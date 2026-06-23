package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A single admin moderation action (flag/unflag user, delete user, block/
 * unblock IP, promote to admin, delete topic, etc.), persisted server-side.
 *
 * Previously the Activity Log tab, the "Recently Deleted Accounts" table,
 * and the flagged-user list were all built from ONE browser's localStorage
 * (admin_activity_log / admin_flagged_users / admin_deleted_users in
 * admin.html). That meant:
 *   - A second admin on a different machine saw none of it — no record of
 *     who blocked which IP, who got flagged, who got deleted.
 *   - "Delete User" and "Flag User" had no server-side effect at all: the
 *     localStorage entry was the ONLY trace anything happened. A "deleted"
 *     account could still log in and use the platform completely normally.
 *
 * This entity is the real, shared record. Every admin action that matters
 * for accountability gets one row here, written by the server at the
 * moment the action actually takes effect — see AdminController.recordActivity.
 */
@Entity
@Table(name = "admin_activity_log")
public class AdminActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. "flag", "unflag", "delete", "block-ip", "unblock", "promote", "delete-topic" */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(length = 1000)
    private String detail;

    /** Email of the admin who performed the action, or null if unknown. */
    private String performedBy;

    private LocalDateTime timestamp;

    public AdminActivityLog() {}

    public AdminActivityLog(String type, String message, String detail, String performedBy) {
        this.type = type;
        this.message = message;
        this.detail = detail;
        this.performedBy = performedBy;
        this.timestamp = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public String getDetail() { return detail; }
    public String getPerformedBy() { return performedBy; }
    public LocalDateTime getTimestamp() { return timestamp; }

    public void setId(Long id) { this.id = id; }
    public void setType(String type) { this.type = type; }
    public void setMessage(String message) { this.message = message; }
    public void setDetail(String detail) { this.detail = detail; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}