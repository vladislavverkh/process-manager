package dev.verkhovskiy.processmanager.autoconfigure;

import dev.verkhovskiy.processmanager.runtime.ProcessMetadataPolicy;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Конфигурационные свойства среды выполнения process-manager. */
@Getter
@ConfigurationProperties(prefix = "process.manager")
public class ProcessManagerProperties {

  /** Включает или отключает autoconfiguration основного process-manager starter. */
  private boolean enabled = true;

  /** Batch size одного прохода retention cleanup. */
  private int cleanupBatchSize = 100;

  /** Batch size одного прохода deadline watchdog. */
  private int deadlineBatchSize = 100;

  /** Настройки сохранения диагностической metadata runtime'а. */
  private Metadata metadata = new Metadata();

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setCleanupBatchSize(int cleanupBatchSize) {
    this.cleanupBatchSize = positive(cleanupBatchSize, "cleanupBatchSize");
  }

  public void setDeadlineBatchSize(int deadlineBatchSize) {
    this.deadlineBatchSize = positive(deadlineBatchSize, "deadlineBatchSize");
  }

  public void setMetadata(Metadata metadata) {
    this.metadata = metadata == null ? new Metadata() : metadata;
  }

  ProcessMetadataPolicy metadataPolicy() {
    return new ProcessMetadataPolicy(
        metadata.historyTrigger,
        metadata.variables.lastTrigger,
        metadata.variables.lastActionResult,
        metadata.variables.lastEvent,
        metadata.variables.lastRetry,
        metadata.variables.lastCancel,
        metadata.variables.retryMetadata);
  }

  private static int positive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  @Getter
  public static class Metadata {

    /** Объем trigger JSON, который сохраняется в pm_process_history.trigger_json. */
    private ProcessMetadataPolicy.HistoryTrigger historyTrigger =
        ProcessMetadataPolicy.HistoryTrigger.FULL;

    /** Настройки служебных variables с префиксом _pm. */
    private MetadataVariables variables = new MetadataVariables();

    public void setHistoryTrigger(ProcessMetadataPolicy.HistoryTrigger historyTrigger) {
      this.historyTrigger =
          historyTrigger == null ? ProcessMetadataPolicy.HistoryTrigger.FULL : historyTrigger;
    }

    public void setVariables(MetadataVariables variables) {
      this.variables = variables == null ? new MetadataVariables() : variables;
    }
  }

  @Getter
  public static class MetadataVariables {

    /** Сохранять _pm.lastTrigger. */
    private boolean lastTrigger = true;

    /** Сохранять _pm.lastActionResult. */
    private boolean lastActionResult = true;

    /** Сохранять _pm.lastEvent. */
    private boolean lastEvent = true;

    /** Сохранять _pm.lastRetry. */
    private boolean lastRetry = true;

    /** Сохранять _pm.lastCancel при ручной отмене. */
    private boolean lastCancel = true;

    /** Сохранять _pm.retry.<state> с подробной retry metadata. */
    private boolean retryMetadata = true;

    public void setLastTrigger(boolean lastTrigger) {
      this.lastTrigger = lastTrigger;
    }

    public void setLastActionResult(boolean lastActionResult) {
      this.lastActionResult = lastActionResult;
    }

    public void setLastEvent(boolean lastEvent) {
      this.lastEvent = lastEvent;
    }

    public void setLastRetry(boolean lastRetry) {
      this.lastRetry = lastRetry;
    }

    public void setLastCancel(boolean lastCancel) {
      this.lastCancel = lastCancel;
    }

    public void setRetryMetadata(boolean retryMetadata) {
      this.retryMetadata = retryMetadata;
    }
  }
}
