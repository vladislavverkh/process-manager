package dev.verkhovskiy.processmanager.sample;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProcessManagerLiquibaseConfiguration {

  static final String LIQUIBASE_BEAN_NAME = "processManagerLiquibase";

  @Bean(LIQUIBASE_BEAN_NAME)
  SpringLiquibase processManagerLiquibase(DataSource dataSource) {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog("classpath:db/changelog/process-manager.postgres.sql");
    return liquibase;
  }
}
