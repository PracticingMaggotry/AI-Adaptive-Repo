package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A single admin moderation action, persisted server-side for a shared activity log. */
@Entity
@Table(name = "admin_activity_log")
public class AdminActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Action type, e.g. "flag", "unflag", "delete", "promote", "delete-topic", "ban-email", "unban-email", "archive", "unarchive",
     *  "request-deletion", "deletion-approved", "deletion-rejected", "deletion-cancelled". (Legacy rows may still be "block-ip"/"unblock".) */
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