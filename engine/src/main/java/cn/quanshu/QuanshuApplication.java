package cn.quanshu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class QuanshuApplication {
  public static void main(String[] args) {
    SpringApplication.run(QuanshuApplication.class, args);
  }
}
