package dev.verkhovskiy.processmanager.autoconfigure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration properties for process-manager runtime. */
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "process.manager")
public class ProcessManagerProperties {

  private boolean enabled = true;

  @NotBlank private String taskType = "process-manager.command";

  @Positive private int cleanupBatchSize = 100;
}
