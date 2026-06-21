package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A single banned IP address, enforced server-side by {@link IpBlockFilter}
 * on every incoming request.
 *
 * Previously, "blocked" IPs only ever lived in the admin's own browser
 * localStorage (admin.html's old addBlockedIP()) — nothing on the server
 * ever checked them, so the feature was purely cosmetic record-keeping.
 * This entity is the real, persisted ban list that IpBlockFilter actually
 * reads from.
 */
@Entity
@Table(name = "blocked_ips", uniqueConstraints = @UniqueConstraint(columnNames = "ip"))
public class BlockedIp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String ip;

    @Column(length = 500)
    private String reason;

    /** Optional — the account this block was issued against, for reference only. */
    private String email;

    /** Optional — display name to go with email, for reference only. */
    private String name;

    private LocalDateTime blockedAt;

    public BlockedIp() {}

    public BlockedIp(String ip, String reason, String email, String name) {
        this.ip = ip;
        this.reason = (reason == null || reason.isBlank()) ? "No reason given" : reason;
        this.email = email;
        this.name = name;
        this.blockedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getIp() { return ip; }
    public String getReason() { return reason; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public LocalDateTime getBlockedAt() { return blockedAt; }

    public void setId(Long id) { this.id = id; }
    public void setIp(String ip) { this.ip = ip; }
    public void setReason(String reason) { this.reason = reason; }
    public void setEmail(String email) { this.email = email; }
    public void setName(String name) { this.name = name; }
    public void setBlockedAt(LocalDateTime blockedAt) { this.blockedAt = blockedAt; }
}