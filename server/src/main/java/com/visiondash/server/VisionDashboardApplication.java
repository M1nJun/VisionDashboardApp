package com.visiondash.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VisionDashboardApplication {
    public static void main(String[] args) {
        SpringApplication.run(VisionDashboardApplication.class, args);
    }
}
