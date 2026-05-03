package dev.verkhovskiy.processmanager.autoconfigure;

import dev.verkhovskiy.processmanager.runtime.ProcessManagerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Micrometer integration for process-manager runtime metrics. */
@AutoConfiguration(
    before = ProcessManagerAutoConfiguration.class,
    afterName = {
      "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration"
    })
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
public class ProcessManagerMicrometerAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ProcessManagerMetrics processManagerMetrics(MeterRegistry meterRegistry) {
    return new MicrometerProcessManagerMetrics(meterRegistry);
  }
}
