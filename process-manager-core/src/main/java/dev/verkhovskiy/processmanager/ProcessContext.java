package dev.verkhovskiy.processmanager;

import java.time.Instant;
import java.util.UUID;

/** Контекст, передаваемый действию процесса. */
public record ProcessContext<P>(
    UUID instanceId,
    String processType,
    int definitionVersion,
    String state,
    String businessKey,
    P payload,
    ProcessVariables variables,
    Instant now) {}
