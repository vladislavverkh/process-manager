package dev.verkhovskiy.processmanager.taskqueue.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.processmanager.taskqueue.ProcessCommandTaskHandler;
import dev.verkhovskiy.taskqueue.handler.TaskHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Автоконфигурация обработчика process commands из task-queue-postgres. */
@AutoConfiguration(
    afterName = "dev.verkhovskiy.processmanager.autoconfigure.ProcessManagerAutoConfiguration")
@ConditionalOnClass(TaskHandler.class)
@ConditionalOnProperty(
    prefix = "process.manager.task-queue",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ProcessManagerTaskQueueHandlerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(ProcessManager.class)
  ProcessCommandTaskHandler processCommandTaskHandler(
      ProcessManager processManager, ObjectMapper objectMapper) {
    return new ProcessCommandTaskHandler(processManager, objectMapper);
  }
}
