package dev.verkhovskiy.processmanager.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessDefinitionRegistry;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.processmanager.ProcessOperator;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.runtime.ProcessDeadlineWatchdog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ProcessManagerAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ProcessManagerAutoConfiguration.class))
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
          .withBean(ProcessCommandScheduler.class, () -> mock(ProcessCommandScheduler.class));

  @Test
  void createsProcessManagerInfrastructure() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(ProcessDefinitionRegistry.class);
          assertThat(context).hasSingleBean(PostgresProcessRepository.class);
          assertThat(context).hasSingleBean(ProcessInspector.class);
          assertThat(context).hasSingleBean(ProcessCommandScheduler.class);
          assertThat(context).hasSingleBean(ProcessManager.class);
          assertThat(context).hasSingleBean(ProcessOperator.class);
          assertThat(context).hasSingleBean(ProcessDeadlineWatchdog.class);
        });
  }

  @Test
  void backsOffWhenDisabled() {
    contextRunner
        .withPropertyValues("process.manager.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(ProcessManager.class));
  }
}
