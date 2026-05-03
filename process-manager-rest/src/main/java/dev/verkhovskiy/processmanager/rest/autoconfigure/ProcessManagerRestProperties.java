package dev.verkhovskiy.processmanager.rest.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Конфигурационные свойства Operator REST API process-manager. */
@ConfigurationProperties(prefix = "process.manager.rest")
@Getter
@Setter
public class ProcessManagerRestProperties {

  /** Включает или отключает autoconfiguration Operator REST API. */
  private boolean enabled = true;
}
