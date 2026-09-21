package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A proposal to permanently delete a user account — requires every current admin to approve (dual control). */
@Entity
@Table(name = "account_deletion_requests")
public class AccountDeletionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String targetEmail;
    private String targetName;

    @Column(length = 1000)
    private String reason;

    private String requestedBy;
    private LocalDateTime requestedAt;

    /** PENDING | APPROVED | REJECTED | EXECUTED | CANCELLED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    private String executedBy;
    private LocalDateTime executedAt;

    public AccountDeletionRequest() {}

    public AccountDeletionRequest(String targetEmail, String targetName, String reason, String requestedBy) {
        this.targetEmail = targetEmail;
        this.targetName = targetName;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getTargetEmail() { return targetEmail; }
    public String getTargetName() { return targetName; }
    public String getReason() { return reason; }
    public String getRequestedBy() { return requestedBy; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public String getStatus() { return status; }
    public String getExecutedBy() { return executedBy; }
    public LocalDateTime getExecutedAt() { return executedAt; }

    public void setId(Long id) { this.id = id; }
    public void setTargetEmail(String v) { this.targetEmail = v; }
    public void setTargetName(String v) { this.targetName = v; }
    public void setReason(String v) { this.reason = v; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public void setRequestedAt(LocalDateTime v) { this.requestedAt = v; }
    public void setStatus(String v) { this.status = v; }
    public void setExecutedBy(String v) { this.executedBy = v; }
    public void setExecutedAt(LocalDateTime v) { this.executedAt = v; }
}