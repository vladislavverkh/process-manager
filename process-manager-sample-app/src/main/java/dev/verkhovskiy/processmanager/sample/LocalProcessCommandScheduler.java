package dev.verkhovskiy.processmanager.sample;

import dev.verkhovskiy.processmanager.ProcessCommand;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessManager;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class LocalProcessCommandScheduler implements ProcessCommandScheduler, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(LocalProcessCommandScheduler.class);

  private final ObjectProvider<ProcessManager> processManager;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "sample-process-command-scheduler");
            thread.setDaemon(true);
            return thread;
          });

  public LocalProcessCommandScheduler(ObjectProvider<ProcessManager> processManager) {
    this.processManager = processManager;
  }

  @Override
  public void schedule(ProcessCommand command, String partitionKey) {
    scheduleDelayed(command, partitionKey, Duration.ZERO);
  }

  @Override
  public void scheduleDelayed(ProcessCommand command, String partitionKey, Duration delay) {
    Runnable task = () -> resume(command, partitionKey);
    Runnable submit = () -> executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              submit.run();
            }
          });
      return;
    }
    submit.run();
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }

  private void resume(ProcessCommand command, String partitionKey) {
    try {
      processManager.getObject().resume(command);
    } catch (RuntimeException e) {
      log.warn("Process command failed, partitionKey={}, command={}", partitionKey, command, e);
      throw e;
    }
  }
}
