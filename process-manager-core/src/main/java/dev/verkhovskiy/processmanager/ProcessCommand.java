package dev.verkhovskiy.processmanager;

import java.util.UUID;

/** Durable command scheduled through task queue to continue process execution. */
public record ProcessCommand(UUID instanceId, ProcessCommandReason reason, long expectedVersion) {}
