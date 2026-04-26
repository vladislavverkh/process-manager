package dev.verkhovskiy.processmanager.postgres;

import dev.verkhovskiy.processmanager.ProcessInstanceStatus;
import java.time.Instant;
import java.util.UUID;

/** Raw PostgreSQL representation of a process instance. */
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
    Instant completedAt,
    Instant deleteAfter,
    long version) {}
