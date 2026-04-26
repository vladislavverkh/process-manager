package dev.verkhovskiy.processmanager.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ExternalEvent;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessContext;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionException;
import dev.verkhovskiy.processmanager.ProcessDefinitionRegistry;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.processmanager.ProcessVariables;
import dev.verkhovskiy.processmanager.StateDefinition;
import dev.verkhovskiy.processmanager.StateKind;
import dev.verkhovskiy.processmanager.StepResult;
import dev.verkhovskiy.processmanager.TransitionContext;
import dev.verkhovskiy.processmanager.TransitionDefinition;
import dev.verkhovskiy.processmanager.TransitionSelector;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.ProcessHistoryRecord;
import dev.verkhovskiy.processmanager.postgres.StoredProcessEvent;
import dev.verkhovskiy.processmanager.postgres.StoredProcessInstance;
import dev.verkhovskiy.processmanager.postgres.StoredProcessWait;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Начальная реализация менеджера процессов поверх PostgreSQL. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Зависимости являются внедренными инфраструктурными Spring-бинами.")
public class PostgresProcessManager implements ProcessManager {

  private static final int MAX_STEPS_PER_RESUME = 100;
  private static final String LAST_TRIGGER_VARIABLE = "_pm.lastTrigger";
  private static final String LAST_ACTION_RESULT_VARIABLE = "_pm.lastActionResult";
  private static final String LAST_EVENT_VARIABLE = "_pm.lastEvent";
  private static final String LAST_RETRY_VARIABLE = "_pm.lastRetry";
  private static final String RETRY_ATTEMPT_VARIABLE_PREFIX = "_pm.retry.";
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ProcessDefinitionRegistry definitionRegistry;
  private final PostgresProcessRepository processRepository;
  private final ProcessCommandScheduler commandScheduler;
  private final ObjectMapper objectMapper;
  private final TransitionSelector transitionSelector = new TransitionSelector();

  public PostgresProcessManager(
      ProcessDefinitionRegistry definitionRegistry,
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ObjectMapper objectMapper) {
    this.definitionRegistry = definitionRegistry;
    this.processRepository = processRepository;
    this.commandScheduler = commandScheduler;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public UUID start(String processType, String businessKey, Object payload) {
    ProcessDefinition<?> definition = definitionRegistry.latest(processType);
    StateDefinition<?> initialState = definition.state(definition.initialState());
    UUID instanceId = UUID.randomUUID();
    Instant now = Instant.now();
    StoredProcessInstance newInstance =
        new StoredProcessInstance(
            instanceId,
            definition.processType(),
            definition.version(),
            definition.payloadSchemaVersion(),
            businessKey,
            definition.initialState(),
            ProcessInstanceStatus.RUNNING,
            toJson(payload),
            "{}",
            now,
            now,
            deadlineAt(now, definition.processTimeout()),
            now,
            stateDeadlineAt(initialState, now),
            null,
            null,
            0);
    if (!processRepository.insertInstanceIfActiveAbsent(newInstance)) {
      return processRepository
          .findActiveInstance(definition.processType(), businessKey)
          .map(StoredProcessInstance::instanceId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Active process instance was not found after idempotent start conflict"));
    }
    commandScheduler.schedule(
        new ProcessCommand(instanceId, ProcessCommandReason.START, 0),
        partitionKey(processType, businessKey));
    return instanceId;
  }

  @Override
  @Transactional
  public void signal(String eventType, String correlationKey, Map<String, Object> payload) {
    signal(eventType, correlationKey, null, payload);
  }

  @Override
  @Transactional
  public void signal(
      String eventType, String correlationKey, String idempotencyKey, Map<String, Object> payload) {
    UUID eventId = UUID.randomUUID();
    boolean inserted =
        processRepository.insertEvent(
            eventId,
            eventType,
            correlationKey,
            normalizeIdempotencyKey(idempotencyKey),
            toJson(payload));
    if (!inserted) {
      return;
    }
    for (StoredProcessWait wait : processRepository.findWaits(eventType, correlationKey)) {
      commandScheduler.schedule(
          new ProcessCommand(wait.instanceId(), ProcessCommandReason.RESUME, -1),
          partitionKey(wait.processType(), wait.instanceId().toString()));
    }
  }

  @Override
  @Transactional
  public void resume(UUID instanceId) {
    resume(new ProcessCommand(instanceId, ProcessCommandReason.RESUME, -1));
  }

  @Override
  @Transactional
  public void resume(ProcessCommand command) {
    StoredProcessInstance instance =
        processRepository
            .findInstanceForUpdate(command.instanceId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Process instance not found: " + command.instanceId()));
    if (command.expectedVersion() >= 0 && instance.version() != command.expectedVersion()) {
      return;
    }
    ProcessDefinition<?> definition =
        definitionRegistry.get(instance.processType(), instance.definitionVersion());
    execute(command, instance, typed(definition));
  }

  private <P> void execute(
      ProcessCommand command, StoredProcessInstance instance, ProcessDefinition<P> definition) {
    P payload = fromJson(instance.payloadJson(), definition.payloadType(), "process payload");
    ExecutionState<P> state =
        new ExecutionState<>(
            instance.instanceId(),
            instance.processType(),
            instance.definitionVersion(),
            instance.businessKey(),
            instance.state(),
            instance.status(),
            instance.version(),
            payload,
            new ProcessVariables(readMap(instance.variablesJson(), "process variables")),
            instance.processDeadlineAt(),
            instance.stateEnteredAt(),
            instance.stateDeadlineAt());

    boolean timeoutHandled = false;
    for (int step = 0; step < MAX_STEPS_PER_RESUME; step++) {
      StateDefinition<P> stateDefinition = definition.state(state.state());
      Instant now = Instant.now();
      if (!timeoutHandled
          && (processTimeoutCommand(command) || deadlineExpired(state.processDeadlineAt(), now))) {
        state = handleProcessTimeout(definition, state, stateDefinition, now);
        timeoutHandled = true;
        if (state.parked()) {
          return;
        }
        continue;
      }
      if (!timeoutHandled
          && (stateTimeoutCommand(command) || deadlineExpired(state.stateDeadlineAt(), now))) {
        state = handleStateTimeout(definition, state, stateDefinition, now);
        timeoutHandled = true;
        if (state.parked()) {
          return;
        }
        continue;
      }
      state =
          switch (stateDefinition.kind()) {
            case ACTION -> executeAction(definition, state, stateDefinition);
            case DECISION -> executeDecision(definition, state, stateDefinition);
            case WAIT -> executeWait(definition, state, stateDefinition);
            case TERMINAL -> enterTerminal(definition, state, stateDefinition);
          };
      if (state.parked()) {
        return;
      }
    }
    throw new ProcessDefinitionException(
        "Process execution exceeded "
            + MAX_STEPS_PER_RESUME
            + " steps for instance "
            + instance.instanceId());
  }

  private <P> ExecutionState<P> executeAction(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition) {
    Instant now = Instant.now();
    ProcessContext<P> context = processContext(state, now);
    StepResult result;
    try {
      result = stateDefinition.action().execute(context);
    } catch (Exception e) {
      result = StepResult.retryableFailure(e.getClass().getSimpleName(), e.getMessage());
    }
    if (result == null) {
      throw new ProcessDefinitionException(
          "Action returned null result: " + stateDefinition.name());
    }

    ProcessVariables variables = applyActionVariables(state, result);
    ExecutionState<P> actionState = state.withVariables(variables);

    if (result.baseResult() instanceof StepResult.RetryableFailure
        && canRetry(state, stateDefinition)) {
      return scheduleRetry(definition, actionState, stateDefinition, result, now);
    }

    if (!(result.baseResult() instanceof StepResult.RetryableFailure)) {
      variables =
          variables
              .without(retryAttemptVariable(state.state()))
              .without(retryMetadataVariable(state.state()));
      actionState = state.withVariables(variables);
    }
    TransitionDefinition<P> transition =
        transitionSelector.select(
            stateDefinition, transitionContext(actionState, result, null, now));
    return applyTransition(
        definition,
        actionState,
        stateDefinition,
        transition,
        "ACTION_RESULT",
        actionTrigger(result),
        now);
  }

  private <P> ExecutionState<P> executeDecision(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition) {
    Instant now = Instant.now();
    TransitionDefinition<P> transition =
        transitionSelector.select(stateDefinition, transitionContext(state, null, null, now));
    return applyTransition(
        definition, state, stateDefinition, transition, "DECISION", Map.of(), now);
  }

  private <P> ExecutionState<P> executeWait(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition) {
    if (state.status() != ProcessInstanceStatus.WAITING) {
      return parkInWait(state, stateDefinition, "START", Map.of(), Instant.now());
    }

    Instant now = Instant.now();
    String correlationKey =
        stateDefinition.correlationKeyResolver().resolve(processContext(state, now));
    Optional<StoredProcessEvent> event =
        processRepository.findUnconsumedEventForUpdate(stateDefinition.eventType(), correlationKey);
    if (event.isEmpty()) {
      return state.park();
    }

    ExternalEvent externalEvent =
        new ExternalEvent(
            event.get().eventType(),
            event.get().correlationKey(),
            event.get().idempotencyKey(),
            readMap(event.get().payloadJson(), "event payload"),
            event.get().receivedAt());
    ExecutionState<P> updated =
        advanceFromWait(
            definition,
            state,
            stateDefinition,
            externalEvent,
            "EVENT",
            eventTrigger(externalEvent),
            Instant.now());
    if (updated.version() != state.version()) {
      processRepository.markEventConsumed(event.get().eventId());
    }
    return updated;
  }

  private <P> ExecutionState<P> advanceFromWait(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      ExternalEvent event,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    ProcessVariables variables =
        state.variables().with(LAST_TRIGGER_VARIABLE, triggerVariable(triggerType, trigger));
    if (event != null) {
      variables = variables.with(LAST_EVENT_VARIABLE, trigger);
    }
    ExecutionState<P> triggeredState = state.withVariables(variables);
    TransitionDefinition<P> transition =
        transitionSelector.select(
            stateDefinition, transitionContext(triggeredState, null, event, now));
    return applyTransition(
        definition, triggeredState, stateDefinition, transition, triggerType, trigger, now);
  }

  private <P> ExecutionState<P> handleProcessTimeout(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      Instant now) {
    if (state.processDeadlineAt() == null || state.processDeadlineAt().isAfter(now)) {
      return state.park();
    }
    if (definition.processTimeoutTargetState() == null) {
      return state.park();
    }
    Map<String, Object> trigger =
        processTimeoutTrigger(
            definition.processTimeoutTargetState(), state.processDeadlineAt(), now);
    return applyTransition(
        definition,
        state.withVariables(
            state
                .variables()
                .with(LAST_TRIGGER_VARIABLE, triggerVariable("PROCESS_TIMEOUT", trigger))),
        stateDefinition,
        timeoutTransition("process-timeout", definition.processTimeoutTargetState()),
        "PROCESS_TIMEOUT",
        trigger,
        now);
  }

  private <P> ExecutionState<P> handleStateTimeout(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      Instant now) {
    if (state.stateDeadlineAt() == null || state.stateDeadlineAt().isAfter(now)) {
      return state.park();
    }
    Map<String, Object> trigger =
        stateTimeoutTrigger(stateDefinition, state.stateDeadlineAt(), now);
    if (stateDefinition.timeoutTargetState() != null) {
      return applyTransition(
          definition,
          state.withVariables(
              state
                  .variables()
                  .with(LAST_TRIGGER_VARIABLE, triggerVariable("STATE_TIMEOUT", trigger))),
          stateDefinition,
          timeoutTransition("state-timeout", stateDefinition.timeoutTargetState()),
          "STATE_TIMEOUT",
          trigger,
          now);
    }
    if (stateDefinition.kind() == StateKind.WAIT) {
      return advanceFromWait(
          definition, state, stateDefinition, null, "STATE_TIMEOUT", trigger, now);
    }
    return state.park();
  }

  private <P> ExecutionState<P> enterTerminal(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition) {
    if (state.status() == stateDefinition.terminalStatus()) {
      return state.park();
    }
    Instant now = Instant.now();
    ProcessInstanceStatus terminalStatus = terminalStatus(stateDefinition);
    ProcessVariables variables =
        state.variables().with(LAST_TRIGGER_VARIABLE, triggerVariable("START", Map.of()));
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            stateDefinition.name(),
            terminalStatus,
            toJson(variables.values()),
            now,
            null,
            now,
            deleteAfter(definition, terminalStatus, now));
    if (updated == 0) {
      return state.park();
    }
    insertHistory(state, null, stateDefinition.name(), "START", "START", Map.of(), now);
    return state
        .withVariables(variables)
        .withState(stateDefinition.name(), terminalStatus, state.version() + 1, now, null)
        .park();
  }

  private <P> ExecutionState<P> applyTransition(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> fromState,
      TransitionDefinition<P> transition,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    StateDefinition<P> targetState = definition.state(transition.targetState());
    ProcessInstanceStatus status = statusForTarget(targetState);
    Instant stateEnteredAt = now;
    Instant stateDeadlineAt = stateDeadlineAt(targetState, stateEnteredAt);
    Instant completedAt = targetState.terminal() ? now : null;
    Instant deleteAfter = targetState.terminal() ? deleteAfter(definition, status, now) : null;
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            targetState.name(),
            status,
            toJson(state.variables().values()),
            stateEnteredAt,
            stateDeadlineAt,
            completedAt,
            deleteAfter);
    if (updated == 0) {
      return state.park();
    }
    if (fromState.kind() == StateKind.WAIT) {
      processRepository.deleteWaits(state.instanceId());
    }
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            fromState.name(),
            targetState.name(),
            transition.name(),
            triggerType,
            toJson(trigger),
            now));

    ExecutionState<P> updatedState =
        state.withState(
            targetState.name(), status, state.version() + 1, stateEnteredAt, stateDeadlineAt);
    if (targetState.kind() == StateKind.WAIT) {
      return registerWait(updatedState, targetState, now).park();
    }
    if (targetState.kind() == StateKind.TERMINAL) {
      return updatedState.park();
    }
    return updatedState;
  }

  private <P> ExecutionState<P> parkInWait(
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    ProcessVariables variables =
        state.variables().with(LAST_TRIGGER_VARIABLE, triggerVariable(triggerType, trigger));
    ExecutionState<P> stateWithTrigger = state.withVariables(variables);
    int updated =
        processRepository.updateExecutionState(
            stateWithTrigger.instanceId(),
            stateWithTrigger.version(),
            stateDefinition.name(),
            ProcessInstanceStatus.WAITING,
            toJson(stateWithTrigger.variables().values()),
            now,
            stateDeadlineAt(stateDefinition, now),
            null,
            null);
    if (updated == 0) {
      return state.park();
    }
    insertHistory(
        stateWithTrigger,
        null,
        stateDefinition.name(),
        stateDefinition.name(),
        triggerType,
        trigger,
        now);
    return registerWait(
            stateWithTrigger.withState(
                stateDefinition.name(),
                ProcessInstanceStatus.WAITING,
                state.version() + 1,
                now,
                stateDeadlineAt(stateDefinition, now)),
            stateDefinition,
            now)
        .park();
  }

  private <P> ExecutionState<P> registerWait(
      ExecutionState<P> state, StateDefinition<P> stateDefinition, Instant now) {
    String correlationKey =
        stateDefinition.correlationKeyResolver().resolve(processContext(state, now));
    Instant expiresAt =
        stateDefinition.waitTimeout() == null ? null : now.plus(stateDefinition.waitTimeout());
    processRepository.upsertWait(
        new StoredProcessWait(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            stateDefinition.name(),
            stateDefinition.eventType(),
            correlationKey,
            expiresAt,
            now));
    return state;
  }

  private <P> ExecutionState<P> scheduleRetry(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      StepResult result,
      Instant now) {
    int nextAttempt = retryAttempt(state, stateDefinition) + 1;
    Duration delay = stateDefinition.retryPolicy().delayForAttempt(nextAttempt);
    Map<String, Object> retryMetadata = retryTrigger(stateDefinition, result, nextAttempt, delay);
    ProcessVariables variables =
        state
            .variables()
            .with(retryAttemptVariable(stateDefinition.name()), nextAttempt)
            .with(retryMetadataVariable(stateDefinition.name()), retryMetadata)
            .with(LAST_RETRY_VARIABLE, retryMetadata)
            .with(LAST_TRIGGER_VARIABLE, triggerVariable("RETRY", retryMetadata));
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            state.state(),
            ProcessInstanceStatus.RUNNING,
            toJson(variables.values()),
            state.stateEnteredAt(),
            state.stateDeadlineAt(),
            null,
            null);
    if (updated == 0) {
      return state.park();
    }
    long nextVersion = state.version() + 1;
    commandScheduler.scheduleDelayed(
        new ProcessCommand(state.instanceId(), ProcessCommandReason.RETRY, nextVersion),
        partitionKey(definition.processType(), state.businessKey()),
        delay);
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            state.state(),
            state.state(),
            "retry",
            "RETRY",
            toJson(retryMetadata),
            now));
    return state.withVariables(variables).withVersion(nextVersion).park();
  }

  private <P> boolean canRetry(ExecutionState<P> state, StateDefinition<P> stateDefinition) {
    return retryAttempt(state, stateDefinition) < stateDefinition.retryPolicy().maxAttempts();
  }

  private <P> int retryAttempt(ExecutionState<P> state, StateDefinition<P> stateDefinition) {
    return state.variables().integer(retryAttemptVariable(stateDefinition.name())).orElse(0);
  }

  private static String retryAttemptVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state + ".attempt";
  }

  private static String retryMetadataVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state;
  }

  private static boolean processTimeoutCommand(ProcessCommand command) {
    return command.reason() == ProcessCommandReason.PROCESS_TIMEOUT;
  }

  private static boolean stateTimeoutCommand(ProcessCommand command) {
    return command.reason() == ProcessCommandReason.STATE_TIMEOUT
        || command.reason() == ProcessCommandReason.TIMEOUT;
  }

  private static boolean deadlineExpired(Instant deadlineAt, Instant now) {
    return deadlineAt != null && !deadlineAt.isAfter(now);
  }

  private <P> ProcessContext<P> processContext(ExecutionState<P> state, Instant now) {
    return new ProcessContext<>(
        state.instanceId(),
        state.processType(),
        state.definitionVersion(),
        state.state(),
        state.businessKey(),
        state.payload(),
        state.variables(),
        now);
  }

  private <P> TransitionContext<P> transitionContext(
      ExecutionState<P> state, StepResult actionResult, ExternalEvent event, Instant now) {
    return new TransitionContext<>(
        state.instanceId(),
        state.processType(),
        state.definitionVersion(),
        state.state(),
        state.businessKey(),
        state.payload(),
        state.variables(),
        actionResult,
        event,
        now);
  }

  private <P> void insertHistory(
      ExecutionState<P> state,
      String fromState,
      String toState,
      String transitionName,
      String triggerType,
      Map<String, Object> trigger,
      Instant now) {
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            fromState,
            toState,
            transitionName,
            triggerType,
            toJson(trigger),
            now));
  }

  private static ProcessInstanceStatus statusForTarget(StateDefinition<?> targetState) {
    return switch (targetState.kind()) {
      case ACTION, DECISION -> ProcessInstanceStatus.RUNNING;
      case WAIT -> ProcessInstanceStatus.WAITING;
      case TERMINAL -> terminalStatus(targetState);
    };
  }

  private static ProcessInstanceStatus terminalStatus(StateDefinition<?> stateDefinition) {
    ProcessInstanceStatus terminalStatus = stateDefinition.terminalStatus();
    if (terminalStatus == null) {
      throw new ProcessDefinitionException(
          "Terminal state must define terminal status: " + stateDefinition.name());
    }
    return terminalStatus;
  }

  private static Instant deleteAfter(
      ProcessDefinition<?> definition, ProcessInstanceStatus status, Instant completedAt) {
    return completedAt.plus(definition.retention().forStatus(status));
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
      case StepResult.AwaitEvent awaitEvent ->
          Map.of(
              "kind",
              "AWAIT_EVENT",
              "eventType",
              awaitEvent.eventType(),
              "correlationKey",
              awaitEvent.correlationKey(),
              "timeout",
              awaitEvent.timeout() == null ? "" : awaitEvent.timeout().toString());
      case StepResult.WithVariables withVariables -> actionTrigger(withVariables.delegate());
    };
  }

  private static ProcessVariables applyActionVariables(ExecutionState<?> state, StepResult result) {
    Map<String, Object> actionTrigger = actionTrigger(result);
    return state
        .variables()
        .withAll(actionData(result))
        .withAll(result.variableUpdates())
        .with(LAST_ACTION_RESULT_VARIABLE, actionTrigger)
        .with(LAST_TRIGGER_VARIABLE, triggerVariable("ACTION_RESULT", actionTrigger));
  }

  private static Map<String, Object> actionData(StepResult result) {
    return switch (result.baseResult()) {
      case StepResult.Success success -> success.data();
      case StepResult.BusinessFailure failure -> failure.data();
      case StepResult.RetryableFailure failure -> Map.of();
      case StepResult.FatalFailure failure -> Map.of();
      case StepResult.AwaitEvent awaitEvent -> Map.of();
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

  private static Map<String, Object> processTimeoutTrigger(
      String targetState, Instant deadlineAt, Instant triggeredAt) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("targetState", targetState);
    trigger.put("triggeredAt", triggeredAt.toString());
    if (deadlineAt != null) {
      trigger.put("deadlineAt", deadlineAt.toString());
    }
    return Map.copyOf(trigger);
  }

  private static Map<String, Object> stateTimeoutTrigger(
      StateDefinition<?> stateDefinition, Instant deadlineAt, Instant triggeredAt) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("state", stateDefinition.name());
    if (stateDefinition.eventType() != null) {
      trigger.put("eventType", stateDefinition.eventType());
    }
    if (stateDefinition.timeoutTargetState() != null) {
      trigger.put("targetState", stateDefinition.timeoutTargetState());
    }
    if (deadlineAt != null) {
      trigger.put("deadlineAt", deadlineAt.toString());
    }
    trigger.put("triggeredAt", triggeredAt.toString());
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

  private static Map<String, Object> triggerVariable(
      String triggerType, Map<String, Object> trigger) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("type", triggerType);
    value.putAll(trigger == null ? Map.of() : trigger);
    return Map.copyOf(value);
  }

  private static <P> TransitionDefinition<P> timeoutTransition(String name, String targetState) {
    return new TransitionDefinition<>(name, targetState, Integer.MIN_VALUE, context -> true);
  }

  private static Instant deadlineAt(Instant start, Duration timeout) {
    return timeout == null ? null : start.plus(timeout);
  }

  private static Instant stateDeadlineAt(StateDefinition<?> stateDefinition, Instant enteredAt) {
    return deadlineAt(enteredAt, timeoutForState(stateDefinition));
  }

  private static Duration timeoutForState(StateDefinition<?> stateDefinition) {
    if (stateDefinition.stateTimeout() != null) {
      return stateDefinition.stateTimeout();
    }
    return stateDefinition.kind() == StateKind.WAIT ? stateDefinition.waitTimeout() : null;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private <T> T fromJson(String json, Class<T> type, String valueName) {
    try {
      return objectMapper.readValue(json, type);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot deserialize " + valueName, e);
    }
  }

  private Map<String, Object> readMap(String json, String valueName) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot deserialize " + valueName, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static <P> ProcessDefinition<P> typed(ProcessDefinition<?> definition) {
    return (ProcessDefinition<P>) definition;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize process payload", e);
    }
  }

  private static String partitionKey(String processType, String key) {
    return processType + ":" + key;
  }

  private static String normalizeIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null) {
      return null;
    }
    if (idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
    return idempotencyKey;
  }

  private record ExecutionState<P>(
      UUID instanceId,
      String processType,
      int definitionVersion,
      String businessKey,
      String state,
      ProcessInstanceStatus status,
      long version,
      P payload,
      ProcessVariables variables,
      Instant processDeadlineAt,
      Instant stateEnteredAt,
      Instant stateDeadlineAt,
      boolean parked) {

    ExecutionState(
        UUID instanceId,
        String processType,
        int definitionVersion,
        String businessKey,
        String state,
        ProcessInstanceStatus status,
        long version,
        P payload,
        ProcessVariables variables,
        Instant processDeadlineAt,
        Instant stateEnteredAt,
        Instant stateDeadlineAt) {
      this(
          instanceId,
          processType,
          definitionVersion,
          businessKey,
          state,
          status,
          version,
          payload,
          variables,
          processDeadlineAt,
          stateEnteredAt,
          stateDeadlineAt,
          false);
    }

    ExecutionState<P> withState(
        String newState,
        ProcessInstanceStatus newStatus,
        long newVersion,
        Instant newStateEnteredAt,
        Instant newStateDeadlineAt) {
      return new ExecutionState<>(
          instanceId,
          processType,
          definitionVersion,
          businessKey,
          newState,
          newStatus,
          newVersion,
          payload,
          variables,
          processDeadlineAt,
          newStateEnteredAt,
          newStateDeadlineAt,
          false);
    }

    ExecutionState<P> withVersion(long newVersion) {
      return new ExecutionState<>(
          instanceId,
          processType,
          definitionVersion,
          businessKey,
          state,
          status,
          newVersion,
          payload,
          variables,
          processDeadlineAt,
          stateEnteredAt,
          stateDeadlineAt,
          false);
    }

    ExecutionState<P> withVariables(ProcessVariables newVariables) {
      return new ExecutionState<>(
          instanceId,
          processType,
          definitionVersion,
          businessKey,
          state,
          status,
          version,
          payload,
          newVariables,
          processDeadlineAt,
          stateEnteredAt,
          stateDeadlineAt,
          false);
    }

    ExecutionState<P> park() {
      return new ExecutionState<>(
          instanceId,
          processType,
          definitionVersion,
          businessKey,
          state,
          status,
          version,
          payload,
          variables,
          processDeadlineAt,
          stateEnteredAt,
          stateDeadlineAt,
          true);
    }
  }
}
