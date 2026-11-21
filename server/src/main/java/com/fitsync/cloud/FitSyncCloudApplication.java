package com.fitsync.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FitSyncCloudApplication {
  public static void main(String[] args) {
    SpringApplication.run(FitSyncCloudApplication.class, args);
  }
}
