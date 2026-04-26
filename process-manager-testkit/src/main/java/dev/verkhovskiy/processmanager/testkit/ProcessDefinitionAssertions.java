package dev.verkhovskiy.processmanager.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.StateKind;

/** Проверки для тестов описания процесса. */
public final class ProcessDefinitionAssertions {

  private ProcessDefinitionAssertions() {}

  public static void assertHasState(
      ProcessDefinition<?> definition, String stateName, StateKind kind) {
    assertThat(definition.states())
        .containsKey(stateName)
        .extractingByKey(stateName)
        .extracting("kind")
        .isEqualTo(kind);
  }

  public static void assertTransition(
      ProcessDefinition<?> definition,
      String fromState,
      String transitionName,
      String targetState) {
    assertThat(definition.state(fromState).transitions())
        .anySatisfy(
            transition -> {
              assertThat(transition.name()).isEqualTo(transitionName);
              assertThat(transition.targetState()).isEqualTo(targetState);
            });
  }
}
