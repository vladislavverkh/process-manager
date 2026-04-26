package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Fluent builder for process definitions with conditional transitions. */
public final class ProcessDefinitionBuilder<P> {

  private final String processType;
  private final Class<P> payloadType;
  private final Map<String, StateDefinition<P>> states = new LinkedHashMap<>();
  private int version = 1;
  private int payloadSchemaVersion = 1;
  private String initialState;
  private ProcessRetention retention = ProcessRetention.defaults();

  ProcessDefinitionBuilder(String processType, Class<P> payloadType) {
    this.processType = processType;
    this.payloadType = payloadType;
  }

  public ProcessDefinitionBuilder<P> version(int version) {
    this.version = version;
    return this;
  }

  public ProcessDefinitionBuilder<P> payloadSchemaVersion(int payloadSchemaVersion) {
    this.payloadSchemaVersion = payloadSchemaVersion;
    return this;
  }

  public ProcessDefinitionBuilder<P> initialState(String initialState) {
    this.initialState = initialState;
    return this;
  }

  public ProcessDefinitionBuilder<P> retention(ProcessRetention retention) {
    this.retention = retention;
    return this;
  }

  public ProcessDefinitionBuilder<P> actionState(
      String name, ProcessAction<P> action, Consumer<StateBuilder<P>> transitions) {
    StateBuilder<P> builder = new StateBuilder<P>(name, StateKind.ACTION).action(action);
    transitions.accept(builder);
    add(builder.build());
    return this;
  }

  public ProcessDefinitionBuilder<P> waitState(
      String name,
      String eventType,
      CorrelationKeyResolver<P> correlationKeyResolver,
      Duration timeout,
      Consumer<StateBuilder<P>> transitions) {
    StateBuilder<P> builder =
        new StateBuilder<P>(name, StateKind.WAIT)
            .eventType(eventType)
            .correlationKeyResolver(correlationKeyResolver)
            .waitTimeout(timeout);
    transitions.accept(builder);
    add(builder.build());
    return this;
  }

  public ProcessDefinitionBuilder<P> decisionState(
      String name, Consumer<StateBuilder<P>> transitions) {
    StateBuilder<P> builder = new StateBuilder<>(name, StateKind.DECISION);
    transitions.accept(builder);
    add(builder.build());
    return this;
  }

  public ProcessDefinitionBuilder<P> terminalState(
      String name, ProcessInstanceStatus terminalStatus) {
    add(new StateBuilder<P>(name, StateKind.TERMINAL).terminalStatus(terminalStatus).build());
    return this;
  }

  public ProcessDefinition<P> build() {
    return new ProcessDefinition<>(
        processType,
        version,
        payloadSchemaVersion,
        payloadType,
        initialState,
        retention,
        ProcessDefinition.orderedCopy(states));
  }

  private void add(StateDefinition<P> state) {
    if (states.putIfAbsent(state.name(), state) != null) {
      throw new ProcessDefinitionException("Duplicate state: " + state.name());
    }
  }

  /** Builder for state transitions and retry policy. */
  public static final class StateBuilder<P> {

    private final String name;
    private final StateKind kind;
    private final List<TransitionDefinition<P>> transitions = new ArrayList<>();
    private ProcessAction<P> action;
    private String eventType;
    private CorrelationKeyResolver<P> correlationKeyResolver;
    private Duration waitTimeout;
    private RetryPolicy retryPolicy = RetryPolicy.none();
    private ProcessInstanceStatus terminalStatus;

    private StateBuilder(String name, StateKind kind) {
      this.name = name;
      this.kind = kind;
    }

    public StateBuilder<P> retry(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    public StateBuilder<P> transition(
        String name, String targetState, TransitionCondition<P> condition) {
      return transition(name, targetState, transitions.size(), condition);
    }

    public StateBuilder<P> transition(
        String name, String targetState, int priority, TransitionCondition<P> condition) {
      transitions.add(new TransitionDefinition<>(name, targetState, priority, condition));
      return this;
    }

    public StateBuilder<P> otherwise(String targetState) {
      transitions.add(TransitionDefinition.always("otherwise", targetState));
      return this;
    }

    private StateBuilder<P> action(ProcessAction<P> action) {
      this.action = action;
      return this;
    }

    private StateBuilder<P> eventType(String eventType) {
      this.eventType = eventType;
      return this;
    }

    private StateBuilder<P> correlationKeyResolver(
        CorrelationKeyResolver<P> correlationKeyResolver) {
      this.correlationKeyResolver = correlationKeyResolver;
      return this;
    }

    private StateBuilder<P> waitTimeout(Duration waitTimeout) {
      this.waitTimeout = waitTimeout;
      return this;
    }

    private StateBuilder<P> terminalStatus(ProcessInstanceStatus terminalStatus) {
      this.terminalStatus = terminalStatus;
      return this;
    }

    private StateDefinition<P> build() {
      return new StateDefinition<>(
          name,
          kind,
          action,
          eventType,
          correlationKeyResolver,
          waitTimeout,
          retryPolicy,
          terminalStatus,
          transitions);
    }
  }
}
