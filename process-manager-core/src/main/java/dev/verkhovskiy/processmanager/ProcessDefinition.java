package dev.verkhovskiy.processmanager;

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
    states = Map.copyOf(states == null ? Map.of() : states);
    validate(initialState, states);
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
      String initialState, Map<String, ? extends StateDefinition<?>> states) {
    if (!states.containsKey(initialState)) {
      throw new ProcessDefinitionException("Initial state does not exist: " + initialState);
    }
    states.forEach(
        (stateName, state) ->
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
                    }));
  }

  static <P> Map<String, StateDefinition<P>> orderedCopy(Map<String, StateDefinition<P>> states) {
    return new LinkedHashMap<>(states);
  }
}
