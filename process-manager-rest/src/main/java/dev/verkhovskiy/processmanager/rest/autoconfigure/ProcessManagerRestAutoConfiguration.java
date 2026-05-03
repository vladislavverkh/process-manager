package dev.verkhovskiy.processmanager.rest.autoconfigure;

import dev.verkhovskiy.processmanager.ProcessInspector;
import dev.verkhovskiy.processmanager.ProcessOperator;
import dev.verkhovskiy.processmanager.rest.ProcessOperatorController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

/** Автоконфигурация REST API оператора process-manager. */
@AutoConfiguration
@ConditionalOnClass(RestController.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(
    prefix = "process.manager.rest",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(ProcessManagerRestProperties.class)
public class ProcessManagerRestAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({ProcessInspector.class, ProcessOperator.class})
  ProcessOperatorController processOperatorController(
      ProcessInspector processInspector, ProcessOperator processOperator) {
    return new ProcessOperatorController(processInspector, processOperator);
  }
}
