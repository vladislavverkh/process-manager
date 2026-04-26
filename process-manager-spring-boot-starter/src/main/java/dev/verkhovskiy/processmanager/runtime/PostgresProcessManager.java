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
import java.time.Instant;
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
    UUID instanceId = UUID.randomUUID();
    Instant now = Instant.now();
    processRepository.insertInstance(
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
            null,
            null,
            0));
    commandScheduler.schedule(
        new ProcessCommand(instanceId, ProcessCommandReason.START, 0),
        partitionKey(processType, businessKey));
    return instanceId;
  }

  @Override
  @Transactional
  public void signal(String eventType, String correlationKey, Map<String, Object> payload) {
    UUID eventId = UUID.randomUUID();
    processRepository.insertEvent(eventId, eventType, correlationKey, toJson(payload));
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
            new ProcessVariables(readMap(instance.variablesJson(), "process variables")));

    for (int step = 0; step < MAX_STEPS_PER_RESUME; step++) {
      StateDefinition<P> stateDefinition = definition.state(state.state());
      state =
          switch (stateDefinition.kind()) {
            case ACTION -> executeAction(definition, state, stateDefinition);
            case DECISION -> executeDecision(definition, state, stateDefinition);
            case WAIT -> executeWait(command, definition, state, stateDefinition);
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

    if (result instanceof StepResult.RetryableFailure && canRetry(state, stateDefinition)) {
      return scheduleRetry(definition, state, stateDefinition, result, now);
    }

    ProcessVariables variables = state.variables().without(retryAttemptVariable(state.state()));
    TransitionDefinition<P> transition =
        transitionSelector.select(
            stateDefinition, transitionContext(state.withVariables(variables), result, null, now));
    return applyTransition(
        definition,
        state.withVariables(variables),
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
      ProcessCommand command,
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition) {
    if (state.status() != ProcessInstanceStatus.WAITING) {
      return parkInWait(state, stateDefinition, "START", Map.of(), Instant.now());
    }

    if (command.reason() == ProcessCommandReason.TIMEOUT) {
      return advanceFromWait(definition, state, stateDefinition, null, "TIMEOUT", Map.of());
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
            readMap(event.get().payloadJson(), "event payload"),
            event.get().receivedAt());
    ExecutionState<P> updated =
        advanceFromWait(
            definition,
            state,
            stateDefinition,
            externalEvent,
            "EVENT",
            eventTrigger(externalEvent));
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
      Map<String, Object> trigger) {
    Instant now = Instant.now();
    TransitionDefinition<P> transition =
        transitionSelector.select(stateDefinition, transitionContext(state, null, event, now));
    return applyTransition(
        definition, state, stateDefinition, transition, triggerType, trigger, now);
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
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            stateDefinition.name(),
            terminalStatus,
            toJson(state.variables().values()),
            now,
            deleteAfter(definition, terminalStatus, now));
    if (updated == 0) {
      return state.park();
    }
    insertHistory(state, null, stateDefinition.name(), "START", "START", Map.of(), now);
    return state.withState(stateDefinition.name(), terminalStatus, state.version() + 1).park();
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
    Instant completedAt = targetState.terminal() ? now : null;
    Instant deleteAfter = targetState.terminal() ? deleteAfter(definition, status, now) : null;
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            targetState.name(),
            status,
            toJson(state.variables().values()),
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
        state.withState(targetState.name(), status, state.version() + 1);
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
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            stateDefinition.name(),
            ProcessInstanceStatus.WAITING,
            toJson(state.variables().values()),
            null,
            null);
    if (updated == 0) {
      return state.park();
    }
    insertHistory(
        state, null, stateDefinition.name(), stateDefinition.name(), triggerType, trigger, now);
    return registerWait(
            state.withState(
                stateDefinition.name(), ProcessInstanceStatus.WAITING, state.version() + 1),
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
    if (stateDefinition.waitTimeout() != null) {
      commandScheduler.scheduleDelayed(
          new ProcessCommand(state.instanceId(), ProcessCommandReason.TIMEOUT, state.version()),
          partitionKey(state.processType(), state.businessKey()),
          stateDefinition.waitTimeout());
    }
    return state;
  }

  private <P> ExecutionState<P> scheduleRetry(
      ProcessDefinition<P> definition,
      ExecutionState<P> state,
      StateDefinition<P> stateDefinition,
      StepResult result,
      Instant now) {
    int nextAttempt = retryAttempt(state, stateDefinition) + 1;
    ProcessVariables variables =
        state.variables().with(retryAttemptVariable(stateDefinition.name()), nextAttempt);
    int updated =
        processRepository.updateExecutionState(
            state.instanceId(),
            state.version(),
            state.state(),
            ProcessInstanceStatus.RUNNING,
            toJson(variables.values()),
            null,
            null);
    if (updated == 0) {
      return state.park();
    }
    long nextVersion = state.version() + 1;
    commandScheduler.scheduleDelayed(
        new ProcessCommand(state.instanceId(), ProcessCommandReason.RETRY, nextVersion),
        partitionKey(definition.processType(), state.businessKey()),
        stateDefinition.retryPolicy().delayForAttempt(nextAttempt));
    processRepository.insertHistory(
        new ProcessHistoryRecord(
            UUID.randomUUID(),
            state.instanceId(),
            state.processType(),
            state.state(),
            state.state(),
            "retry",
            "ACTION_RESULT",
            toJson(actionTrigger(result)),
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
    return switch (result) {
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
    };
  }

  private static Map<String, Object> eventTrigger(ExternalEvent event) {
    return Map.of(
        "eventType",
        event.eventType(),
        "correlationKey",
        event.correlationKey(),
        "payload",
        event.payload(),
        "receivedAt",
        event.receivedAt().toString());
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
        ProcessVariables variables) {
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
          false);
    }

    ExecutionState<P> withState(String newState, ProcessInstanceStatus newStatus, long newVersion) {
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
          true);
    }
  }
}
