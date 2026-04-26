package dev.verkhovskiy.processmanager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Цепочный построитель описания процесса с условными переходами. */
public final class ProcessDefinitionBuilder<P> {

  private final String processType;
  private final Class<P> payloadType;
  private final Map<String, StateDefinition<P>> states = new LinkedHashMap<>();
  private int version = 1;
  private int payloadSchemaVersion = 1;
  private String initialState;
  private ProcessRetention retention = ProcessRetention.defaults();
  private Duration processTimeout;
  private String processTimeoutTargetState;

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

  /**
   * @deprecated Для новых definitions используйте {@link #processTimeout(Consumer)}, чтобы явно
   *     назвать duration и targetState.
   */
  @Deprecated(since = "0.0.1", forRemoval = false)
  public ProcessDefinitionBuilder<P> processTimeout(Duration timeout, String targetState) {
    this.processTimeout = timeout;
    this.processTimeoutTargetState = targetState;
    return this;
  }

  public ProcessDefinitionBuilder<P> processTimeout(Consumer<TimeoutBuilder> timeout) {
    TimeoutBuilder builder = new TimeoutBuilder();
    timeout.accept(builder);
    this.processTimeout = builder.duration;
    this.processTimeoutTargetState = builder.targetState;
    return this;
  }

  /**
   * @deprecated Для новых definitions используйте {@link #actionState(String, Consumer)} и
   *     задавайте action через {@link StateBuilder#action(ProcessAction)}.
   */
  @Deprecated(since = "0.0.1", forRemoval = false)
  public ProcessDefinitionBuilder<P> actionState(
      String name, ProcessAction<P> action, Consumer<StateBuilder<P>> transitions) {
    StateBuilder<P> builder = new StateBuilder<P>(name, StateKind.ACTION).action(action);
    transitions.accept(builder);
    add(builder.build());
    return this;
  }

  public ProcessDefinitionBuilder<P> actionState(
      String name, Consumer<StateBuilder<P>> definition) {
    StateBuilder<P> builder = new StateBuilder<>(name, StateKind.ACTION);
    definition.accept(builder);
    add(builder.build());
    return this;
  }

  /**
   * @deprecated Для новых definitions используйте {@link #waitState(String, Consumer)} и задавайте
   *     eventType, correlationKey и waitTimeout именованными методами.
   */
  @Deprecated(since = "0.0.1", forRemoval = false)
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

  public ProcessDefinitionBuilder<P> waitState(String name, Consumer<StateBuilder<P>> definition) {
    StateBuilder<P> builder = new StateBuilder<>(name, StateKind.WAIT);
    definition.accept(builder);
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
        processTimeout,
        processTimeoutTargetState,
        ProcessDefinition.orderedCopy(states));
  }

  private void add(StateDefinition<P> state) {
    if (states.putIfAbsent(state.name(), state) != null) {
      throw new ProcessDefinitionException("Duplicate state: " + state.name());
    }
  }

  /** Построитель переходов состояния и политики повторов. */
  public static final class StateBuilder<P> {

    private final String name;
    private final StateKind kind;
    private final List<TransitionDefinition<P>> transitions = new ArrayList<>();
    private ProcessAction<P> action;
    private String eventType;
    private CorrelationKeyResolver<P> correlationKeyResolver;
    private Duration waitTimeout;
    private Duration stateTimeout;
    private String timeoutTargetState;
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

    public StateBuilder<P> action(ProcessAction<P> action) {
      this.action = action;
      return this;
    }

    public StateBuilder<P> eventType(String eventType) {
      this.eventType = eventType;
      return this;
    }

    public StateBuilder<P> correlationKey(CorrelationKeyResolver<P> correlationKeyResolver) {
      this.correlationKeyResolver = correlationKeyResolver;
      return this;
    }

    public StateBuilder<P> waitTimeout(Duration waitTimeout) {
      this.waitTimeout = waitTimeout;
      return this;
    }

    /**
     * @deprecated Для новых definitions используйте {@link #timeout(Consumer)}, чтобы явно назвать
     *     duration и targetState.
     */
    @Deprecated(since = "0.0.1", forRemoval = false)
    public StateBuilder<P> timeout(Duration timeout, String targetState) {
      this.stateTimeout = timeout;
      this.timeoutTargetState = targetState;
      return this;
    }

    public StateBuilder<P> timeout(Consumer<TimeoutBuilder> timeout) {
      TimeoutBuilder builder = new TimeoutBuilder();
      timeout.accept(builder);
      this.stateTimeout = builder.duration;
      this.timeoutTargetState = builder.targetState;
      return this;
    }

    public StateBuilder<P> timeoutTransition(String targetState) {
      this.timeoutTargetState = targetState;
      return this;
    }

    /**
     * @deprecated Для новых definitions используйте {@link #transition(Consumer)}, чтобы явно
     *     назвать name, targetState и condition.
     */
    @Deprecated(since = "0.0.1", forRemoval = false)
    public StateBuilder<P> transition(
        String name, String targetState, TransitionCondition<P> condition) {
      return transition(name, targetState, transitions.size(), condition);
    }

    public StateBuilder<P> transition(Consumer<TransitionBuilder<P>> transition) {
      TransitionBuilder<P> builder = new TransitionBuilder<>(transitions.size());
      transition.accept(builder);
      transitions.add(builder.build());
      return this;
    }

    /**
     * @deprecated Для новых definitions используйте {@link #transition(Consumer)}, чтобы явно
     *     назвать name, targetState, priority и condition.
     */
    @Deprecated(since = "0.0.1", forRemoval = false)
    public StateBuilder<P> transition(
        String name, String targetState, int priority, TransitionCondition<P> condition) {
      transitions.add(new TransitionDefinition<>(name, targetState, priority, condition));
      return this;
    }

    public StateBuilder<P> otherwise(String targetState) {
      transitions.add(TransitionDefinition.always("otherwise", targetState));
      return this;
    }

    private StateBuilder<P> correlationKeyResolver(
        CorrelationKeyResolver<P> correlationKeyResolver) {
      this.correlationKeyResolver = correlationKeyResolver;
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
          stateTimeout,
          timeoutTargetState,
          retryPolicy,
          terminalStatus,
          transitions);
    }
  }

  /** Построитель timeout policy с явными именами полей. */
  public static final class TimeoutBuilder {

    private Duration duration;
    private String targetState;

    public TimeoutBuilder duration(Duration duration) {
      this.duration = duration;
      return this;
    }

    public TimeoutBuilder targetState(String targetState) {
      this.targetState = targetState;
      return this;
    }
  }

  /** Построитель transition с явными именами полей. */
  public static final class TransitionBuilder<P> {

    private String name;
    private String targetState;
    private int priority;
    private TransitionCondition<P> condition;

    private TransitionBuilder(int priority) {
      this.priority = priority;
    }

    public TransitionBuilder<P> name(String name) {
      this.name = name;
      return this;
    }

    public TransitionBuilder<P> targetState(String targetState) {
      this.targetState = targetState;
      return this;
    }

    public TransitionBuilder<P> priority(int priority) {
      this.priority = priority;
      return this;
    }

    public TransitionBuilder<P> condition(TransitionCondition<P> condition) {
      this.condition = condition;
      return this;
    }

    private TransitionDefinition<P> build() {
      return new TransitionDefinition<>(name, targetState, priority, condition);
    }
  }
}
