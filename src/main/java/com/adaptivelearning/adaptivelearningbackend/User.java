package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String fullName;

    @Column(name = "is_admin")
    private boolean admin = false;

    @Column(name = "last_known_ip")
    private String lastKnownIp;

    /** Marks this account as flagged for manual admin review; flagging does not restrict access. */
    @Column(name = "flagged")
    private boolean flagged = false;

    @Column(name = "flag_reason", length = 500)
    private String flagReason;

    @Column(name = "flagged_at")
    private java.time.LocalDateTime flaggedAt;

    /** Suspension: account is kept intact but blocked from logging in / using the app until restored. */
    @Column(name = "archived")
    private boolean archived = false;

    @Column(name = "archive_reason", length = 500)
    private String archiveReason;

    @Column(name = "archived_at")
    private java.time.LocalDateTime archivedAt;

    @Column(name = "archived_by")
    private String archivedBy;

    /**
     * Whether to ask before an action replaces this student's question set (Adapted Quiz, Target Problems, re-uploading
     * a handout). Nullable on purpose: existing rows have no value, and null means "never chose", which is ON.
     */
    @Column(name = "warn_before_replace")
    private Boolean warnBeforeReplace;

    /** True once the user has self-requested deletion of their own account (pending admin review). */
    @Column(name = "deletion_requested")
    private boolean deletionRequested = false;

    @Column(name = "deletion_requested_at")
    private java.time.LocalDateTime deletionRequestedAt;

    /**
     * The user's real email, preserved here once self-requested deletion overwrites {@code email}
     * with a non-loginable placeholder. This frees the real address for immediate re-registration
     * (email is unique) without losing it for admin review — see AccountController.
     */
    @Column(name = "original_email")
    private String originalEmail;

    public User() {
    }

    public User(String email, String password, String fullName) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isAdmin() {
        return admin;
    }

    public String getLastKnownIp() {
        return lastKnownIp;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public String getFlagReason() {
        return flagReason;
    }

    public java.time.LocalDateTime getFlaggedAt() {
        return flaggedAt;
    }

    public boolean isArchived() { return archived; }
    public String getArchiveReason() { return archiveReason; }
    public java.time.LocalDateTime getArchivedAt() { return archivedAt; }
    public String getArchivedBy() { return archivedBy; }

    public void setArchived(boolean archived) { this.archived = archived; }
    public void setArchiveReason(String archiveReason) { this.archiveReason = archiveReason; }
    public void setArchivedAt(java.time.LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
    public void setArchivedBy(String archivedBy) { this.archivedBy = archivedBy; }

    public boolean isDeletionRequested() { return deletionRequested; }
    public boolean isWarnBeforeReplace() { return warnBeforeReplace == null || warnBeforeReplace; }
    public void setWarnBeforeReplace(boolean warnBeforeReplace) { this.warnBeforeReplace = warnBeforeReplace; }
    public java.time.LocalDateTime getDeletionRequestedAt() { return deletionRequestedAt; }
    public String getOriginalEmail() { return originalEmail; }

    public void setDeletionRequested(boolean deletionRequested) { this.deletionRequested = deletionRequested; }
    public void setDeletionRequestedAt(java.time.LocalDateTime deletionRequestedAt) { this.deletionRequestedAt = deletionRequestedAt; }
    public void setOriginalEmail(String originalEmail) { this.originalEmail = originalEmail; }

    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    public void setFlagReason(String flagReason) {
        this.flagReason = flagReason;
    }

    public void setFlaggedAt(java.time.LocalDateTime flaggedAt) {
        this.flaggedAt = flaggedAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public void setLastKnownIp(String lastKnownIp) {
        this.lastKnownIp = lastKnownIp;
    }


    /** AES-256-GCM encrypted Base32 TOTP secret ("v1:..."); null = no authenticator enrolled. */
    @Column(name = "admin_totp_secret_enc", length = 512)
    private String adminTotpSecretEnc;

    /** Last accepted TOTP time step; codes at or before it are rejected (replay protection). */
    @Column(name = "admin_totp_last_step")
    private Long adminTotpLastStep;

    /** Comma-separated SHA-256 hashes of the unused single-use recovery codes. */
    @Column(name = "admin_recovery_hashes", length = 1000)
    private String adminRecoveryHashes;

    @Column(name = "admin_mfa_enrolled_at")
    private java.time.LocalDateTime adminMfaEnrolledAt;

    public String getAdminTotpSecretEnc() { return adminTotpSecretEnc; }
    public void setAdminTotpSecretEnc(String v) { this.adminTotpSecretEnc = v; }
    public Long getAdminTotpLastStep() { return adminTotpLastStep; }
    public void setAdminTotpLastStep(Long v) { this.adminTotpLastStep = v; }
    public String getAdminRecoveryHashes() { return adminRecoveryHashes; }
    public void setAdminRecoveryHashes(String v) { this.adminRecoveryHashes = v; }
    public java.time.LocalDateTime getAdminMfaEnrolledAt() { return adminMfaEnrolledAt; }
    public void setAdminMfaEnrolledAt(java.time.LocalDateTime v) { this.adminMfaEnrolledAt = v; }
}
