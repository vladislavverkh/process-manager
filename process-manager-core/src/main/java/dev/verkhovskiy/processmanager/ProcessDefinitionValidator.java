package dev.verkhovskiy.processmanager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/** Проверяет структурную корректность графа process definition. */
public final class ProcessDefinitionValidator {

  private ProcessDefinitionValidator() {}

  /** Возвращает все найденные проблемы без выбрасывания исключения. */
  public static List<ProcessDefinitionProblem> validate(ProcessDefinition<?> definition) {
    Objects.requireNonNull(definition, "definition must not be null");
    List<ProcessDefinitionProblem> problems = new ArrayList<>();
    Map<String, ? extends StateDefinition<?>> states = definition.states();
    if (states.isEmpty()) {
      problems.add(
          ProcessDefinitionProblem.global("NO_STATES", "Process definition has no states"));
      return List.copyOf(problems);
    }

    validateTerminals(states, problems);
    states.forEach((stateName, state) -> validateState(definition, stateName, state, problems));
    validateReachability(definition, states, problems);
    return List.copyOf(problems);
  }

  /** Выбрасывает ProcessDefinitionException, если definition содержит ошибки. */
  public static void validateOrThrow(ProcessDefinition<?> definition) {
    List<ProcessDefinitionProblem> problems = validate(definition);
    if (!problems.isEmpty()) {
      String message =
          problems.stream()
              .map(ProcessDefinitionValidator::formatProblem)
              .collect(Collectors.joining("; "));
      throw new ProcessDefinitionException(
          "Invalid process definition "
              + definition.processType()
              + " v"
              + definition.version()
              + ": "
              + message);
    }
  }

  private static void validateTerminals(
      Map<String, ? extends StateDefinition<?>> states, List<ProcessDefinitionProblem> problems) {
    boolean hasTerminal = states.values().stream().anyMatch(StateDefinition::terminal);
    if (!hasTerminal) {
      problems.add(
          ProcessDefinitionProblem.global(
              "NO_TERMINAL_STATE", "Process definition must have at least one terminal state"));
    }
  }

  private static void validateState(
      ProcessDefinition<?> definition,
      String stateName,
      StateDefinition<?> state,
      List<ProcessDefinitionProblem> problems) {
    if (!stateName.equals(state.name())) {
      problems.add(
          ProcessDefinitionProblem.state(
              "STATE_NAME_MISMATCH",
              stateName,
              "State map key must match state name " + state.name()));
    }
    switch (state.kind()) {
      case ACTION -> validateActionState(stateName, state, problems);
      case WAIT -> validateWaitState(stateName, state, problems);
      case DECISION -> validateDecisionState(stateName, state, problems);
      case TERMINAL -> validateTerminalState(stateName, state, problems);
    }
    validateTransitions(definition, stateName, state, problems);
  }

  private static void validateActionState(
      String stateName, StateDefinition<?> state, List<ProcessDefinitionProblem> problems) {
    if (state.action() == null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "ACTION_WITHOUT_HANDLER", stateName, "ACTION state must define action"));
    }
    validateNonTerminalState(stateName, state, problems);
    validateNoWaitFields(stateName, state, problems);
    validateNoTerminalStatus(stateName, state, problems);
  }

  private static void validateWaitState(
      String stateName, StateDefinition<?> state, List<ProcessDefinitionProblem> problems) {
    if (state.eventType() == null || state.eventType().isBlank()) {
      problems.add(
          ProcessDefinitionProblem.state(
              "WAIT_WITHOUT_EVENT_TYPE", stateName, "WAIT state must define eventType"));
    }
    if (state.correlationKeyResolver() == null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "WAIT_WITHOUT_CORRELATION_KEY",
              stateName,
              "WAIT state must define correlationKey resolver"));
    }
    if (state.action() != null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "WAIT_WITH_ACTION", stateName, "WAIT state must not define action"));
    }
    validateNonTerminalState(stateName, state, problems);
    validateNoTerminalStatus(stateName, state, problems);
  }

  private static void validateDecisionState(
      String stateName, StateDefinition<?> state, List<ProcessDefinitionProblem> problems) {
    if (state.action() != null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "DECISION_WITH_ACTION", stateName, "DECISION state must not define action"));
    }
    validateNonTerminalState(stateName, state, problems);
    validateNoWaitFields(stateName, state, problems);
    validateNoTerminalStatus(stateName, state, problems);
  }

  private static void validateTerminalState(
      String stateName, StateDefinition<?> state, List<ProcessDefinitionProblem> problems) {
    if (state.terminalStatus() == null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "TERMINAL_WITHOUT_STATUS", stateName, "TERMINAL state must define terminal status"));
    }
    if (!state.transitions().isEmpty()) {
      problems.add(
          ProcessDefinitionProblem.state(
              "TERMINAL_WITH_TRANSITIONS",
              stateName,
              "TERMINAL state must not define transitions"));
    }
    if (state.action() != null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "TERMINAL_WITH_ACTION", stateName, "TERMINAL state must not define action"));
    }
    if (state.stateTimeout() != null || state.timeoutTargetState() != null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "TERMINAL_WITH_TIMEOUT", stateName, "TERMINAL state must not define timeout"));
    }
    validateNoWaitFields(stateName, state, problems);
  }

  private static void validateNonTerminalState(
      String stateName, StateDefinition<?> state, List<ProcessDefinitionProblem> problems) {
    if (state.transitions().isEmpty()) {
      problems.add(
          ProcessDefinitionProblem.state(
              "NON_TERMINAL_WITHOUT_TRANSITIONS",
              stateName,
              state.kind() + " state must define at least one transition"));
    }
  }

  private static void validateNoWaitFields(
      String stateName, StateDefinition<?> state, List<ProcessDefinitionProblem> problems) {
    if (state.eventType() != null || state.correlationKeyResolver() != null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "NON_WAIT_WITH_WAIT_FIELDS",
              stateName,
              state.kind() + " state must not define WAIT fields"));
    }
    if (state.waitTimeout() != null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "NON_WAIT_WITH_WAIT_TIMEOUT",
              stateName,
              state.kind() + " state must not define waitTimeout"));
    }
  }

  private static void validateNoTerminalStatus(
      String stateName, StateDefinition<?> state, List<ProcessDefinitionProblem> problems) {
    if (state.terminalStatus() != null) {
      problems.add(
          ProcessDefinitionProblem.state(
              "NON_TERMINAL_WITH_STATUS",
              stateName,
              state.kind() + " state must not define terminal status"));
    }
  }

  private static void validateTransitions(
      ProcessDefinition<?> definition,
      String stateName,
      StateDefinition<?> state,
      List<ProcessDefinitionProblem> problems) {
    Map<Integer, Integer> priorities = new HashMap<>();
    for (TransitionDefinition<?> transition : state.transitions()) {
      if (!definition.states().containsKey(transition.targetState())) {
        problems.add(
            ProcessDefinitionProblem.state(
                "UNKNOWN_TRANSITION_TARGET",
                stateName,
                "Transition "
                    + transition.name()
                    + " points to unknown state "
                    + transition.targetState()));
      }
      priorities.merge(transition.priority(), 1, Integer::sum);
    }
    priorities.entrySet().stream()
        .filter(entry -> entry.getValue() > 1)
        .forEach(
            entry ->
                problems.add(
                    ProcessDefinitionProblem.state(
                        "DUPLICATE_TRANSITION_PRIORITY",
                        stateName,
                        "State has duplicate transition priority " + entry.getKey())));
    if (state.timeoutTargetState() != null
        && !definition.states().containsKey(state.timeoutTargetState())) {
      problems.add(
          ProcessDefinitionProblem.state(
              "UNKNOWN_TIMEOUT_TARGET",
              stateName,
              "Timeout points to unknown state " + state.timeoutTargetState()));
    }
  }

  private static void validateReachability(
      ProcessDefinition<?> definition,
      Map<String, ? extends StateDefinition<?>> states,
      List<ProcessDefinitionProblem> problems) {
    if (!states.containsKey(definition.initialState())) {
      return;
    }
    Set<String> terminalStates =
        states.entrySet().stream()
            .filter(entry -> entry.getValue().terminal())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    Set<String> reachable = reachableFrom(definition, definition.initialState());
    states.keySet().stream()
        .filter(stateName -> !reachable.contains(stateName))
        .forEach(
            stateName ->
                problems.add(
                    ProcessDefinitionProblem.state(
                        "UNREACHABLE_STATE",
                        stateName,
                        "State is unreachable from initial state")));
    if (!terminalStates.isEmpty()) {
      Set<String> canReachTerminal = statesThatCanReach(definition, terminalStates);
      reachable.stream()
          .filter(stateName -> !states.get(stateName).terminal())
          .filter(stateName -> !canReachTerminal.contains(stateName))
          .forEach(
              stateName ->
                  problems.add(
                      ProcessDefinitionProblem.state(
                          "NO_TERMINAL_PATH", stateName, "State cannot reach any terminal state")));
    }
  }

  private static Set<String> reachableFrom(ProcessDefinition<?> definition, String startState) {
    Set<String> visited = new LinkedHashSet<>();
    Queue<String> queue = new ArrayDeque<>();
    queue.add(startState);
    while (!queue.isEmpty()) {
      String stateName = queue.remove();
      if (!visited.add(stateName)) {
        continue;
      }
      targets(definition, stateName).forEach(queue::add);
    }
    return visited;
  }

  private static Set<String> statesThatCanReach(
      ProcessDefinition<?> definition, Set<String> targetStates) {
    Set<String> result = new HashSet<>(targetStates);
    boolean changed = true;
    while (changed) {
      changed = false;
      for (String stateName : definition.states().keySet()) {
        if (!result.contains(stateName)
            && targets(definition, stateName).stream().anyMatch(result::contains)) {
          result.add(stateName);
          changed = true;
        }
      }
    }
    return result;
  }

  private static List<String> targets(ProcessDefinition<?> definition, String stateName) {
    StateDefinition<?> state = definition.states().get(stateName);
    if (state == null || state.terminal()) {
      return List.of();
    }
    List<String> targets = new ArrayList<>();
    for (TransitionDefinition<?> transition : state.transitions()) {
      if (definition.states().containsKey(transition.targetState())) {
        targets.add(transition.targetState());
      }
    }
    if (state.timeoutTargetState() != null
        && definition.states().containsKey(state.timeoutTargetState())) {
      targets.add(state.timeoutTargetState());
    }
    if (definition.processTimeoutTargetState() != null
        && definition.states().containsKey(definition.processTimeoutTargetState())) {
      targets.add(definition.processTimeoutTargetState());
    }
    return List.copyOf(targets);
  }

  private static String formatProblem(ProcessDefinitionProblem problem) {
    if (problem.state() == null) {
      return problem.code() + ": " + problem.message();
    }
    return problem.code() + "[" + problem.state() + "]: " + problem.message();
  }
}
