package dev.verkhovskiy.processmanager;

import java.util.UUID;

/** Персистентная команда, которую очередь задач планирует для продолжения исполнения процесса. */
public record ProcessCommand(UUID instanceId, ProcessCommandReason reason, long expectedVersion) {}
