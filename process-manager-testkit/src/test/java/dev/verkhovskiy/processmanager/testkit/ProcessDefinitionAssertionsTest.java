package dev.verkhovskiy.processmanager.testkit;

import static dev.verkhovskiy.processmanager.testkit.ProcessDefinitionAssertions.assertThat;

import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.StateKind;
import dev.verkhovskiy.processmanager.StepResult;
import org.junit.jupiter.api.Test;

class ProcessDefinitionAssertionsTest {

  @Test
  void supportsFluentAssertions() {
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
                                transition.name("sent").targetState("DONE").condition(ctx -> true)))
            .terminalState("DONE", ProcessInstanceStatus.COMPLETED)
            .build();

    assertThat(definition)
        .isValid()
        .hasState("SEND", StateKind.ACTION)
        .hasTransition("SEND", "sent", "DONE")
        .hasNoUnreachableStates()
        .canReachTerminal("DONE");
  }

  private record PaymentPayload(String paymentId) {}
}
