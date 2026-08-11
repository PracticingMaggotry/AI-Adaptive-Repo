package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Server-side storage for the Learning Hub's per-topic notepad. Previously
 * pure localStorage, which doesn't survive a cleared browser or device
 * switch — moved server-side like every other per-student state in this app.
 *
 * One row per (studentId, topic), overwritten in place on save (last write wins).
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