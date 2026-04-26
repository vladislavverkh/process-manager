package dev.verkhovskiy.processmanager.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionRegistry;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.runtime.PostgresProcessManager;
import dev.verkhovskiy.processmanager.taskqueue.ProcessCommandTaskHandler;
import dev.verkhovskiy.processmanager.taskqueue.TaskQueueProcessCommandScheduler;
import dev.verkhovskiy.taskqueue.service.TaskProducer;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Spring Boot autoconfiguration for process-manager. */
@AutoConfiguration
@EnableConfigurationProperties(ProcessManagerProperties.class)
@ConditionalOnProperty(
    prefix = "process.manager",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ProcessManagerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ProcessDefinitionRegistry processDefinitionRegistry(List<ProcessDefinition<?>> definitions) {
    return new ProcessDefinitionRegistry(definitions);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnClass(NamedParameterJdbcTemplate.class)
  PostgresProcessRepository postgresProcessRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    return new PostgresProcessRepository(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(TaskProducer.class)
  ProcessCommandScheduler processCommandScheduler(
      TaskProducer taskProducer, ObjectMapper objectMapper) {
    return new TaskQueueProcessCommandScheduler(taskProducer, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(ProcessCommandScheduler.class)
  ProcessManager processManager(
      ProcessDefinitionRegistry definitionRegistry,
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ObjectMapper objectMapper) {
    return new PostgresProcessManager(
        definitionRegistry, processRepository, commandScheduler, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(ProcessManager.class)
  ProcessCommandTaskHandler processCommandTaskHandler(
      ProcessManager processManager, ObjectMapper objectMapper) {
    return new ProcessCommandTaskHandler(processManager, objectMapper);
  }
}
