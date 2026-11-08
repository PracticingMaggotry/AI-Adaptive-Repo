package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AdaptiveLearningBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdaptiveLearningBackendApplication.class, args);
    }

}