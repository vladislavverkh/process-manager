package dev.verkhovskiy.processmanager.sample.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProcessManagerJacksonConfiguration {

  @Bean
  ObjectMapper processManagerObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
