package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    /** Case-insensitive user lookup by email. */
    Optional<User> findByEmailIgnoreCase(String email);
}