package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A handout uploaded by an admin FOR one study participant. Nothing is sent to Anthropic when it is
 * created: the participant must open it on the quiz page and press the send button, which runs the
 * normal upload pipeline (MaterialController#processUploadedContent) and flips status to PROCESSED.
 * Several rows may share one stored file (same handout assigned to many participants).
 */
@Entity
@Table(name = "assigned_handouts")
public class AssignedHandout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_email", nullable = false, length = 255)
    private String studentEmail;

    @Column(nullable = false)
    private String topic;

    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private Long sizeBytes;

    private String assignedBy;
    private LocalDateTime assignedAt;

    /** PENDING (not yet sent to Claude) | PROCESSED (quiz generated). */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    private LocalDateTime processedAt;

    public AssignedHandout() {}

    public AssignedHandout(String studentEmail, String topic, String originalFilename, String storedFilename,
                           String contentType, Long sizeBytes, String assignedBy) {
        this.studentEmail = studentEmail;
        this.topic = topic;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.assignedBy = assignedBy;
        this.assignedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getStudentEmail() { return studentEmail; }
    public String getTopic() { return topic; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStoredFilename() { return storedFilename; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getAssignedBy() { return assignedBy; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public String getStatus() { return status; }
    public LocalDateTime getProcessedAt() { return processedAt; }

    public void setId(Long id) { this.id = id; }
    public void setStudentEmail(String v) { this.studentEmail = v; }
    public void setTopic(String v) { this.topic = v; }
    public void setOriginalFilename(String v) { this.originalFilename = v; }
    public void setStoredFilename(String v) { this.storedFilename = v; }
    public void setContentType(String v) { this.contentType = v; }
    public void setSizeBytes(Long v) { this.sizeBytes = v; }
    public void setAssignedBy(String v) { this.assignedBy = v; }
    public void setAssignedAt(LocalDateTime v) { this.assignedAt = v; }
    public void setStatus(String v) { this.status = v; }
    public void setProcessedAt(LocalDateTime v) { this.processedAt = v; }
}
