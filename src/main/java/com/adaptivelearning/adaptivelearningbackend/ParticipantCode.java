package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** The ONLY place a participant's identity is linked to their study code. Drop this table after the study. */
@Entity
@Table(name = "participant_codes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pc_email", columnNames = "email"),
                @UniqueConstraint(name = "uq_pc_code", columnNames = "code")
        })
public class ParticipantCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 20)
    private String code;

    private LocalDateTime createdAt = LocalDateTime.now();

    public ParticipantCode() {}
    public ParticipantCode(String email, String code) { this.email = email; this.code = code; }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getCode() { return code; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
