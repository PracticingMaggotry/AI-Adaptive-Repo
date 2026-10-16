package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SusResponseRepository extends JpaRepository<SusResponse, Long> {
    boolean existsByParticipantCode(String participantCode);
}
