package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Server-side storage for the Learning Hub's per-topic notepad
 * (learninghub.html's floating notepad widget).
 *
 * This used to live entirely in the browser's localStorage, keyed by
 * `notepad_${email}_${topic}`. Every other piece of per-student/per-admin
 * state in this app (blocked IPs, activity log, flags, quiz history,
 * uploaded materials...) was deliberately migrated server-side specifically
 * because localStorage doesn't survive a cleared browser, private/incognito
 * mode, or switching devices — see the rationale in AdminActivityLog /
 * BlockedIp's javadocs, which describe the exact same problem being fixed
 * for admin-side state. Notes were the one feature nobody got around to
 * moving. This entity closes that gap using the same one-row-per-student
 * shape as {@link FirstQuizResult} / {@link LessonCache}.
 *
 * One row per (studentId, topic). Overwritten in place on every save —
 * no revision history, matching the simple "last write wins" behavior the
 * old localStorage version already had.
 */
@Entity
@Table(
        name = "topic_notes",
        uniqueConstraints = @UniqueConstraint(name = "uq_topic_note", columnNames = {"student_id", "topic"})
)
public class TopicNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(nullable = false)
    private String topic;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public TopicNote() {}

    public TopicNote(String studentId, String topic, String content) {
        this.studentId = studentId;
        this.topic = topic;
        this.content = content;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getTopic() { return topic; }
    public String getContent() { return content; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setTopic(String topic) { this.topic = topic; }
    public void setContent(String content) { this.content = content; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}