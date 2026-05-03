package dev.verkhovskiy.processmanager.sample.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SampleOpenApiConfiguration {

  @Bean
  OpenAPI sampleOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Process Manager Sample API")
                .version("0.0.1")
                .description("API примера обработки транзакции через process-manager."));
  }
}
