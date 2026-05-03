package dev.verkhovskiy.processmanager.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.verkhovskiy.processmanager.ProcessCommandScheduler;
import dev.verkhovskiy.processmanager.ProcessDefinition;
import dev.verkhovskiy.processmanager.ProcessDefinitionRegistry;
import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessManager;
import dev.verkhovskiy.processmanager.ProcessOperator;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessInspector;
import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import dev.verkhovskiy.processmanager.runtime.NoopProcessManagerMetrics;
import dev.verkhovskiy.processmanager.runtime.PostgresProcessManager;
import dev.verkhovskiy.processmanager.runtime.PostgresProcessOperator;
import dev.verkhovskiy.processmanager.runtime.ProcessDeadlineWatchdog;
import dev.verkhovskiy.processmanager.runtime.ProcessManagerMetrics;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Автоконфигурация Spring Boot для process-manager. */
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
  @ConditionalOnBean({PostgresProcessRepository.class, ObjectMapper.class})
  ProcessInspector processInspector(
      PostgresProcessRepository processRepository, ObjectMapper objectMapper) {
    return new PostgresProcessInspector(processRepository, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({PostgresProcessRepository.class, ProcessCommandScheduler.class})
  ProcessManager processManager(
      ProcessDefinitionRegistry definitionRegistry,
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ObjectMapper objectMapper,
      ObjectProvider<ProcessManagerMetrics> metrics) {
    return new PostgresProcessManager(
        definitionRegistry,
        processRepository,
        commandScheduler,
        objectMapper,
        metrics.getIfAvailable(() -> NoopProcessManagerMetrics.INSTANCE));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({PostgresProcessRepository.class, ProcessCommandScheduler.class})
  ProcessOperator processOperator(
      ProcessDefinitionRegistry definitionRegistry,
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ObjectMapper objectMapper,
      ObjectProvider<ProcessManagerMetrics> metrics) {
    return new PostgresProcessOperator(
        definitionRegistry,
        processRepository,
        commandScheduler,
        objectMapper,
        metrics.getIfAvailable(() -> NoopProcessManagerMetrics.INSTANCE));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({PostgresProcessRepository.class, ProcessCommandScheduler.class})
  ProcessDeadlineWatchdog processDeadlineWatchdog(
      PostgresProcessRepository processRepository,
      ProcessCommandScheduler commandScheduler,
      ProcessManagerProperties properties,
      ObjectProvider<ProcessManagerMetrics> metrics) {
    return new ProcessDeadlineWatchdog(
        processRepository,
        commandScheduler,
        properties.getDeadlineBatchSize(),
        metrics.getIfAvailable(() -> NoopProcessManagerMetrics.INSTANCE));
  }
}
