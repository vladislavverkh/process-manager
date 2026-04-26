package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Версионированное описание сценария процесса. */
public record ProcessDefinition<P>(
    String processType,
    int version,
    int payloadSchemaVersion,
    Class<P> payloadType,
    String initialState,
    ProcessRetention retention,
    Duration processTimeout,
    String processTimeoutTargetState,
    Map<String, StateDefinition<P>> states) {

  public ProcessDefinition {
    if (processType == null || processType.isBlank()) {
      throw new IllegalArgumentException("processType must be set");
    }
    if (version <= 0) {
      throw new IllegalArgumentException("version must be greater than 0");
    }
    if (payloadSchemaVersion <= 0) {
      throw new IllegalArgumentException("payloadSchemaVersion must be greater than 0");
    }
    if (payloadType == null) {
      throw new IllegalArgumentException("payloadType must be set");
    }
    if (initialState == null || initialState.isBlank()) {
      throw new IllegalArgumentException("initialState must be set");
    }
    retention = retention == null ? ProcessRetention.defaults() : retention;
    if (processTimeout != null && (processTimeout.isZero() || processTimeout.isNegative())) {
      throw new IllegalArgumentException("processTimeout must be positive");
    }
    if (processTimeout != null
        && (processTimeoutTargetState == null || processTimeoutTargetState.isBlank())) {
      throw new IllegalArgumentException("processTimeoutTargetState must be set");
    }
    if (processTimeout == null && processTimeoutTargetState != null) {
      throw new IllegalArgumentException("processTimeout must be set");
    }
    states = Map.copyOf(states == null ? Map.of() : states);
    validate(initialState, processTimeoutTargetState, states);
  }

  /** Создает типизированный построитель описания процесса. */
  public static <P> ProcessDefinitionBuilder<P> builder(String processType, Class<P> payloadType) {
    return new ProcessDefinitionBuilder<>(processType, payloadType);
  }

  /** Возвращает описание состояния по имени. */
  public StateDefinition<P> state(String name) {
    StateDefinition<P> state = states.get(name);
    if (state == null) {
      throw new ProcessDefinitionException("Unknown state: " + name);
    }
    return state;
  }

  private static void validate(
      String initialState,
      String processTimeoutTargetState,
      Map<String, ? extends StateDefinition<?>> states) {
    if (!states.containsKey(initialState)) {
      throw new ProcessDefinitionException("Initial state does not exist: " + initialState);
    }
    if (processTimeoutTargetState != null && !states.containsKey(processTimeoutTargetState)) {
      throw new ProcessDefinitionException(
          "Process timeout points to unknown state " + processTimeoutTargetState);
    }
    states.forEach((stateName, state) -> validateStateTransitions(stateName, state, states));
  }

  private static void validateStateTransitions(
      String stateName,
      StateDefinition<?> state,
      Map<String, ? extends StateDefinition<?>> states) {
    if (state.timeoutTargetState() != null && !states.containsKey(state.timeoutTargetState())) {
      throw new ProcessDefinitionException(
          "Timeout from " + stateName + " points to unknown state " + state.timeoutTargetState());
    }
    state
        .transitions()
        .forEach(
            transition -> {
              if (!states.containsKey(transition.targetState())) {
                throw new ProcessDefinitionException(
                    "Transition "
                        + transition.name()
                        + " from "
                        + stateName
                        + " points to unknown state "
                        + transition.targetState());
              }
            });
  }

  static <P> Map<String, StateDefinition<P>> orderedCopy(Map<String, StateDefinition<P>> states) {
    return new LinkedHashMap<>(states);
  }
}
