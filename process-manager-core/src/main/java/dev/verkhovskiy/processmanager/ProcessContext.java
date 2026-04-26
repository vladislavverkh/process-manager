package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.UUID;

/** Context passed to process actions. */
public record ProcessContext<P>(
    UUID instanceId,
    String processType,
    int definitionVersion,
    String state,
    String businessKey,
    P payload,
    ProcessVariables variables,
    Instant now) {}
