package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Append-only record that a participant accepted a consent notice version. Keyed by code, not email. */
@Entity
@Table(name = "consent_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_consent", columnNames = {"participant_code", "version"}))
public class ConsentRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_code", nullable = false, length = 20)
    private String participantCode;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt = LocalDateTime.now();

    public ConsentRecord() {}
    public ConsentRecord(String participantCode, String version) {
        this.participantCode = participantCode; this.version = version;
    }
    public String getParticipantCode() { return participantCode; }
    public String getVersion() { return version; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
}
