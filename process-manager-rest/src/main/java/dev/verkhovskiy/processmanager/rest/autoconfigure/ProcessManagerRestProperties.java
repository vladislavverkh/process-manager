package dev.verkhovskiy.processmanager.rest.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Конфигурационные свойства Operator REST API process-manager. */
@ConfigurationProperties(prefix = "process.manager.rest")
public class ProcessManagerRestProperties {

  /** Включает или отключает autoconfiguration Operator REST API. */
  private boolean enabled = true;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
