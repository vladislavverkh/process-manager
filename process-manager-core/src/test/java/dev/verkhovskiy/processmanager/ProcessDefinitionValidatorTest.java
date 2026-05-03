package dev.verkhovskiy.processmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessDefinitionValidatorTest {

  @Test
  void acceptsValidDefinition() {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .initialState("SEND")
            .actionState(
                "SEND",
                state ->
                    state
                        .action(ctx -> StepResult.success("OK"))
                        .transition(
                            transition ->
                                transition
                                    .name("sent")
                                    .targetState("DONE")
                                    .condition(ctx -> ctx.resultCodeEquals("OK"))))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();

    assertThat(ProcessDefinitionValidator.validate(definition)).isEmpty();
  }

  @Test
  void rejectsActionWithoutHandler() {
    assertThatThrownBy(
            () ->
                ProcessDefinition.builder("payment", PaymentPayload.class)
                    .initialState("SEND")
                    .actionState(
                        "SEND",
                        state ->
                            state.transition(
                                transition ->
                                    transition
                                        .name("sent")
                                        .targetState("DONE")
                                        .condition(ctx -> true)))
                    .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
                    .build())
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("ACTION_WITHOUT_HANDLER[SEND]");
  }

  @Test
  void rejectsUnreachableState() {
    ProcessDefinition<PaymentPayload> definition =
        new ProcessDefinition<>(
            "payment",
            1,
            1,
            PaymentPayload.class,
            "SEND",
            ProcessRetention.defaults(),
            null,
            null,
            Map.of(
                "SEND",
                new StateDefinition<>(
                    "SEND",
                    StateKind.ACTION,
                    ctx -> StepResult.success("OK"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    RetryPolicy.none(),
                    null,
                    java.util.List.of(new TransitionDefinition<>("sent", "DONE", 0, ctx -> true))),
                "DONE",
                terminal("DONE"),
                "ORPHAN",
                terminal("ORPHAN")));

    assertThat(ProcessDefinitionValidator.validate(definition))
        .extracting(ProcessDefinitionProblem::code, ProcessDefinitionProblem::state)
        .contains(org.assertj.core.api.Assertions.tuple("UNREACHABLE_STATE", "ORPHAN"));
  }

  @Test
  void rejectsDuplicateTransitionPriorities() {
    assertThatThrownBy(
            () ->
                ProcessDefinition.builder("payment", PaymentPayload.class)
                    .initialState("DECIDE")
                    .decisionState(
                        "DECIDE",
                        state ->
                            state
                                .transition(
                                    transition ->
                                        transition
                                            .name("first")
                                            .targetState("DONE")
                                            .priority(1)
                                            .condition(ctx -> true))
                                .transition(
                                    transition ->
                                        transition
                                            .name("second")
                                            .targetState("FAILED")
                                            .priority(1)
                                            .condition(ctx -> true)))
                    .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
                    .terminalState("FAILED", ProcessInstanceStatus.FAILED)
                    .build())
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("DUPLICATE_TRANSITION_PRIORITY[DECIDE]");
  }

  @Test
  void rejectsStateWithoutTerminalPath() {
    assertThatThrownBy(
            () ->
                ProcessDefinition.builder("payment", PaymentPayload.class)
                    .initialState("LOOP")
                    .decisionState("LOOP", state -> state.otherwise("LOOP"))
                    .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
                    .build())
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("NO_TERMINAL_PATH[LOOP]");
  }

  @Test
  void rejectsTimerWithoutDelay() {
    assertThatThrownBy(
            () ->
                ProcessDefinition.builder("payment", PaymentPayload.class)
                    .initialState("WAIT_BEFORE_POLL")
                    .timerState("WAIT_BEFORE_POLL", state -> state.targetState("DONE"))
                    .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
                    .build())
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("TIMER_WITHOUT_DELAY[WAIT_BEFORE_POLL]");
  }

  @Test
  void rejectsTimerWithTransitions() {
    assertThatThrownBy(
            () ->
                ProcessDefinition.builder("payment", PaymentPayload.class)
                    .initialState("WAIT_BEFORE_POLL")
                    .timerState(
                        "WAIT_BEFORE_POLL",
                        state ->
                            state
                                .delay(Duration.ofSeconds(30))
                                .targetState("DONE")
                                .otherwise("DONE"))
                    .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
                    .build())
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("TIMER_WITH_TRANSITIONS[WAIT_BEFORE_POLL]");
  }

  @Test
  void rejectsUnknownRetryExhaustedTarget() {
    assertThatThrownBy(
            () ->
                ProcessDefinition.builder("payment", PaymentPayload.class)
                    .initialState("SEND")
                    .actionState(
                        "SEND",
                        state ->
                            state
                                .action(ctx -> StepResult.retryableFailure("TEMPORARY_ERROR", ""))
                                .retryExhaustedTargetState("PARKED")
                                .otherwise("DONE"))
                    .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
                    .build())
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("UNKNOWN_RETRY_EXHAUSTED_TARGET[SEND]");
  }

  @Test
  void rejectsRetryExhaustedTargetOnNonActionState() {
    assertThatThrownBy(
            () ->
                ProcessDefinition.builder("payment", PaymentPayload.class)
                    .initialState("WAIT_RESULT")
                    .waitState(
                        "WAIT_RESULT",
                        state ->
                            state
                                .eventType("payment.result")
                                .correlationKey(ctx -> ctx.payload().paymentId())
                                .retryExhaustedTargetState("DONE")
                                .otherwise("DONE"))
                    .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
                    .build())
        .isInstanceOf(ProcessDefinitionException.class)
        .hasMessageContaining("RETRY_EXHAUSTED_TARGET_ON_NON_ACTION[WAIT_RESULT]");
  }

  private static StateDefinition<PaymentPayload> terminal(String name) {
    return new StateDefinition<>(
        name,
        StateKind.TERMINAL,
        null,
        null,
        null,
        null,
        null,
        null,
        RetryPolicy.none(),
        ProcessInstanceStatus.COMPLETED,
        java.util.List.of());
  }

  private record PaymentPayload(String paymentId) {}
}
