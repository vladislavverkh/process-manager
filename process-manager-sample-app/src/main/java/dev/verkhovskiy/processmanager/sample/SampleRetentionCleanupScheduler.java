package dev.verkhovskiy.processmanager.sample;

import dev.verkhovskiy.processmanager.runtime.ProcessRetentionCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@DependsOn(ProcessManagerLiquibaseConfiguration.LIQUIBASE_BEAN_NAME)
@Component
@RequiredArgsConstructor
public class SampleRetentionCleanupScheduler {

  private final ProcessRetentionCleanup retentionCleanup;

  @Scheduled(fixedDelayString = "${sample.process-retention-cleanup-delay:PT1M}")
  public void cleanupRetainedProcesses() {
    retentionCleanup.runOnce();
  }
}
