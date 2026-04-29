package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.List;

/** Описание одного состояния процесса. */
public record StateDefinition<P>(
    String name,
    StateKind kind,
    ProcessAction<P> action,
    String eventType,
    CorrelationKeyResolver<P> correlationKeyResolver,
    Duration waitTimeout,
    Duration stateTimeout,
    String timeoutTargetState,
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
    if (stateTimeout != null && (stateTimeout.isZero() || stateTimeout.isNegative())) {
      throw new IllegalArgumentException("stateTimeout must be positive");
    }
    if (timeoutTargetState != null && timeoutTargetState.isBlank()) {
      throw new IllegalArgumentException("timeoutTargetState must not be blank");
    }
    retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
    transitions = List.copyOf(transitions == null ? List.of() : transitions);
  }

  /** Возвращает true, если это финальное состояние. */
  public boolean terminal() {
    return kind == StateKind.TERMINAL;
  }
}
