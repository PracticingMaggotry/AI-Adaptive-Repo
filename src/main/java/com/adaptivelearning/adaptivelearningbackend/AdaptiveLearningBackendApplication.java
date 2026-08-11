package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot application entry point. */
@SpringBootApplication
@EnableScheduling
public class AdaptiveLearningBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdaptiveLearningBackendApplication.class, args);
    }

}