package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A student's report of an AI-generated question with a quality problem
 * QuestionValidator can't catch (structurally valid but wrong content).
 *
 * One row per (reporter, questionId) — a student reports a question once,
 * but multiple students can each report the same one, giving admins a
 * vote-count signal for triage.
 */
@Entity
@Table(
        name = "question_reports",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_report_per_student",
                columnNames = {"question_id", "reporter_email"}
        )
)
public class QuestionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "reporter_email", nullable = false, length = 255)
    private String reporterEmail;

    /** Denormalised for easy admin filtering. */
    @Column(name = "topic", length = 200)
    private String topic;

    /** First 300 chars of the question, for quick display. */
    @Column(name = "question_text", columnDefinition = "TEXT")
    private String questionText;

    /** WRONG_ANSWER | MISLEADING | OUT_OF_SCOPE | DUPLICATE | OTHER */
    @Column(name = "reason", nullable = false, length = 40)
    private String reason;

    @Column(name = "notes", length = 1000)
    private String notes;

    /** PENDING | FIXED | DELETED | DISMISSED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "admin_note", length = 500)
    private String adminNote;

    public QuestionReport() {}

    public QuestionReport(Long questionId, String reporterEmail, String topic,
                          String questionText, String reason, String notes) {
        this.questionId    = questionId;
        this.reporterEmail = reporterEmail;
        this.topic         = topic;
        this.questionText  = questionText != null && questionText.length() > 300
                ? questionText.substring(0, 300) : questionText;
        this.reason        = reason;
        this.notes         = notes != null && notes.length() > 1000
                ? notes.substring(0, 1000) : notes;
        this.status        = "PENDING";
        this.reportedAt    = LocalDateTime.now();
    }

    public Long getId()              { return id; }
    public Long getQuestionId()      { return questionId; }
    public String getReporterEmail() { return reporterEmail; }
    public String getTopic()         { return topic; }
    public String getQuestionText()  { return questionText; }
    public String getReason()        { return reason; }
    public String getNotes()         { return notes; }
    public String getStatus()        { return status; }
    public LocalDateTime getReportedAt()  { return reportedAt; }
    public LocalDateTime getReviewedAt()  { return reviewedAt; }
    public String getReviewedBy()    { return reviewedBy; }
    public String getAdminNote()     { return adminNote; }

    public void setId(Long id)                         { this.id = id; }
    public void setQuestionId(Long questionId)         { this.questionId = questionId; }
    public void setReporterEmail(String reporterEmail) { this.reporterEmail = reporterEmail; }
    public void setTopic(String topic)                 { this.topic = topic; }
    public void setQuestionText(String questionText)   { this.questionText = questionText; }
    public void setReason(String reason)               { this.reason = reason; }
    public void setNotes(String notes)                 { this.notes = notes; }
    public void setStatus(String status)               { this.status = status; }
    public void setReportedAt(LocalDateTime t)         { this.reportedAt = t; }
    public void setReviewedAt(LocalDateTime t)         { this.reviewedAt = t; }
    public void setReviewedBy(String reviewedBy)       { this.reviewedBy = reviewedBy; }
    public void setAdminNote(String adminNote)         { this.adminNote = adminNote; }
}