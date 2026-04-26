package dev.verkhovskiy.processmanager.rest;

import java.util.UUID;

/** Результат ручной операторской операции над процессом. */
public record ProcessOperationResponse(UUID instanceId, boolean accepted) {}
