package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Profile("test")
public class TestAdminSeeder {

    @Bean
    CommandLineRunner seedLocalTestAdmin(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        return args -> {
            String email = "admin.test@example.com";

            if (userRepository.findByEmailIgnoreCase(email).isEmpty()) {
                User admin = new User(
                        email,
                        passwordEncoder.encode("LocalTestAdmin!2026"),
                        "Local Test Admin"
                );
                admin.setAdmin(true);
                userRepository.save(admin);

                System.out.println(
                        "TEST-ONLY ADMIN CREATED: " + email
                );
            }
        };
    }
}