package dev.verkhovskiy.processmanager;

/** Conditional transition from one process state to another. */
public record TransitionDefinition<P>(
    String name, String targetState, int priority, TransitionCondition<P> condition) {

  public TransitionDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("transition name must be set");
    }
    if (targetState == null || targetState.isBlank()) {
      throw new IllegalArgumentException("targetState must be set");
    }
    if (condition == null) {
      throw new IllegalArgumentException("condition must be set");
    }
  }

  /** Transition that always matches. */
  public static <P> TransitionDefinition<P> always(String name, String targetState) {
    return new TransitionDefinition<>(name, targetState, Integer.MAX_VALUE, context -> true);
  }
}
