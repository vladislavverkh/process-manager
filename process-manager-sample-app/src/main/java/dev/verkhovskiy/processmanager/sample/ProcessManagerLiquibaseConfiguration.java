package dev.verkhovskiy.processmanager.sample;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
public class ProcessManagerLiquibaseConfiguration {

  static final String LIQUIBASE_BEAN_NAME = "processManagerLiquibase";
  static final String SAMPLE_LIQUIBASE_BEAN_NAME = "sampleAppLiquibase";

  @Bean(LIQUIBASE_BEAN_NAME)
  SpringLiquibase processManagerLiquibase(DataSource dataSource) {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog("classpath:db/changelog/process-manager.postgres.sql");
    return liquibase;
  }

  @Bean(SAMPLE_LIQUIBASE_BEAN_NAME)
  @DependsOn(LIQUIBASE_BEAN_NAME)
  SpringLiquibase sampleAppLiquibase(DataSource dataSource) {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog("classpath:db/changelog/sample-app.postgres.sql");
    return liquibase;
  }
}
