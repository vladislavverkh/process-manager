package dev.verkhovskiy.processmanager.autoconfigure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Конфигурационные свойства среды выполнения process-manager. */
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "process.manager")
public class ProcessManagerProperties {

  private boolean enabled = true;

  @NotBlank private String taskType = "process-manager.command";

  @Positive private int cleanupBatchSize = 100;

  @Positive private int deadlineBatchSize = 100;
}
