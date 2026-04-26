package dev.verkhovskiy.processmanager.postgres;

import java.time.Instant;
import java.util.UUID;

/** Process transition history record. */
public record ProcessHistoryRecord(
    UUID historyId,
    UUID instanceId,
    String processType,
    String fromState,
    String toState,
    String transitionName,
    String triggerType,
    String triggerJson,
    Instant createdAt) {}
