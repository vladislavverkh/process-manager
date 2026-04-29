package dev.verkhovskiy.processmanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProcessDefinitionBuilderTest {

  @Test
  void buildsDefinitionWithNamedStyleDsl() {
    ProcessDefinition<PaymentPayload> definition =
        ProcessDefinition.builder("payment", PaymentPayload.class)
            .version(2)
            .payloadSchemaVersion(3)
            .initialState("SEND")
            .processTimeout(
                timeout -> timeout.duration(Duration.ofHours(2)).targetState("TECHNICAL_FAILURE"))
            .actionState(
                "SEND",
                state ->
                    state
                        .action(ctx -> StepResult.success("ACCEPTED"))
                        .timeout(
                            timeout ->
                                timeout
                                    .duration(Duration.ofMinutes(10))
                                    .targetState("TECHNICAL_FAILURE"))
                        .transition(
                            transition ->
                                transition
                                    .name("accepted")
                                    .targetState("WAIT_BEFORE_RESULT_POLL")
                                    .condition(ctx -> ctx.resultCodeEquals("ACCEPTED"))))
            .timerState(
                "WAIT_BEFORE_RESULT_POLL",
                state -> state.delay(Duration.ofSeconds(30)).targetState("WAIT_RESULT"))
            .waitState(
                "WAIT_RESULT",
                state ->
                    state
                        .eventType("payment.result")
                        .correlationKey(ctx -> ctx.payload().paymentId())
                        .waitTimeout(Duration.ofHours(1))
                        .timeoutTransition("TECHNICAL_FAILURE")
                        .transition(
                            transition ->
                                transition
                                    .name("approved")
                                    .targetState("DONE")
                                    .condition(ctx -> ctx.eventFieldEquals("status", "APPROVED"))))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .terminalState("TECHNICAL_FAILURE", ProcessInstanceStatus.FAILED)
            .build();

    assertThat(definition.processTimeout()).isEqualTo(Duration.ofHours(2));
    assertThat(definition.processTimeoutTargetState()).isEqualTo("TECHNICAL_FAILURE");
    assertThat(definition.state("SEND").stateTimeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(definition.state("SEND").timeoutTargetState()).isEqualTo("TECHNICAL_FAILURE");
    assertThat(definition.state("WAIT_BEFORE_RESULT_POLL").kind()).isEqualTo(StateKind.TIMER);
    assertThat(definition.state("WAIT_BEFORE_RESULT_POLL").stateTimeout())
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(definition.state("WAIT_BEFORE_RESULT_POLL").timeoutTargetState())
        .isEqualTo("WAIT_RESULT");
    assertThat(definition.state("WAIT_RESULT").eventType()).isEqualTo("payment.result");
    assertThat(definition.state("WAIT_RESULT").waitTimeout()).isEqualTo(Duration.ofHours(1));
    assertThat(definition.state("WAIT_RESULT").timeoutTargetState()).isEqualTo("TECHNICAL_FAILURE");
    assertThat(definition.state("WAIT_RESULT").transitions().getFirst().name())
        .isEqualTo("approved");
  }

  private record PaymentPayload(String paymentId) {}
}
