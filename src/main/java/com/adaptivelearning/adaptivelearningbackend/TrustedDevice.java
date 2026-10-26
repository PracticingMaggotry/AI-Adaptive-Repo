package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A browser that completed the emailed login code. Only the SHA-256 of its cookie token is stored. */
@Entity
@Table(name = "trusted_devices",
        indexes = @Index(name = "idx_trusted_device_user", columnList = "user_email"),
        uniqueConstraints = @UniqueConstraint(name = "uq_trusted_device_hash", columnNames = {"token_hash"}))
public class TrustedDevice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_used_at", nullable = false)
    private LocalDateTime lastUsedAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public TrustedDevice() {}
    public TrustedDevice(String userEmail, String tokenHash, LocalDateTime expiresAt) {
        this.userEmail = userEmail; this.tokenHash = tokenHash; this.expiresAt = expiresAt;
    }
    public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }
    public void touch() { this.lastUsedAt = LocalDateTime.now(); }
}
