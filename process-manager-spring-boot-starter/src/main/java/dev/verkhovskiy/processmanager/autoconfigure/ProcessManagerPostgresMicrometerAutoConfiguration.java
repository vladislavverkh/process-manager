package dev.verkhovskiy.processmanager.autoconfigure;

import dev.verkhovskiy.processmanager.postgres.PostgresProcessRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** PostgreSQL-backed gauges for process-manager runtime state. */
@AutoConfiguration(
    after = ProcessManagerAutoConfiguration.class,
    afterName = {
      "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration"
    })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean({MeterRegistry.class, PostgresProcessRepository.class})
public class ProcessManagerPostgresMicrometerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ProcessManagerPostgresMicrometerMeters processManagerPostgresMicrometerMeters(
      MeterRegistry meterRegistry, PostgresProcessRepository processRepository) {
    return new ProcessManagerPostgresMicrometerMeters(meterRegistry, processRepository);
  }
}
