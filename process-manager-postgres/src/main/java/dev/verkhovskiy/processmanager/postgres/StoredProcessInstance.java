package dev.verkhovskiy.processmanager.postgres;

import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import java.time.Instant;
import java.util.UUID;

/** Низкоуровневое PostgreSQL-представление экземпляра процесса. */
public record StoredProcessInstance(
    UUID instanceId,
    String processType,
    int definitionVersion,
    int payloadSchemaVersion,
    String businessKey,
    String state,
    ProcessInstanceStatus status,
    String payloadJson,
    String variablesJson,
    Instant startedAt,
    Instant updatedAt,
    Instant processDeadlineAt,
    Instant stateEnteredAt,
    Instant stateDeadlineAt,
    Instant completedAt,
    Instant deleteAfter,
    long version) {}
