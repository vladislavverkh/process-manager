package dev.verkhovskiy.processmanager.testkit;

import dev.verkhovskiy.processmanager.ExternalEvent;
import dev.verkhovskiy.processmanager.ProcessContext;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionException;
import dev.verkhovskiy.processmanager.ProcessDefinitionValidator;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessVariables;
import dev.verkhovskiy.processmanager.StateDefinition;
import dev.verkhovskiy.processmanager.StateKind;
import dev.verkhovskiy.processmanager.StepResult;
import dev.verkhovskiy.processmanager.TransitionContext;
import dev.verkhovskiy.processmanager.TransitionDefinition;
import dev.verkhovskiy.processmanager.TransitionSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic in-memory runner для unit-тестов process definitions без PostgreSQL. */
public final class DeterministicProcessRunner<P> {

  private static final int MAX_STEPS = 100;
  private static final String LAST_TRIGGER_VARIABLE = "_pm.lastTrigger";
  private static final String LAST_ACTION_RESULT_VARIABLE = "_pm.lastActionResult";
  private static final String LAST_EVENT_VARIABLE = "_pm.lastEvent";
  private static final String LAST_RETRY_VARIABLE = "_pm.lastRetry";
  private static final String RETRY_ATTEMPT_VARIABLE_PREFIX = "_pm.retry.";

  private final ProcessDefinition<P> definition;
  private final TransitionSelector transitionSelector = new TransitionSelector();
  private final List<TestProcessHistoryRecord> history = new ArrayList<>();

  private UUID instanceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private String businessKey;
  private String state;
  private ProcessInstanceStatus status;
  private long version;
  private P payload;
  private ProcessVariables variables = ProcessVariables.empty();
  private Instant now = Instant.EPOCH;

  private DeterministicProcessRunner(ProcessDefinition<P> definition) {
    this.definition = definition;
    ProcessDefinitionValidator.validateOrThrow(definition);
  }

  public static <P> DeterministicProcessRunner<P> forDefinition(ProcessDefinition<P> definition) {
    return new DeterministicProcessRunner<>(definition);
  }

  public static <P> DeterministicProcessRunner<P> start(
      ProcessDefinition<P> definition, String businessKey, P payload) {
    return forDefinition(definition).start(businessKey, payload);
  }

  public DeterministicProcessRunner<P> start(String businessKey, P payload) {
    this.instanceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    this.businessKey = businessKey;
    this.state = definition.initialState();
    this.status = statusForTarget(definition.state(state));
    this.version = 0;
    this.payload = payload;
    this.variables = ProcessVariables.empty();
    this.history.clear();
    return this;
  }

  public DeterministicProcessRunner<P> withInstanceId(UUID instanceId) {
    this.instanceId = instanceId;
    return this;
  }

  public DeterministicProcessRunner<P> withVariables(Map<String, Object> variables) {
    this.variables = new ProcessVariables(variables);
    return this;
  }

  public DeterministicProcessRunner<P> setNow(Instant now) {
    this.now = now;
    return this;
  }

  public DeterministicProcessRunner<P> advanceTime(Duration duration) {
    this.now = now.plus(duration);
    return this;
  }

  public DeterministicProcessRunner<P> runUntilBlocked() {
    for (int i = 0; i < MAX_STEPS; i++) {
      StateKind kind = currentStateDefinition().kind();
      if (kind == StateKind.ACTION || kind == StateKind.DECISION) {
        step();
        if (retryScheduled()) {
          return this;
        }
        continue;
      }
      if (kind == StateKind.TERMINAL) {
        enterTerminal();
      }
      return this;
    }
    throw new ProcessDefinitionException("Process test runner exceeded " + MAX_STEPS + " steps");
  }

  public DeterministicProcessRunner<P> step() {
    StateDefinition<P> stateDefinition = currentStateDefinition();
    return switch (stateDefinition.kind()) {
      case ACTION -> executeAction();
      case DECISION -> evaluateDecision();
      case TIMER -> fireTimer();
      case WAIT ->
          throw new IllegalStateException("WAIT state requires signal(...) or timeout(): " + state);
      case TERMINAL -> enterTerminal();
    };
  }

  public DeterministicProcessRunner<P> executeAction() {
    StateDefinition<P> stateDefinition = requireStateKind(StateKind.ACTION);
    StepResult result;
    try {
      result = stateDefinition.action().execute(processContext());
    } catch (Exception e) {
      result = StepResult.retryableFailure(e.getClass().getSimpleName(), e.getMessage());
    }
    if (result == null) {
      throw new ProcessDefinitionException(
          "Action returned null result: " + stateDefinition.name());
    }

    variables = applyActionVariables(result);
    if (result.baseResult() instanceof StepResult.RetryableFailure
        && retryAttempt(stateDefinition) < stateDefinition.retryPolicy().maxAttempts()) {
      scheduleRetry(stateDefinition, result);
      return this;
    }
    if (result.baseResult() instanceof StepResult.RetryableFailure
        && stateDefinition.retryExhaustedTargetState() != null) {
      Map<String, Object> trigger = retryExhaustedTrigger(stateDefinition, result);
      variables =
          variables
              .with(LAST_RETRY_VARIABLE, trigger)
              .with(LAST_TRIGGER_VARIABLE, triggerVariable("RETRY_EXHAUSTED", trigger));
      applyTransition(
          syntheticTransition("retry-exhausted", stateDefinition.retryExhaustedTargetState()),
          "RETRY_EXHAUSTED",
          trigger);
      return this;
    }
    if (!(result.baseResult() instanceof StepResult.RetryableFailure)) {
      variables =
          variables
              .without(retryAttemptVariable(stateDefinition.name()))
              .without(retryMetadataVariable(stateDefinition.name()));
    }
    TransitionDefinition<P> transition =
        transitionSelector.select(stateDefinition, transitionContext(result, null));
    applyTransition(transition, "ACTION_RESULT", actionTrigger(result));
    return this;
  }

  public DeterministicProcessRunner<P> evaluateDecision() {
    StateDefinition<P> stateDefinition = requireStateKind(StateKind.DECISION);
    TransitionDefinition<P> transition =
        transitionSelector.select(stateDefinition, transitionContext(null, null));
    applyTransition(transition, "DECISION", Map.of());
    return this;
  }

  public DeterministicProcessRunner<P> signal(
      String eventType, String correlationKey, Map<String, Object> payload) {
    return signal(eventType, correlationKey, null, payload);
  }

  public DeterministicProcessRunner<P> signal(
      String eventType, String correlationKey, String idempotencyKey, Map<String, Object> payload) {
    StateDefinition<P> stateDefinition = requireStateKind(StateKind.WAIT);
    String expectedCorrelationKey =
        stateDefinition.correlationKeyResolver().resolve(processContext());
    if (!stateDefinition.eventType().equals(eventType)) {
      throw new IllegalArgumentException(
          "Unexpected eventType for state " + state + ": " + eventType);
    }
    if (!expectedCorrelationKey.equals(correlationKey)) {
      throw new IllegalArgumentException(
          "Unexpected correlationKey for state " + state + ": " + correlationKey);
    }

    ExternalEvent event =
        new ExternalEvent(
            eventType, correlationKey, idempotencyKey, payload == null ? Map.of() : payload, now);
    Map<String, Object> trigger = eventTrigger(event);
    variables =
        variables
            .with(LAST_EVENT_VARIABLE, trigger)
            .with(LAST_TRIGGER_VARIABLE, triggerVariable("EVENT", trigger));
    TransitionDefinition<P> transition =
        transitionSelector.select(stateDefinition, transitionContext(null, event));
    applyTransition(transition, "EVENT", trigger);
    return this;
  }

  public DeterministicProcessRunner<P> fireTimer() {
    StateDefinition<P> stateDefinition = requireStateKind(StateKind.TIMER);
    Map<String, Object> trigger = timerTrigger(stateDefinition);
    variables = variables.with(LAST_TRIGGER_VARIABLE, triggerVariable("TIMER", trigger));
    applyTransition(
        syntheticTransition("timer-fired", stateDefinition.timeoutTargetState()), "TIMER", trigger);
    return this;
  }

  public DeterministicProcessRunner<P> timeout() {
    StateDefinition<P> stateDefinition = currentStateDefinition();
    if (stateDefinition.kind() == StateKind.TIMER) {
      return fireTimer();
    }
    Map<String, Object> trigger = stateTimeoutTrigger(stateDefinition);
    if (stateDefinition.timeoutTargetState() != null) {
      variables = variables.with(LAST_TRIGGER_VARIABLE, triggerVariable("STATE_TIMEOUT", trigger));
      applyTransition(
          syntheticTransition("state-timeout", stateDefinition.timeoutTargetState()),
          "STATE_TIMEOUT",
          trigger);
      return this;
    }
    if (stateDefinition.kind() == StateKind.WAIT) {
      variables = variables.with(LAST_TRIGGER_VARIABLE, triggerVariable("STATE_TIMEOUT", trigger));
      TransitionDefinition<P> transition =
          transitionSelector.select(stateDefinition, transitionContext(null, null));
      applyTransition(transition, "STATE_TIMEOUT", trigger);
      return this;
    }
    throw new IllegalStateException("Current state has no timeout transition: " + state);
  }

  public DeterministicProcessRunner<P> processTimeout() {
    if (definition.processTimeoutTargetState() == null) {
      throw new IllegalStateException("Process definition has no process timeout target state");
    }
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("targetState", definition.processTimeoutTargetState());
    trigger.put("triggeredAt", now.toString());
    variables = variables.with(LAST_TRIGGER_VARIABLE, triggerVariable("PROCESS_TIMEOUT", trigger));
    applyTransition(
        syntheticTransition("process-timeout", definition.processTimeoutTargetState()),
        "PROCESS_TIMEOUT",
        trigger);
    return this;
  }

  public UUID instanceId() {
    return instanceId;
  }

  public String businessKey() {
    return businessKey;
  }

  public String state() {
    return state;
  }

  public ProcessInstanceStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  public P payload() {
    return payload;
  }

  public ProcessVariables variables() {
    return variables;
  }

  public List<TestProcessHistoryRecord> history() {
    return List.copyOf(history);
  }

  public TestProcessHistoryRecord lastHistory() {
    if (history.isEmpty()) {
      throw new IllegalStateException("No process history was recorded");
    }
    return history.getLast();
  }

  public Instant now() {
    return now;
  }

  private DeterministicProcessRunner<P> enterTerminal() {
    StateDefinition<P> stateDefinition = requireStateKind(StateKind.TERMINAL);
    status = terminalStatus(stateDefinition);
    return this;
  }

  private boolean retryScheduled() {
    return !history.isEmpty() && "RETRY".equals(history.getLast().triggerType());
  }

  private void scheduleRetry(StateDefinition<P> stateDefinition, StepResult result) {
    int nextAttempt = retryAttempt(stateDefinition) + 1;
    Duration delay = stateDefinition.retryPolicy().delayForAttempt(nextAttempt);
    Map<String, Object> trigger = retryTrigger(stateDefinition, result, nextAttempt, delay);
    variables =
        variables
            .with(retryAttemptVariable(stateDefinition.name()), nextAttempt)
            .with(retryMetadataVariable(stateDefinition.name()), trigger)
            .with(LAST_RETRY_VARIABLE, trigger)
            .with(LAST_TRIGGER_VARIABLE, triggerVariable("RETRY", trigger));
    history.add(new TestProcessHistoryRecord(state, state, "retry", "RETRY", trigger, now));
    version++;
  }

  private void applyTransition(
      TransitionDefinition<P> transition, String triggerType, Map<String, Object> trigger) {
    String fromState = state;
    StateDefinition<P> targetState = definition.state(transition.targetState());
    state = targetState.name();
    status = statusForTarget(targetState);
    version++;
    history.add(
        new TestProcessHistoryRecord(
            fromState, targetState.name(), transition.name(), triggerType, trigger, now));
  }

  private ProcessContext<P> processContext() {
    return new ProcessContext<>(
        instanceId,
        definition.processType(),
        definition.version(),
        state,
        businessKey,
        payload,
        variables,
        now);
  }

  private TransitionContext<P> transitionContext(StepResult result, ExternalEvent event) {
    return new TransitionContext<>(
        instanceId,
        definition.processType(),
        definition.version(),
        state,
        businessKey,
        payload,
        variables,
        result,
        event,
        now);
  }

  private StateDefinition<P> currentStateDefinition() {
    return definition.state(state);
  }

  private StateDefinition<P> requireStateKind(StateKind expectedKind) {
    StateDefinition<P> stateDefinition = currentStateDefinition();
    if (stateDefinition.kind() != expectedKind) {
      throw new IllegalStateException(
          "Expected " + expectedKind + " state, but current state is " + stateDefinition.kind());
    }
    return stateDefinition;
  }

  private ProcessVariables applyActionVariables(StepResult result) {
    Map<String, Object> actionTrigger = actionTrigger(result);
    return variables
        .withAll(actionData(result))
        .withAll(result.variableUpdates())
        .with(LAST_ACTION_RESULT_VARIABLE, actionTrigger)
        .with(LAST_TRIGGER_VARIABLE, triggerVariable("ACTION_RESULT", actionTrigger));
  }

  private int retryAttempt(StateDefinition<P> stateDefinition) {
    return variables.integer(retryAttemptVariable(stateDefinition.name())).orElse(0);
  }

  private static String retryAttemptVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state + ".attempt";
  }

  private static String retryMetadataVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state;
  }

  private static <P> TransitionDefinition<P> syntheticTransition(String name, String targetState) {
    return new TransitionDefinition<>(name, targetState, Integer.MIN_VALUE, context -> true);
  }

  private static ProcessInstanceStatus statusForTarget(StateDefinition<?> stateDefinition) {
    return switch (stateDefinition.kind()) {
      case ACTION, DECISION -> ProcessInstanceStatus.RUNNING;
      case WAIT, TIMER -> ProcessInstanceStatus.WAITING;
      case TERMINAL -> terminalStatus(stateDefinition);
    };
  }

  private static ProcessInstanceStatus terminalStatus(StateDefinition<?> stateDefinition) {
    if (stateDefinition.terminalStatus() == null) {
      throw new ProcessDefinitionException(
          "Terminal state must define terminal status: " + stateDefinition.name());
    }
    return stateDefinition.terminalStatus();
  }

  private static Map<String, Object> actionTrigger(StepResult result) {
    return switch (result.baseResult()) {
      case StepResult.Success success ->
          Map.of("kind", "SUCCESS", "code", success.code(), "data", success.data());
      case StepResult.BusinessFailure failure ->
          Map.of("kind", "BUSINESS_FAILURE", "code", failure.code(), "data", failure.data());
      case StepResult.RetryableFailure failure ->
          Map.of(
              "kind",
              "RETRYABLE_FAILURE",
              "code",
              failure.code(),
              "message",
              nullToEmpty(failure.message()));
      case StepResult.FatalFailure failure ->
          Map.of(
              "kind",
              "FATAL_FAILURE",
              "code",
              failure.code(),
              "message",
              nullToEmpty(failure.message()));
      case StepResult.WithVariables withVariables -> actionTrigger(withVariables.delegate());
    };
  }

  private static Map<String, Object> actionData(StepResult result) {
    return switch (result.baseResult()) {
      case StepResult.Success success -> success.data();
      case StepResult.BusinessFailure failure -> failure.data();
      case StepResult.RetryableFailure failure -> Map.of();
      case StepResult.FatalFailure failure -> Map.of();
      case StepResult.WithVariables withVariables -> actionData(withVariables.delegate());
    };
  }

  private static Map<String, Object> eventTrigger(ExternalEvent event) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("eventType", event.eventType());
    trigger.put("correlationKey", event.correlationKey());
    if (event.idempotencyKey() != null) {
      trigger.put("idempotencyKey", event.idempotencyKey());
    }
    trigger.put("payload", event.payload());
    trigger.put("receivedAt", event.receivedAt().toString());
    return Map.copyOf(trigger);
  }

  private Map<String, Object> stateTimeoutTrigger(StateDefinition<?> stateDefinition) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("state", stateDefinition.name());
    if (stateDefinition.eventType() != null) {
      trigger.put("eventType", stateDefinition.eventType());
    }
    if (stateDefinition.timeoutTargetState() != null) {
      trigger.put("targetState", stateDefinition.timeoutTargetState());
    }
    trigger.put("triggeredAt", now.toString());
    return Map.copyOf(trigger);
  }

  private Map<String, Object> timerTrigger(StateDefinition<?> stateDefinition) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("state", stateDefinition.name());
    trigger.put("targetState", stateDefinition.timeoutTargetState());
    trigger.put("delay", stateDefinition.stateTimeout().toString());
    trigger.put("delayMillis", stateDefinition.stateTimeout().toMillis());
    trigger.put("triggeredAt", now.toString());
    return Map.copyOf(trigger);
  }

  private static Map<String, Object> retryTrigger(
      StateDefinition<?> stateDefinition, StepResult result, int nextAttempt, Duration delay) {
    Map<String, Object> retry = new LinkedHashMap<>();
    retry.put("state", stateDefinition.name());
    retry.put("attempt", nextAttempt);
    retry.put("maxAttempts", stateDefinition.retryPolicy().maxAttempts());
    retry.put("delay", delay.toString());
    retry.put("delayMillis", delay.toMillis());
    retry.put("failure", actionTrigger(result));
    return Map.copyOf(retry);
  }

  private Map<String, Object> retryExhaustedTrigger(
      StateDefinition<?> stateDefinition, StepResult result) {
    Map<String, Object> retry = new LinkedHashMap<>();
    retry.put("state", stateDefinition.name());
    retry.put("attempt", retryAttempt(currentStateDefinition()));
    retry.put("maxAttempts", stateDefinition.retryPolicy().maxAttempts());
    retry.put("targetState", stateDefinition.retryExhaustedTargetState());
    retry.put("failure", actionTrigger(result));
    return Map.copyOf(retry);
  }

  private static Map<String, Object> triggerVariable(
      String triggerType, Map<String, Object> trigger) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("type", triggerType);
    value.putAll(trigger == null ? Map.of() : trigger);
    return Map.copyOf(value);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
