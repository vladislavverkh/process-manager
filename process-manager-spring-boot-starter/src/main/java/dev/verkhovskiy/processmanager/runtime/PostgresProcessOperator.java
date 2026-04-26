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
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ProcessDefinitionRegistry definitionRegistry;
  private final PostgresProcessRepository processRepository;
  private final ProcessCommandScheduler commandScheduler;
  private final ObjectMapper objectMapper;

  public PostgresProcessOperator(
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
  public boolean cancel(UUID instanceId, String reason) {
    Optional<StoredProcessInstance> found = processRepository.findInstanceForUpdate(instanceId);
    if (found.isEmpty() || terminalStatus(found.get().status())) {
      return false;
    }

    StoredProcessInstance instance = found.get();
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
    return true;
  }

  @Override
  @Transactional
  public boolean scheduleResume(UUID instanceId) {
    return scheduleActiveCommand(instanceId, ProcessCommandReason.RESUME, false);
  }

  @Override
  @Transactional
  public boolean scheduleRetry(UUID instanceId) {
    return scheduleActiveCommand(instanceId, ProcessCommandReason.RETRY, true);
  }

  private boolean scheduleActiveCommand(
      UUID instanceId, ProcessCommandReason reason, boolean runningOnly) {
    Optional<StoredProcessInstance> found = processRepository.findInstanceForUpdate(instanceId);
    if (found.isEmpty() || terminalStatus(found.get().status())) {
      return false;
    }
    StoredProcessInstance instance = found.get();
    if (runningOnly && instance.status() != ProcessInstanceStatus.RUNNING) {
      return false;
    }
    commandScheduler.schedule(
        new ProcessCommand(instance.instanceId(), reason, instance.version()),
        partitionKey(instance.processType(), instance.businessKey()));
    return true;
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

  private static Instant deleteAfter(
      ProcessDefinition<?> definition, ProcessInstanceStatus status, Instant completedAt) {
    return completedAt.plus(definition.retention().forStatus(status));
  }

  private static String partitionKey(String processType, String key) {
    return processType + ":" + key;
  }
}
