package dev.verkhovskiy.processmanager.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionRegistry;
import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.StoredProcessInstance;
import dev.verkhovskiy.processmanager.postgres.StoredProcessWait;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Initial PostgreSQL-backed process manager implementation. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Collaborators are injected infrastructure beans.")
public class PostgresProcessManager implements ProcessManager {

  private final ProcessDefinitionRegistry definitionRegistry;
  private final PostgresProcessRepository processRepository;
  private final ProcessCommandScheduler commandScheduler;
  private final ObjectMapper objectMapper;

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
    processRepository
        .findInstanceForUpdate(instanceId)
        .orElseThrow(
            () -> new IllegalArgumentException("Process instance not found: " + instanceId));
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
}
