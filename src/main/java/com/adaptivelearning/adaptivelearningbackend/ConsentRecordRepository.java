package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {
    boolean existsByParticipantCodeAndVersion(String participantCode, String version);

    /** Removes every consent version this participant accepted; returns how many rows were deleted. */
    @Transactional
    long deleteByParticipantCode(String participantCode);
}
