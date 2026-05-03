package dev.verkhovskiy.processmanager.runtime;

import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import org.springframework.transaction.annotation.Transactional;

/** Deletes terminal process instances whose retention period has expired. */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
    justification =
        "Dependencies are Spring-managed infrastructure beans; class is non-final so Spring can create transactional proxies.")
public class ProcessRetentionCleanup {

  private final PostgresProcessRepository processRepository;
  private final int batchSize;
  private final ProcessManagerMetrics metrics;

  public ProcessRetentionCleanup(PostgresProcessRepository processRepository, int batchSize) {
    this(processRepository, batchSize, NoopProcessManagerMetrics.INSTANCE);
  }

  public ProcessRetentionCleanup(
      PostgresProcessRepository processRepository, int batchSize, ProcessManagerMetrics metrics) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    this.processRepository = processRepository;
    this.batchSize = batchSize;
    this.metrics = metrics == null ? NoopProcessManagerMetrics.INSTANCE : metrics;
  }

  /** Deletes one retention cleanup batch with the configured batch size. */
  @Transactional
  public int runOnce() {
    return runOnce(batchSize);
  }

  /** Deletes one retention cleanup batch with the provided limit. */
  @Transactional
  public int runOnce(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    long startedAtNanos = System.nanoTime();
    String outcome = "success";
    int deletedInstances = 0;
    try {
      deletedInstances = processRepository.deleteExpiredTerminalInstances(limit);
      return deletedInstances;
    } catch (RuntimeException e) {
      outcome = "error";
      throw e;
    } finally {
      metrics.recordRetentionCleanup(elapsedSince(startedAtNanos), deletedInstances, outcome);
    }
  }

  private static Duration elapsedSince(long startedAtNanos) {
    return Duration.ofNanos(System.nanoTime() - startedAtNanos);
  }
}
