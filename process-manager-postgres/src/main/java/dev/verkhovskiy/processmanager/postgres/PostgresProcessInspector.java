package dev.verkhovskiy.processmanager.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessDetailsView;
import dev.verkhovskiy.processmanager.ProcessHistoryView;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessInstanceQuery;
import dev.verkhovskiy.processmanager.ProcessInstanceView;
import dev.verkhovskiy.processmanager.ProcessVariables;
import dev.verkhovskiy.processmanager.ProcessWaitView;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-реализация API чтения состояния процессов. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Зависимости являются внедренными инфраструктурными Spring-бинами.")
public class PostgresProcessInspector implements ProcessInspector {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final PostgresProcessRepository processRepository;
  private final ObjectMapper objectMapper;

  public PostgresProcessInspector(
      PostgresProcessRepository processRepository, ObjectMapper objectMapper) {
    this.processRepository = processRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProcessInstanceView> findInstance(UUID instanceId) {
    return processRepository.findInstance(instanceId).map(this::toView);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProcessInstanceView> findActiveInstance(String processType, String businessKey) {
    return processRepository.findActiveInstance(processType, businessKey).map(this::toView);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProcessInstanceView> findInstances(ProcessInstanceQuery query) {
    return processRepository.findInstances(query).stream().map(this::toView).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProcessWaitView> findWaits(UUID instanceId) {
    return processRepository.findWaitsByInstance(instanceId).stream()
        .map(PostgresProcessInspector::toView)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ProcessHistoryView> findHistory(UUID instanceId, int limit) {
    return processRepository.findHistory(instanceId, normalizeHistoryLimit(limit)).stream()
        .map(this::toView)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProcessDetailsView> findDetails(UUID instanceId) {
    return findInstance(instanceId)
        .map(
            instance ->
                new ProcessDetailsView(instance, findWaits(instanceId), findHistory(instanceId)));
  }

  private ProcessInstanceView toView(StoredProcessInstance instance) {
    return new ProcessInstanceView(
        instance.instanceId(),
        instance.processType(),
        instance.definitionVersion(),
        instance.payloadSchemaVersion(),
        instance.businessKey(),
        instance.state(),
        instance.status(),
        readMap(instance.payloadJson(), "process payload"),
        new ProcessVariables(readMap(instance.variablesJson(), "process variables")),
        instance.startedAt(),
        instance.updatedAt(),
        instance.processDeadlineAt(),
        instance.stateEnteredAt(),
        instance.stateDeadlineAt(),
        instance.completedAt(),
        instance.deleteAfter(),
        instance.version());
  }

  private static ProcessWaitView toView(StoredProcessWait wait) {
    return new ProcessWaitView(
        wait.waitId(),
        wait.instanceId(),
        wait.processType(),
        wait.state(),
        wait.eventType(),
        wait.correlationKey(),
        wait.expiresAt(),
        wait.createdAt());
  }

  private ProcessHistoryView toView(ProcessHistoryRecord history) {
    return new ProcessHistoryView(
        history.historyId(),
        history.instanceId(),
        history.processType(),
        history.fromState(),
        history.toState(),
        history.transitionName(),
        history.triggerType(),
        readMap(history.triggerJson(), "history trigger"),
        history.createdAt());
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

  private static int normalizeHistoryLimit(int limit) {
    if (limit <= 0) {
      return DEFAULT_HISTORY_LIMIT;
    }
    if (limit > ProcessInstanceQuery.MAX_LIMIT) {
      throw new IllegalArgumentException(
          "history limit must not be greater than " + ProcessInstanceQuery.MAX_LIMIT);
    }
    return limit;
  }
}
