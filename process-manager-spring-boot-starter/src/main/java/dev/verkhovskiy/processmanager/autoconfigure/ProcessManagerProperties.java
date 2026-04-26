package dev.verkhovskiy.processmanager.autoconfigure;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Конфигурационные свойства среды выполнения process-manager. */
@Getter
@ConfigurationProperties(prefix = "process.manager")
public class ProcessManagerProperties {

  private boolean enabled = true;

  private int cleanupBatchSize = 100;

  private int deadlineBatchSize = 100;

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setCleanupBatchSize(int cleanupBatchSize) {
    this.cleanupBatchSize = positive(cleanupBatchSize, "cleanupBatchSize");
  }

  public void setDeadlineBatchSize(int deadlineBatchSize) {
    this.deadlineBatchSize = positive(deadlineBatchSize, "deadlineBatchSize");
  }

  private static int positive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
