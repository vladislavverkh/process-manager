package dev.verkhovskiy.processmanager.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionRegistry;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessRetention;
import dev.verkhovskiy.processmanager.RetryPolicy;
import dev.verkhovskiy.processmanager.StepResult;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.ProcessHistoryRecord;
import dev.verkhovskiy.processmanager.postgres.StoredProcessEvent;
import dev.verkhovskiy.processmanager.postgres.StoredProcessInstance;
import dev.verkhovskiy.processmanager.postgres.StoredProcessWait;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresProcessManagerTest {

  private static final UUID INSTANCE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
  private static final UUID EVENT_ID = UUID.fromString("018f0000-0000-7000-8000-000000000002");

  @Mock private PostgresProcessRepository processRepository;
  @Mock private ProcessCommandScheduler commandScheduler;

  @Captor private ArgumentCaptor<ProcessHistoryRecord> historyCaptor;
  @Captor private ArgumentCaptor<StoredProcessWait> waitCaptor;
  @Captor private ArgumentCaptor<String> variablesCaptor;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};

  @Test
  void executesActionAndEntersTerminalState() throws Exception {
    AtomicReference<PaymentPayload> seenPayload = new AtomicReference<>();
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .version(1)
            .payloadSchemaVersion(1)
            .initialState("SEND")
            .retention(
                new ProcessRetention(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3)))
            .actionState(
                "SEND",
                ctx -> {
                  seenPayload.set(ctx.payload());
                  return StepResult.success("OK", Map.of("providerPaymentId", "provider-1"))
                      .withVariable("operationId", "operation-1");
                },
                state -> state.transition("sent", "DONE", ctx -> ctx.resultCodeEquals("OK")))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();
    PostgresProcessManager manager = manager(definition);
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("SEND", ProcessInstanceStatus.RUNNING, 0)));
    when(processRepository.updateExecutionState(
            eq(INSTANCE_ID),
            eq(0L),
            eq("DONE"),
            eq(ProcessInstanceStatus.COMPLETED),
            any(),
            any(),
            any()))
        .thenReturn(1);

    manager.resume(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.START, 0));

    assertThat(seenPayload.get().paymentId()).isEqualTo("pay-1");
    verify(processRepository).insertHistory(historyCaptor.capture());
    ProcessHistoryRecord history = historyCaptor.getValue();
    assertThat(history.fromState()).isEqualTo("SEND");
    assertThat(history.toState()).isEqualTo("DONE");
    assertThat(history.transitionName()).isEqualTo("sent");
    assertThat(history.triggerType()).isEqualTo("ACTION_RESULT");
    assertThat(history.triggerJson()).contains("\"kind\":\"SUCCESS\"");
    verify(processRepository)
        .updateExecutionState(
            eq(INSTANCE_ID),
            eq(0L),
            eq("DONE"),
            eq(ProcessInstanceStatus.COMPLETED),
            variablesCaptor.capture(),
            any(),
            any());
    Map<String, Object> variables = jsonMap(variablesCaptor.getValue());
    assertThat(variables)
        .containsEntry("providerPaymentId", "provider-1")
        .containsEntry("operationId", "operation-1");
    assertThat(nested(variables, "_pm.lastActionResult"))
        .containsEntry("kind", "SUCCESS")
        .containsEntry("code", "OK");
    assertThat(nested(variables, "_pm.lastTrigger")).containsEntry("type", "ACTION_RESULT");
  }

  @Test
  void enteringWaitStateRegistersWaitAndSchedulesTimeout() {
    Duration timeout = Duration.ofMinutes(5);
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .version(1)
            .payloadSchemaVersion(1)
            .initialState("SEND")
            .actionState(
                "SEND",
                ctx -> StepResult.success("ACCEPTED"),
                state ->
                    state.transition(
                        "accepted", "WAIT_RESULT", ctx -> ctx.resultCodeEquals("ACCEPTED")))
            .waitState(
                "WAIT_RESULT",
                "payment.result",
                ctx -> ctx.payload().paymentId(),
                timeout,
                state ->
                    state.transition(
                        "approved", "DONE", ctx -> ctx.eventFieldEquals("status", "APPROVED")))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();
    PostgresProcessManager manager = manager(definition);
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("SEND", ProcessInstanceStatus.RUNNING, 0)));
    when(processRepository.updateExecutionState(
            eq(INSTANCE_ID),
            eq(0L),
            eq("WAIT_RESULT"),
            eq(ProcessInstanceStatus.WAITING),
            any(),
            eq(null),
            eq(null)))
        .thenReturn(1);

    manager.resume(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.START, 0));

    verify(processRepository).upsertWait(waitCaptor.capture());
    StoredProcessWait wait = waitCaptor.getValue();
    assertThat(wait.instanceId()).isEqualTo(INSTANCE_ID);
    assertThat(wait.state()).isEqualTo("WAIT_RESULT");
    assertThat(wait.eventType()).isEqualTo("payment.result");
    assertThat(wait.correlationKey()).isEqualTo("pay-1");
    verify(commandScheduler)
        .scheduleDelayed(
            new ProcessCommand(INSTANCE_ID, ProcessCommandReason.TIMEOUT, 1),
            "payment:payment-1",
            timeout);
  }

  @Test
  void resumesWaitingStateFromInboxEvent() throws Exception {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .version(1)
            .payloadSchemaVersion(1)
            .initialState("WAIT_RESULT")
            .waitState(
                "WAIT_RESULT",
                "payment.result",
                ctx -> ctx.payload().paymentId(),
                Duration.ofMinutes(5),
                state ->
                    state.transition(
                        "approved", "DONE", ctx -> ctx.eventFieldEquals("status", "APPROVED")))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();
    PostgresProcessManager manager = manager(definition);
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("WAIT_RESULT", ProcessInstanceStatus.WAITING, 3)));
    when(processRepository.findUnconsumedEventForUpdate("payment.result", "pay-1"))
        .thenReturn(
            Optional.of(
                new StoredProcessEvent(
                    EVENT_ID,
                    "payment.result",
                    "pay-1",
                    "{\"status\":\"APPROVED\"}",
                    Instant.parse("2026-04-26T12:00:00Z"),
                    null)));
    when(processRepository.updateExecutionState(
            eq(INSTANCE_ID),
            eq(3L),
            eq("DONE"),
            eq(ProcessInstanceStatus.COMPLETED),
            any(),
            any(),
            any()))
        .thenReturn(1);

    manager.resume(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.RESUME, -1));

    verify(processRepository).deleteWaits(INSTANCE_ID);
    verify(processRepository).markEventConsumed(EVENT_ID);
    verify(processRepository).insertHistory(historyCaptor.capture());
    ProcessHistoryRecord history = historyCaptor.getValue();
    assertThat(history.fromState()).isEqualTo("WAIT_RESULT");
    assertThat(history.toState()).isEqualTo("DONE");
    assertThat(history.transitionName()).isEqualTo("approved");
    assertThat(history.triggerType()).isEqualTo("EVENT");
    assertThat(history.triggerJson()).contains("\"status\":\"APPROVED\"");
    verify(processRepository)
        .updateExecutionState(
            eq(INSTANCE_ID),
            eq(3L),
            eq("DONE"),
            eq(ProcessInstanceStatus.COMPLETED),
            variablesCaptor.capture(),
            any(),
            any());
    Map<String, Object> variables = jsonMap(variablesCaptor.getValue());
    assertThat(nested(variables, "_pm.lastTrigger")).containsEntry("type", "EVENT");
    assertThat(nested(nested(variables, "_pm.lastEvent"), "payload"))
        .containsEntry("status", "APPROVED");
  }

  @Test
  void decisionCanUseVariablesStoredFromActionResult() throws Exception {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .version(1)
            .payloadSchemaVersion(1)
            .initialState("SEND")
            .actionState(
                "SEND",
                ctx ->
                    StepResult.success("OK", Map.of("providerPaymentId", "provider-1"))
                        .withVariable("manualReview", true),
                state -> state.transition("sent", "DECIDE", ctx -> ctx.resultCodeEquals("OK")))
            .decisionState(
                "DECIDE",
                state ->
                    state
                        .transition(
                            "manual-review",
                            "REVIEW",
                            ctx ->
                                Boolean.TRUE.equals(
                                        ctx.variables().get("manualReview").orElse(false))
                                    && ctx.variables()
                                        .string("providerPaymentId")
                                        .orElse("")
                                        .equals("provider-1"))
                        .otherwise("DONE"))
            .terminalState("REVIEW", ProcessInstanceStatus.COMPLETED)
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();
    PostgresProcessManager manager = manager(definition);
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("SEND", ProcessInstanceStatus.RUNNING, 0)));
    when(processRepository.updateExecutionState(
            eq(INSTANCE_ID), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    manager.resume(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.START, 0));

    verify(processRepository)
        .updateExecutionState(
            eq(INSTANCE_ID),
            eq(0L),
            eq("DECIDE"),
            eq(ProcessInstanceStatus.RUNNING),
            variablesCaptor.capture(),
            any(),
            any());
    verify(processRepository)
        .updateExecutionState(
            eq(INSTANCE_ID),
            eq(1L),
            eq("REVIEW"),
            eq(ProcessInstanceStatus.COMPLETED),
            variablesCaptor.capture(),
            any(),
            any());
    Map<String, Object> variables = jsonMap(variablesCaptor.getAllValues().getLast());
    assertThat(variables)
        .containsEntry("providerPaymentId", "provider-1")
        .containsEntry("manualReview", true);
  }

  @Test
  void retryStoresTriggerMetadata() throws Exception {
    Duration retryDelay = Duration.ofSeconds(1);
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .version(1)
            .payloadSchemaVersion(1)
            .initialState("SEND")
            .actionState(
                "SEND",
                ctx -> StepResult.retryableFailure("TEMPORARY_ERROR", "temporary outage"),
                state ->
                    state
                        .retry(RetryPolicy.exponential(2, retryDelay, Duration.ofSeconds(10)))
                        .otherwise("FAILED"))
            .terminalState("FAILED", ProcessInstanceStatus.FAILED)
            .build();
    PostgresProcessManager manager = manager(definition);
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("SEND", ProcessInstanceStatus.RUNNING, 0)));
    when(processRepository.updateExecutionState(
            eq(INSTANCE_ID),
            eq(0L),
            eq("SEND"),
            eq(ProcessInstanceStatus.RUNNING),
            any(),
            eq(null),
            eq(null)))
        .thenReturn(1);

    manager.resume(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.START, 0));

    verify(commandScheduler)
        .scheduleDelayed(
            new ProcessCommand(INSTANCE_ID, ProcessCommandReason.RETRY, 1),
            "payment:payment-1",
            retryDelay);
    verify(processRepository)
        .updateExecutionState(
            eq(INSTANCE_ID),
            eq(0L),
            eq("SEND"),
            eq(ProcessInstanceStatus.RUNNING),
            variablesCaptor.capture(),
            eq(null),
            eq(null));
    Map<String, Object> variables = jsonMap(variablesCaptor.getValue());
    assertThat(nested(variables, "_pm.lastTrigger")).containsEntry("type", "RETRY");
    assertThat(nested(variables, "_pm.lastRetry"))
        .containsEntry("attempt", 1)
        .containsEntry("delay", "PT1S");
    assertThat(nested(nested(variables, "_pm.lastRetry"), "failure"))
        .containsEntry("kind", "RETRYABLE_FAILURE")
        .containsEntry("code", "TEMPORARY_ERROR");
    verify(processRepository).insertHistory(historyCaptor.capture());
    assertThat(historyCaptor.getValue().triggerType()).isEqualTo("RETRY");
  }

  @Test
  void skipsStaleCommand() {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .initialState("DONE")
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();
    PostgresProcessManager manager = manager(definition);
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("DONE", ProcessInstanceStatus.COMPLETED, 4)));

    manager.resume(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.RESUME, 3));

    verify(processRepository, never())
        .updateExecutionState(any(), anyLong(), any(), any(), any(), any(), any());
    verifyNoInteractions(commandScheduler);
  }

  private PostgresProcessManager manager(ProcessDefinition<PaymentPayload> definition) {
    return new PostgresProcessManager(
        new ProcessDefinitionRegistry(List.of(definition)),
        processRepository,
        commandScheduler,
        objectMapper);
  }

  private static StoredProcessInstance instance(
      String state, ProcessInstanceStatus status, long version) {
    return new StoredProcessInstance(
        INSTANCE_ID,
        "payment",
        1,
        1,
        "payment-1",
        state,
        status,
        "{\"paymentId\":\"pay-1\"}",
        "{}",
        Instant.parse("2026-04-26T10:00:00Z"),
        Instant.parse("2026-04-26T10:00:00Z"),
        null,
        null,
        version);
  }

  private Map<String, Object> jsonMap(String json) throws Exception {
    return objectMapper.readValue(json, mapType);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nested(Map<String, Object> values, String key) {
    return (Map<String, Object>) values.get(key);
  }

  private record PaymentPayload(String paymentId) {}
}
