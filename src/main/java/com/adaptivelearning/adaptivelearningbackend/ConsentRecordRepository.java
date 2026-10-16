package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {
    boolean existsByParticipantCodeAndVersion(String participantCode, String version);
}
