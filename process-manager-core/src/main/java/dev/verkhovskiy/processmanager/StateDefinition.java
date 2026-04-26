package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.List;

/** Definition of a single process state. */
public record StateDefinition<P>(
    String name,
    StateKind kind,
    ProcessAction<P> action,
    String eventType,
    CorrelationKeyResolver<P> correlationKeyResolver,
    Duration waitTimeout,
    RetryPolicy retryPolicy,
    ProcessInstanceStatus terminalStatus,
    List<TransitionDefinition<P>> transitions) {

  public StateDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("state name must be set");
    }
    if (kind == null) {
      throw new IllegalArgumentException("state kind must be set");
    }
    retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
    transitions = List.copyOf(transitions == null ? List.of() : transitions);
  }

  /** Returns true when this state is terminal. */
  public boolean terminal() {
    return kind == StateKind.TERMINAL;
  }
}
