package dev.verkhovskiy.processmanager.taskqueue.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.taskqueue.TaskQueueProcessCommandScheduler;
import dev.verkhovskiy.taskqueue.service.TaskProducer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Автоконфигурация планировщика process commands через task-queue-postgres. */
@AutoConfiguration(
    beforeName = "dev.verkhovskiy.processmanager.autoconfigure.ProcessManagerAutoConfiguration")
@ConditionalOnClass(TaskProducer.class)
@ConditionalOnProperty(
    prefix = "process.manager.task-queue",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ProcessManagerTaskQueueSchedulerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(TaskProducer.class)
  ProcessCommandScheduler processCommandScheduler(
      TaskProducer taskProducer, ObjectMapper objectMapper) {
    return new TaskQueueProcessCommandScheduler(taskProducer, objectMapper);
  }
}
