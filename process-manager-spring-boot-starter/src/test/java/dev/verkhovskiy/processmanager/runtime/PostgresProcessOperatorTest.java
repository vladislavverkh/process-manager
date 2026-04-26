package dev.verkhovskiy.processmanager.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.ProcessHistoryRecord;
import dev.verkhovskiy.processmanager.postgres.StoredProcessInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresProcessOperatorTest {

  private static final UUID INSTANCE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
  private static final Instant STATE_ENTERED_AT = Instant.parse("2026-04-26T10:00:00Z");

  @Mock private PostgresProcessRepository processRepository;
  @Mock private ProcessCommandScheduler commandScheduler;

  @Captor private ArgumentCaptor<String> variablesCaptor;
  @Captor private ArgumentCaptor<ProcessHistoryRecord> historyCaptor;
  @Captor private ArgumentCaptor<ProcessCommand> commandCaptor;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {};

  @Test
  void cancelActiveInstanceMovesItToCancelledAndWritesHistory() throws Exception {
    PostgresProcessOperator operator = operator();
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("WAIT_RESULT", ProcessInstanceStatus.WAITING, 4)));
    when(processRepository.updateExecutionState(
            eq(INSTANCE_ID),
            eq(4L),
            eq("WAIT_RESULT"),
            eq(ProcessInstanceStatus.CANCELLED),
            any(),
            eq(STATE_ENTERED_AT),
            isNull(),
            any(),
            any()))
        .thenReturn(1);

    boolean cancelled = operator.cancel(INSTANCE_ID, "client requested cancellation");

    assertThat(cancelled).isTrue();
    verify(processRepository).deleteWaits(INSTANCE_ID);
    verify(processRepository)
        .updateExecutionState(
            eq(INSTANCE_ID),
            eq(4L),
            eq("WAIT_RESULT"),
            eq(ProcessInstanceStatus.CANCELLED),
            variablesCaptor.capture(),
            eq(STATE_ENTERED_AT),
            isNull(),
            any(),
            any());
    Map<String, Object> variables = objectMapper.readValue(variablesCaptor.getValue(), mapType);
    assertThat(nested(variables, "_pm.lastCancel"))
        .containsEntry("reason", "client requested cancellation");
    assertThat(nested(variables, "_pm.lastTrigger"))
        .containsEntry("type", "MANUAL_CANCEL")
        .containsEntry("reason", "client requested cancellation");
    verify(processRepository).insertHistory(historyCaptor.capture());
    ProcessHistoryRecord history = historyCaptor.getValue();
    assertThat(history.fromState()).isEqualTo("WAIT_RESULT");
    assertThat(history.toState()).isEqualTo("WAIT_RESULT");
    assertThat(history.transitionName()).isEqualTo("manual-cancel");
    assertThat(history.triggerType()).isEqualTo("MANUAL_CANCEL");
    assertThat(history.triggerJson()).contains("client requested cancellation");
    verifyNoInteractions(commandScheduler);
  }

  @Test
  void cancelTerminalInstanceDoesNothing() {
    PostgresProcessOperator operator = operator();
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("DONE", ProcessInstanceStatus.COMPLETED, 5)));

    boolean cancelled = operator.cancel(INSTANCE_ID, "too late");

    assertThat(cancelled).isFalse();
    verify(processRepository, never())
        .updateExecutionState(any(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    verifyNoInteractions(commandScheduler);
  }

  @Test
  void scheduleResumeUsesCurrentVersion() {
    PostgresProcessOperator operator = operator();
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("WAIT_RESULT", ProcessInstanceStatus.WAITING, 6)));

    boolean scheduled = operator.scheduleResume(INSTANCE_ID);

    assertThat(scheduled).isTrue();
    verify(commandScheduler).schedule(commandCaptor.capture(), eq("payment:payment-1"));
    assertThat(commandCaptor.getValue())
        .isEqualTo(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.RESUME, 6));
  }

  @Test
  void scheduleRetryRequiresRunningInstance() {
    PostgresProcessOperator operator = operator();
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("WAIT_RESULT", ProcessInstanceStatus.WAITING, 6)));

    boolean scheduled = operator.scheduleRetry(INSTANCE_ID);

    assertThat(scheduled).isFalse();
    verifyNoInteractions(commandScheduler);
  }

  @Test
  void scheduleRetryUsesCurrentVersionForRunningInstance() {
    PostgresProcessOperator operator = operator();
    when(processRepository.findInstanceForUpdate(INSTANCE_ID))
        .thenReturn(Optional.of(instance("SEND", ProcessInstanceStatus.RUNNING, 7)));

    boolean scheduled = operator.scheduleRetry(INSTANCE_ID);

    assertThat(scheduled).isTrue();
    verify(commandScheduler).schedule(commandCaptor.capture(), eq("payment:payment-1"));
    assertThat(commandCaptor.getValue())
        .isEqualTo(new ProcessCommand(INSTANCE_ID, ProcessCommandReason.RETRY, 7));
  }

  private PostgresProcessOperator operator() {
    return new PostgresProcessOperator(
        new ProcessDefinitionRegistry(List.of(definition())),
        processRepository,
        commandScheduler,
        objectMapper);
  }

  private static ProcessDefinition<PaymentPayload> definition() {
    return ProcessDefinition.builder("payment", PaymentPayload.class)
        .retention(new ProcessRetention(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3)))
        .initialState("WAIT_RESULT")
        .waitState(
            "WAIT_RESULT",
            state ->
                state
                    .eventType("payment.result")
                    .correlationKey(ctx -> ctx.payload().paymentId())
                    .otherwise("DONE"))
        .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
        .build();
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
        "{\"existing\":true}",
        Instant.parse("2026-04-26T09:00:00Z"),
        Instant.parse("2026-04-26T09:00:00Z"),
        null,
        STATE_ENTERED_AT,
        Instant.parse("2026-04-26T10:05:00Z"),
        null,
        null,
        version);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nested(Map<String, Object> values, String key) {
    return (Map<String, Object>) values.get(key);
  }

  private record PaymentPayload(String paymentId) {}
}
