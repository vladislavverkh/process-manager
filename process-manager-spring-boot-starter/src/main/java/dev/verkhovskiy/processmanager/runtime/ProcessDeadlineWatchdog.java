package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandReason;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.postgres.StoredProcessInstance;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Сканирует истекшие дедлайны процессов и планирует команды таймаута. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Зависимости являются внедренными инфраструктурными Spring-бинами.")
public final class ProcessDeadlineWatchdog {

  private final PostgresProcessRepository processRepository;
  private final ProcessCommandScheduler commandScheduler;
  private final int batchSize;

  public ProcessDeadlineWatchdog(
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      int batchSize) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    this.processRepository = processRepository;
    this.commandScheduler = commandScheduler;
    this.batchSize = batchSize;
  }

  /** Обрабатывает один батч истекших дедлайнов с размером из конфигурации. */
  @Transactional
  public int runOnce() {
    return runOnce(batchSize);
  }

  /** Обрабатывает один батч истекших дедлайнов указанного размера. */
  @Transactional
  public int runOnce(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    List<StoredProcessInstance> instances = processRepository.findExpiredDeadlinesForUpdate(limit);
    Instant now = Instant.now();
    for (StoredProcessInstance instance : instances) {
      ProcessCommandReason reason = timeoutReason(instance, now);
      commandScheduler.schedule(
          new ProcessCommand(instance.instanceId(), reason, instance.version()),
          partitionKey(instance.processType(), instance.businessKey()));
    }
    return instances.size();
  }

  private static ProcessCommandReason timeoutReason(
      StoredProcessInstance instance, Instant triggeredAt) {
    Instant processDeadlineAt = instance.processDeadlineAt();
    Instant stateDeadlineAt = instance.stateDeadlineAt();
    if (processDeadlineAt == null) {
      return ProcessCommandReason.STATE_TIMEOUT;
    }
    if (stateDeadlineAt == null
        || deadlineExpired(processDeadlineAt, triggeredAt)
        || !processDeadlineAt.isAfter(stateDeadlineAt)) {
      return ProcessCommandReason.PROCESS_TIMEOUT;
    }
    return ProcessCommandReason.STATE_TIMEOUT;
  }

  private static boolean deadlineExpired(Instant deadlineAt, Instant triggeredAt) {
    return deadlineAt != null && !deadlineAt.isAfter(triggeredAt);
  }

  private static String partitionKey(String processType, String key) {
    return processType + ":" + key;
  }
}
