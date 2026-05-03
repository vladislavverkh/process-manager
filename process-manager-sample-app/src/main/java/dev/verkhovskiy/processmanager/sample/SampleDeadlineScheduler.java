package dev.verkhovskiy.processmanager.sample;

import dev.verkhovskiy.processmanager.runtime.ProcessDeadlineWatchdog;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@DependsOn(ProcessManagerLiquibaseConfiguration.LIQUIBASE_BEAN_NAME)
@Component
@RequiredArgsConstructor
public class SampleDeadlineScheduler {

  private final ProcessDeadlineWatchdog deadlineWatchdog;

  @Scheduled(fixedDelayString = "${sample.process-deadline-scan-delay:PT10S}")
  public void processDeadlines() {
    deadlineWatchdog.runOnce();
  }
}
