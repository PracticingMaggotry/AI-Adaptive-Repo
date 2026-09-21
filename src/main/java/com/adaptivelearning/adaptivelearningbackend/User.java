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

    /** Marks this account as an admin account. */
    @Column(name = "is_admin")
    private boolean admin = false;

    /** The IP address this account most recently logged in from. */
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
}