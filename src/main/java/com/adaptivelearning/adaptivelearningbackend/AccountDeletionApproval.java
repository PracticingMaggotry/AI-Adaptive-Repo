package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One admin's vote on an {@link AccountDeletionRequest}. One vote per admin per request. */
@Entity
@Table(
        name = "account_deletion_approvals",
        uniqueConstraints = @UniqueConstraint(name = "uq_vote_per_admin", columnNames = {"request_id", "admin_email"})
)
public class AccountDeletionApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "admin_email", nullable = false, length = 255)
    private String adminEmail;

    /** APPROVE | REJECT */
    @Column(nullable = false, length = 10)
    private String decision;

    @Column(length = 500)
    private String note;

    private LocalDateTime decidedAt;

    public AccountDeletionApproval() {}

    public AccountDeletionApproval(Long requestId, String adminEmail, String decision, String note) {
        this.requestId = requestId;
        this.adminEmail = adminEmail;
        this.decision = decision;
        this.note = note;
        this.decidedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getRequestId() { return requestId; }
    public String getAdminEmail() { return adminEmail; }
    public String getDecision() { return decision; }
    public String getNote() { return note; }
    public LocalDateTime getDecidedAt() { return decidedAt; }

    public void setId(Long id) { this.id = id; }
    public void setRequestId(Long v) { this.requestId = v; }
    public void setAdminEmail(String v) { this.adminEmail = v; }
    public void setDecision(String v) { this.decision = v; }
    public void setNote(String v) { this.note = v; }
    public void setDecidedAt(LocalDateTime v) { this.decidedAt = v; }
}