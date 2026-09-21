package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BannedEmailRepository extends JpaRepository<BannedEmail, Long> {
    boolean existsByEmailIgnoreCase(String email);

    @Transactional
    void deleteByEmailIgnoreCase(String email);
}