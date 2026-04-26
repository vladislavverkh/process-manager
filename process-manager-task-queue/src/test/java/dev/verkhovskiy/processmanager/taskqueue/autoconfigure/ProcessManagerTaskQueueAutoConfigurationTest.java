package dev.verkhovskiy.processmanager.taskqueue.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.processmanager.taskqueue.ProcessCommandTaskHandler;
import dev.verkhovskiy.processmanager.taskqueue.TaskQueueProcessCommandScheduler;
import dev.verkhovskiy.taskqueue.service.TaskProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProcessManagerTaskQueueAutoConfigurationTest {

  @Test
  void createsSchedulerWhenTaskProducerExists() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ProcessManagerTaskQueueSchedulerAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(TaskProducer.class, () -> mock(TaskProducer.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(ProcessCommandScheduler.class);
              assertThat(context).hasSingleBean(TaskQueueProcessCommandScheduler.class);
            });
  }

  @Test
  void createsHandlerWhenProcessManagerExists() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ProcessManagerTaskQueueHandlerAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(ProcessManager.class, () -> mock(ProcessManager.class))
        .run(context -> assertThat(context).hasSingleBean(ProcessCommandTaskHandler.class));
  }

  @Test
  void backsOffWhenDisabled() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ProcessManagerTaskQueueSchedulerAutoConfiguration.class,
                ProcessManagerTaskQueueHandlerAutoConfiguration.class))
        .withPropertyValues("process.manager.task-queue.enabled=false")
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(TaskProducer.class, () -> mock(TaskProducer.class))
        .withBean(ProcessManager.class, () -> mock(ProcessManager.class))
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(ProcessCommandScheduler.class);
              assertThat(context).doesNotHaveBean(ProcessCommandTaskHandler.class);
            });
  }
}
