package dev.verkhovskiy.processmanager.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SampleProcessManagerApplication {

  public static void main(String[] args) {
    SpringApplication.run(SampleProcessManagerApplication.class, args);
  }
}
