package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AdaptiveLearningBackendApplication {

    // Runs when the class loads, before Spring or the DB pool start (and in @SpringBootTest too).
    static {
        AppTime.install();
    }

    public static void main(String[] args) {
        SpringApplication.run(AdaptiveLearningBackendApplication.class, args);
    }

}