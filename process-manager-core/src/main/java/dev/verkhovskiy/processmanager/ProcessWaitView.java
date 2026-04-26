package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.UUID;

/** Ожидание внешнего события, зарегистрированное экземпляром процесса. */
public record ProcessWaitView(
    UUID waitId,
    UUID instanceId,
    String processType,
    String state,
    String eventType,
    String correlationKey,
    Instant expiresAt,
    Instant createdAt) {}
