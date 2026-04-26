package dev.verkhovskiy.processmanager.rest.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessOperator;
import dev.verkhovskiy.processmanager.rest.ProcessOperatorController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ProcessManagerRestAutoConfigurationTest {

  private final WebApplicationContextRunner webContextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ProcessManagerRestAutoConfiguration.class))
          .withBean(ProcessInspector.class, () -> mock(ProcessInspector.class))
          .withBean(ProcessOperator.class, () -> mock(ProcessOperator.class));

  @Test
  void createsControllerInWebContext() {
    webContextRunner.run(
        context -> assertThat(context).hasSingleBean(ProcessOperatorController.class));
  }

  @Test
  void backsOffWhenDisabled() {
    webContextRunner
        .withPropertyValues("process.manager.rest.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ProcessOperatorController.class));
  }

  @Test
  void backsOffWithoutOperatorBeans() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProcessManagerRestAutoConfiguration.class))
        .run(context -> assertThat(context).doesNotHaveBean(ProcessOperatorController.class));
  }

  @Test
  void backsOffInNonWebContext() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProcessManagerRestAutoConfiguration.class))
        .withBean(ProcessInspector.class, () -> mock(ProcessInspector.class))
        .withBean(ProcessOperator.class, () -> mock(ProcessOperator.class))
        .run(context -> assertThat(context).doesNotHaveBean(ProcessOperatorController.class));
  }
}
