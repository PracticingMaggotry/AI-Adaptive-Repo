package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ParticipantCodeRepository extends JpaRepository<ParticipantCode, Long> {
    Optional<ParticipantCode> findByEmailIgnoreCase(String email);
}
