package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Снимок текущего состояния экземпляра процесса для чтения и диагностики. */
public record ProcessInstanceView(
    UUID instanceId,
    String processType,
    int definitionVersion,
    int payloadSchemaVersion,
    String businessKey,
    String state,
    ProcessInstanceStatus status,
    Map<String, Object> payload,
    ProcessVariables variables,
    Instant startedAt,
    Instant updatedAt,
    Instant processDeadlineAt,
    Instant stateEnteredAt,
    Instant stateDeadlineAt,
    Instant completedAt,
    Instant deleteAfter,
    long version) {

  public ProcessInstanceView {
    payload = Map.copyOf(payload == null ? Map.of() : payload);
    variables = variables == null ? ProcessVariables.empty() : variables;
  }
}
