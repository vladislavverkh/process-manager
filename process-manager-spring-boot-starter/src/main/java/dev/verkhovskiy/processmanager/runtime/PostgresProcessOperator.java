package dev.verkhovskiy.processmanager.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionRegistry;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessOperator;
import dev.verkhovskiy.processmanager.ProcessVariables;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.ProcessHistoryRecord;
import dev.verkhovskiy.processmanager.postgres.StoredProcessInstance;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-реализация ручных операторских операций над процессами. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Зависимости являются внедренными инфраструктурными Spring-бинами.")
public class PostgresProcessOperator implements ProcessOperator {

  private static final String LAST_TRIGGER_VARIABLE = "_pm.lastTrigger";
  private static final String LAST_CANCEL_VARIABLE = "_pm.lastCancel";
  private static final String LAST_RETRY_VARIABLE = "_pm.lastRetry";
  private static final String RETRY_ATTEMPT_VARIABLE_PREFIX = "_pm.retry.";
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ProcessDefinitionRegistry definitionRegistry;
  private final PostgresProcessRepository processRepository;
  private final ProcessCommandScheduler commandScheduler;
  private final ObjectMapper objectMapper;
  private final ProcessManagerMetrics metrics;

  public PostgresProcessOperator(
      ProcessDefinitionRegistry definitionRegistry,
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ObjectMapper objectMapper) {
    this(
        definitionRegistry,
        processRepository,
        commandScheduler,
        objectMapper,
        NoopProcessManagerMetrics.INSTANCE);
  }

  public PostgresProcessOperator(
      ProcessDefinitionRegistry definitionRegistry,
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ObjectMapper objectMapper,
      ProcessManagerMetrics metrics) {
    this.definitionRegistry = definitionRegistry;
    this.processRepository = processRepository;
    this.commandScheduler = commandScheduler;
    this.objectMapper = objectMapper;
    this.metrics = metrics == null ? NoopProcessManagerMetrics.INSTANCE : metrics;
  }

  @Override
  @Transactional
  public boolean cancel(UUID instanceId, String reason) {
    String processType = "unknown";
    String outcome = "error";
    try {
      Optional<StoredProcessInstance> found = processRepository.findInstanceForUpdate(instanceId);
      if (found.isEmpty()) {
        outcome = "not_found";
        return false;
      }
      if (terminalStatus(found.get().status())) {
        processType = found.get().processType();
        outcome = "terminal";
        return false;
      }

      StoredProcessInstance instance = found.get();
      processType = instance.processType();
      ProcessDefinition<?> definition =
          definitionRegistry.get(instance.processType(), instance.definitionVersion());
      Instant now = Instant.now();
      Map<String, Object> trigger = manualCancelTrigger(reason, now);
      ProcessVariables variables =
          new ProcessVariables(readMap(instance.variablesJson(), "process variables"))
              .with(LAST_CANCEL_VARIABLE, trigger)
              .with(LAST_TRIGGER_VARIABLE, triggerVariable("MANUAL_CANCEL", trigger));

      int updated =
          processRepository.updateExecutionState(
              instance.instanceId(),
              instance.version(),
              instance.state(),
              ProcessInstanceStatus.CANCELLED,
              toJson(variables.values()),
              instance.stateEnteredAt(),
              null,
              now,
              deleteAfter(definition, ProcessInstanceStatus.CANCELLED, now));
      if (updated == 0) {
        metrics.recordOptimisticLockConflict(instance.processType(), instance.state());
        outcome = "conflict";
        return false;
      }
      processRepository.deleteWaits(instance.instanceId());
      processRepository.insertHistory(
          new ProcessHistoryRecord(
              UUID.randomUUID(),
              instance.instanceId(),
              instance.processType(),
              instance.state(),
              instance.state(),
              "manual-cancel",
              "MANUAL_CANCEL",
              toJson(trigger),
              now));
      metrics.recordTransition(
          instance.processType(),
          instance.definitionVersion(),
          instance.state(),
          instance.state(),
          "manual-cancel",
          "MANUAL_CANCEL");
      metrics.recordProcessTerminal(
          instance.processType(),
          instance.definitionVersion(),
          instance.state(),
          ProcessInstanceStatus.CANCELLED,
          durationBetween(instance.startedAt(), now));
      outcome = "success";
      return true;
    } finally {
      metrics.recordOperatorOperation("cancel", processType, outcome);
    }
  }

  @Override
  @Transactional
  public boolean scheduleResume(UUID instanceId) {
    return scheduleActiveCommand(instanceId, ProcessCommandReason.RESUME, false);
  }

  @Override
  @Transactional
  public boolean scheduleRetry(UUID instanceId) {
    return scheduleManualRetry(instanceId);
  }

  private boolean scheduleActiveCommand(
      UUID instanceId, ProcessCommandReason reason, boolean runningOnly) {
    String processType = "unknown";
    String outcome = "error";
    String operation = reason == ProcessCommandReason.RETRY ? "schedule_retry" : "schedule_resume";
    try {
      Optional<StoredProcessInstance> found = processRepository.findInstanceForUpdate(instanceId);
      if (found.isEmpty()) {
        outcome = "not_found";
        return false;
      }
      StoredProcessInstance instance = found.get();
      processType = instance.processType();
      if (terminalStatus(instance.status())) {
        outcome = "terminal";
        return false;
      }
      if (runningOnly && instance.status() != ProcessInstanceStatus.RUNNING) {
        outcome = "not_running";
        return false;
      }
      commandScheduler.schedule(
          new ProcessCommand(instance.instanceId(), reason, instance.version()),
          partitionKey(instance.processType(), instance.businessKey()));
      outcome = "scheduled";
      return true;
    } finally {
      metrics.recordOperatorOperation(operation, processType, outcome);
    }
  }

  private boolean scheduleManualRetry(UUID instanceId) {
    String processType = "unknown";
    String outcome = "error";
    try {
      Optional<StoredProcessInstance> found = processRepository.findInstanceForUpdate(instanceId);
      if (found.isEmpty()) {
        outcome = "not_found";
        return false;
      }
      StoredProcessInstance instance = found.get();
      processType = instance.processType();
      if (terminalStatus(instance.status())) {
        outcome = "terminal";
        return false;
      }
      if (instance.status() != ProcessInstanceStatus.RUNNING) {
        outcome = "not_running";
        return false;
      }

      Instant now = Instant.now();
      Map<String, Object> trigger = manualRetryTrigger(instance.state(), now);
      ProcessVariables variables =
          new ProcessVariables(readMap(instance.variablesJson(), "process variables"))
              .without(retryAttemptVariable(instance.state()))
              .without(retryMetadataVariable(instance.state()))
              .without(LAST_RETRY_VARIABLE)
              .with(LAST_TRIGGER_VARIABLE, triggerVariable("MANUAL_RETRY", trigger));
      int updated =
          processRepository.updateExecutionState(
              instance.instanceId(),
              instance.version(),
              instance.state(),
              ProcessInstanceStatus.RUNNING,
              toJson(variables.values()),
              instance.stateEnteredAt(),
              instance.stateDeadlineAt(),
              null,
              null);
      if (updated == 0) {
        metrics.recordOptimisticLockConflict(instance.processType(), instance.state());
        outcome = "conflict";
        return false;
      }

      long nextVersion = instance.version() + 1;
      commandScheduler.schedule(
          new ProcessCommand(instance.instanceId(), ProcessCommandReason.RETRY, nextVersion),
          partitionKey(instance.processType(), instance.businessKey()));
      processRepository.insertHistory(
          new ProcessHistoryRecord(
              UUID.randomUUID(),
              instance.instanceId(),
              instance.processType(),
              instance.state(),
              instance.state(),
              "manual-retry",
              "MANUAL_RETRY",
              toJson(trigger),
              now));
      metrics.recordTransition(
          instance.processType(),
          instance.definitionVersion(),
          instance.state(),
          instance.state(),
          "manual-retry",
          "MANUAL_RETRY");
      outcome = "scheduled";
      return true;
    } finally {
      metrics.recordOperatorOperation("schedule_retry", processType, outcome);
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

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize operator data", e);
    }
  }

  private static Map<String, Object> manualCancelTrigger(String reason, Instant cancelledAt) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    if (reason != null && !reason.isBlank()) {
      trigger.put("reason", reason);
    }
    trigger.put("cancelledAt", cancelledAt.toString());
    return Map.copyOf(trigger);
  }

  private static Map<String, Object> manualRetryTrigger(String state, Instant scheduledAt) {
    Map<String, Object> trigger = new LinkedHashMap<>();
    trigger.put("state", state);
    trigger.put("resetRetry", true);
    trigger.put("scheduledAt", scheduledAt.toString());
    return Map.copyOf(trigger);
  }

  private static Map<String, Object> triggerVariable(
      String triggerType, Map<String, Object> trigger) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("type", triggerType);
    value.putAll(trigger == null ? Map.of() : trigger);
    return Map.copyOf(value);
  }

  private static boolean terminalStatus(ProcessInstanceStatus status) {
    return status == ProcessInstanceStatus.COMPLETED
        || status == ProcessInstanceStatus.FAILED
        || status == ProcessInstanceStatus.CANCELLED;
  }

  private static String retryAttemptVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state + ".attempt";
  }

  private static String retryMetadataVariable(String state) {
    return RETRY_ATTEMPT_VARIABLE_PREFIX + state;
  }

  private static Instant deleteAfter(
      ProcessDefinition<?> definition, ProcessInstanceStatus status, Instant completedAt) {
    return completedAt.plus(definition.retention().forStatus(status));
  }

  private static Duration durationBetween(Instant start, Instant end) {
    if (start == null || end == null || end.isBefore(start)) {
      return Duration.ZERO;
    }
    return Duration.between(start, end);
  }

  private static String partitionKey(String processType, String key) {
    return processType + ":" + key;
  }
}
