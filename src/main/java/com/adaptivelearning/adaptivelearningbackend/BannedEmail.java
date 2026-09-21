package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A banned email address, enforced at registration, at login, and on every authenticated request. Replaces the old IP block list. */
@Entity
@Table(name = "banned_emails", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class BannedEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(length = 500)
    private String reason;

    private String bannedBy;
    private LocalDateTime bannedAt;

    public BannedEmail() {}

    public BannedEmail(String email, String reason, String bannedBy) {
        this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
        this.reason = (reason == null || reason.isBlank()) ? "No reason given" : reason;
        this.bannedBy = bannedBy;
        this.bannedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getReason() { return reason; }
    public String getBannedBy() { return bannedBy; }
    public LocalDateTime getBannedAt() { return bannedAt; }

    public void setId(Long id) { this.id = id; }
    public void setEmail(String email) { this.email = email; }
    public void setReason(String reason) { this.reason = reason; }
    public void setBannedBy(String bannedBy) { this.bannedBy = bannedBy; }
    public void setBannedAt(LocalDateTime bannedAt) { this.bannedAt = bannedAt; }
}