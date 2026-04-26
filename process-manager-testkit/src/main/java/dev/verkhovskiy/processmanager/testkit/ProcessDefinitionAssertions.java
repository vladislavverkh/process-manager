package dev.verkhovskiy.processmanager.testkit;

import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionProblem;
import dev.verkhovskiy.processmanager.ProcessDefinitionValidator;
import dev.verkhovskiy.processmanager.StateKind;
import dev.verkhovskiy.processmanager.TransitionDefinition;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;
import org.assertj.core.api.Assertions;

/** Проверки для тестов описания процесса. */
public final class ProcessDefinitionAssertions {

  private ProcessDefinitionAssertions() {}

  /** Создает fluent assertions для process definition. */
  public static ProcessDefinitionAssert assertThat(ProcessDefinition<?> definition) {
    return new ProcessDefinitionAssert(definition);
  }

  public static void assertHasState(
      ProcessDefinition<?> definition, String stateName, StateKind kind) {
    assertThat(definition).hasState(stateName, kind);
  }

  public static void assertTransition(
      ProcessDefinition<?> definition,
      String fromState,
      String transitionName,
      String targetState) {
    assertThat(definition).hasTransition(fromState, transitionName, targetState);
  }

  /** Fluent assertions для process definition. */
  public static final class ProcessDefinitionAssert {

    private final ProcessDefinition<?> definition;

    private ProcessDefinitionAssert(ProcessDefinition<?> definition) {
      this.definition = definition;
    }

    public ProcessDefinitionAssert isValid() {
      Assertions.assertThat(ProcessDefinitionValidator.validate(definition)).isEmpty();
      return this;
    }

    public ProcessDefinitionAssert hasState(String stateName, StateKind kind) {
      Assertions.assertThat(definition.states())
          .containsKey(stateName)
          .extractingByKey(stateName)
          .extracting("kind")
          .isEqualTo(kind);
      return this;
    }

    public ProcessDefinitionAssert hasTransition(
        String fromState, String transitionName, String targetState) {
      Assertions.assertThat(definition.state(fromState).transitions())
          .anySatisfy(
              transition -> {
                Assertions.assertThat(transition.name()).isEqualTo(transitionName);
                Assertions.assertThat(transition.targetState()).isEqualTo(targetState);
              });
      return this;
    }

    public ProcessDefinitionAssert hasNoUnreachableStates() {
      Assertions.assertThat(ProcessDefinitionValidator.validate(definition))
          .filteredOn(problem -> "UNREACHABLE_STATE".equals(problem.code()))
          .isEmpty();
      return this;
    }

    public ProcessDefinitionAssert canReachTerminal(String terminalState) {
      Assertions.assertThat(definition.state(terminalState).terminal()).isTrue();
      Assertions.assertThat(reachableStates()).contains(terminalState);
      return this;
    }

    public ProcessDefinitionAssert hasProblem(String code, String state) {
      Assertions.assertThat(ProcessDefinitionValidator.validate(definition))
          .extracting(ProcessDefinitionProblem::code, ProcessDefinitionProblem::state)
          .contains(Assertions.tuple(code, state));
      return this;
    }

    private Set<String> reachableStates() {
      Set<String> visited = new LinkedHashSet<>();
      Queue<String> queue = new ArrayDeque<>();
      queue.add(definition.initialState());
      while (!queue.isEmpty()) {
        String stateName = queue.remove();
        if (!visited.add(stateName) || definition.state(stateName).terminal()) {
          continue;
        }
        for (TransitionDefinition<?> transition : definition.state(stateName).transitions()) {
          queue.add(transition.targetState());
        }
        if (definition.state(stateName).timeoutTargetState() != null) {
          queue.add(definition.state(stateName).timeoutTargetState());
        }
        if (definition.processTimeoutTargetState() != null) {
          queue.add(definition.processTimeoutTargetState());
        }
      }
      return visited;
    }
  }
}
