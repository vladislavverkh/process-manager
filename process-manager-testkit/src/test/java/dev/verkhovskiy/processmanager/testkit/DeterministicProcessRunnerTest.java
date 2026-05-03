package dev.verkhovskiy.processmanager.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.RetryPolicy;
import dev.verkhovskiy.processmanager.StepResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeterministicProcessRunnerTest {

  @Test
  void runsActionsUntilWaitAndThenConsumesSignal() {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .initialState("SEND_PAYMENT")
            .actionState(
                "SEND_PAYMENT",
                state ->
                    state
                        .action(
                            ctx ->
                                StepResult.success(
                                        "ACCEPTED", Map.of("providerPaymentId", "provider-1"))
                                    .withVariable("attempted", true))
                        .transition(
                            transition ->
                                transition
                                    .name("accepted")
                                    .targetState("WAIT_PAYMENT_RESULT")
                                    .condition(ctx -> ctx.resultCodeEquals("ACCEPTED"))))
            .waitState(
                "WAIT_PAYMENT_RESULT",
                state ->
                    state
                        .eventType("payment.result")
                        .correlationKey(ctx -> ctx.payload().paymentId())
                        .waitTimeout(Duration.ofMinutes(5))
                        .transition(
                            transition ->
                                transition
                                    .name("approved")
                                    .targetState("DONE")
                                    .condition(ctx -> ctx.eventFieldEquals("approved", true)))
                        .otherwise("FAILED"))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .terminalState("FAILED", ProcessInstanceStatus.FAILED)
            .build();

    DeterministicProcessRunner<PaymentPayload> runner =
        DeterministicProcessRunner.start(definition, "payment-1", new PaymentPayload("payment-1"))
            .setNow(Instant.parse("2026-05-03T10:15:30Z"));

    runner.runUntilBlocked();

    assertThat(runner.state()).isEqualTo("WAIT_PAYMENT_RESULT");
    assertThat(runner.status()).isEqualTo(ProcessInstanceStatus.WAITING);
    assertThat(runner.version()).isEqualTo(1);
    assertThat(runner.variables().values())
        .containsEntry("providerPaymentId", "provider-1")
        .containsEntry("attempted", true);
    assertThat(mapVariable(runner, "_pm.lastActionResult"))
        .containsEntry("kind", "SUCCESS")
        .containsEntry("code", "ACCEPTED");
    assertThat(runner.history())
        .extracting(TestProcessHistoryRecord::transitionName)
        .containsExactly("accepted");

    runner.signal("payment.result", "payment-1", "event-1", Map.of("approved", true));

    assertThat(runner.state()).isEqualTo("DONE");
    assertThat(runner.status()).isEqualTo(ProcessInstanceStatus.COMPLETED);
    assertThat(runner.version()).isEqualTo(2);
    assertThat(mapVariable(runner, "_pm.lastTrigger"))
        .containsEntry("type", "EVENT")
        .containsEntry("eventType", "payment.result")
        .containsEntry("correlationKey", "payment-1")
        .containsEntry("idempotencyKey", "event-1");
    assertThat(mapVariable(runner, "_pm.lastEvent"))
        .containsEntry("eventType", "payment.result")
        .containsEntry("correlationKey", "payment-1");
    assertThat(runner.history())
        .extracting(TestProcessHistoryRecord::transitionName)
        .containsExactly("accepted", "approved");
  }

  @Test
  void evaluatesDecisionStatesDeterministically() {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .initialState("ROUTE")
            .decisionState(
                "ROUTE",
                state ->
                    state
                        .transition(
                            transition ->
                                transition
                                    .name("large")
                                    .targetState("MANUAL_REVIEW")
                                    .condition(ctx -> ctx.payload().amount() > 10_000))
                        .otherwise("DONE"))
            .terminalState("MANUAL_REVIEW", ProcessInstanceStatus.CANCELLED)
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();

    DeterministicProcessRunner<PaymentPayload> runner =
        DeterministicProcessRunner.start(
            definition, "payment-1", new PaymentPayload("payment-1", 42));

    runner.step();

    assertThat(runner.state()).isEqualTo("DONE");
    assertThat(runner.status()).isEqualTo(ProcessInstanceStatus.COMPLETED);
    assertThat(runner.lastHistory().triggerType()).isEqualTo("DECISION");
  }

  @Test
  void firesTimerWithoutWaitingForPostgresDeadline() {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .initialState("WAIT_RETRY_WINDOW")
            .timerState(
                "WAIT_RETRY_WINDOW",
                state -> state.delay(Duration.ofSeconds(30)).targetState("DONE"))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();

    DeterministicProcessRunner<PaymentPayload> runner =
        DeterministicProcessRunner.start(definition, "payment-1", new PaymentPayload("payment-1"));

    runner.fireTimer();

    assertThat(runner.state()).isEqualTo("DONE");
    assertThat(runner.status()).isEqualTo(ProcessInstanceStatus.COMPLETED);
    assertThat(runner.lastHistory().transitionName()).isEqualTo("timer-fired");
    assertThat(runner.lastHistory().triggerType()).isEqualTo("TIMER");
    assertThat(runner.lastHistory().trigger())
        .containsEntry("state", "WAIT_RETRY_WINDOW")
        .containsEntry("targetState", "DONE")
        .containsEntry("delayMillis", 30_000L);
  }

  @Test
  void recordsRetryAndRoutesRetryExhaustion() {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .initialState("SEND_PAYMENT")
            .actionState(
                "SEND_PAYMENT",
                state ->
                    state
                        .action(ctx -> StepResult.retryableFailure("IO_ERROR", "gateway is down"))
                        .retry(
                            RetryPolicy.exponential(
                                1, Duration.ofSeconds(5), Duration.ofSeconds(5)))
                        .retryExhaustedTargetState("TECHNICAL_FAILURE")
                        .otherwise("TECHNICAL_FAILURE"))
            .terminalState("TECHNICAL_FAILURE", ProcessInstanceStatus.FAILED)
            .build();

    DeterministicProcessRunner<PaymentPayload> runner =
        DeterministicProcessRunner.start(definition, "payment-1", new PaymentPayload("payment-1"));

    runner.runUntilBlocked();

    assertThat(runner.state()).isEqualTo("SEND_PAYMENT");
    assertThat(runner.status()).isEqualTo(ProcessInstanceStatus.RUNNING);
    assertThat(runner.version()).isEqualTo(1);
    assertThat(runner.variables().values()).containsEntry("_pm.retry.SEND_PAYMENT.attempt", 1);
    assertThat(mapVariable(runner, "_pm.lastTrigger"))
        .containsEntry("type", "RETRY")
        .containsEntry("state", "SEND_PAYMENT")
        .containsEntry("attempt", 1);
    assertThat(runner.lastHistory().transitionName()).isEqualTo("retry");
    assertThat(runner.lastHistory().fromState()).isEqualTo("SEND_PAYMENT");
    assertThat(runner.lastHistory().toState()).isEqualTo("SEND_PAYMENT");

    runner.executeAction();

    assertThat(runner.state()).isEqualTo("TECHNICAL_FAILURE");
    assertThat(runner.status()).isEqualTo(ProcessInstanceStatus.FAILED);
    assertThat(runner.version()).isEqualTo(2);
    assertThat(runner.lastHistory().transitionName()).isEqualTo("retry-exhausted");
    assertThat(runner.lastHistory().triggerType()).isEqualTo("RETRY_EXHAUSTED");
    assertThat(mapVariable(runner, "_pm.lastRetry"))
        .containsEntry("state", "SEND_PAYMENT")
        .containsEntry("attempt", 1)
        .containsEntry("targetState", "TECHNICAL_FAILURE");
  }

  private record PaymentPayload(String paymentId, long amount) {
    private PaymentPayload(String paymentId) {
      this(paymentId, 0);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapVariable(
      DeterministicProcessRunner<?> runner, String variableName) {
    return (Map<String, Object>) runner.variables().values().get(variableName);
  }
}
