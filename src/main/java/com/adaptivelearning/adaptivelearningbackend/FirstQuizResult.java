package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Stores the locked first general quiz score and latest Adapted Quiz result per (studentId, topic). */
@Entity
@Table(
        name = "first_quiz_results",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "topic"})
)
public class FirstQuizResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(nullable = false)
    private String topic;

    /** Score (0-100) from the first general quiz attempt. */
    @Column(name = "general_score", nullable = false)
    private double generalScore;

    /** Difficulty the general quiz was taken at. */
    @Column(name = "general_difficulty")
    private String generalDifficulty;

    /** Score (0-100) from the most recently completed Adapted Quiz, or null if none yet. */
    @Column(name = "latest_adapted_score")
    private Double latestAdaptedScore;

    /** Tier of the most recently completed Adapted Quiz, or null. */
    @Column(name = "latest_adapted_tier")
    private String latestAdaptedTier;

    @Column(name = "attempted_at")
    private LocalDateTime attemptedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public FirstQuizResult() {}

    public FirstQuizResult(String studentId, String topic, double generalScore, String generalDifficulty) {
        this.studentId = studentId;
        this.topic = topic;
        this.generalScore = generalScore;
        this.generalDifficulty = generalDifficulty;
        this.attemptedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getTopic() { return topic; }
    public double getGeneralScore() { return generalScore; }
    public String getGeneralDifficulty() { return generalDifficulty; }
    public Double getLatestAdaptedScore() { return latestAdaptedScore; }
    public String getLatestAdaptedTier() { return latestAdaptedTier; }
    public LocalDateTime getAttemptedAt() { return attemptedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setTopic(String topic) { this.topic = topic; }
    public void setGeneralScore(double generalScore) { this.generalScore = generalScore; }
    public void setGeneralDifficulty(String generalDifficulty) { this.generalDifficulty = generalDifficulty; }
    public void setLatestAdaptedScore(Double latestAdaptedScore) { this.latestAdaptedScore = latestAdaptedScore; }
    public void setLatestAdaptedTier(String latestAdaptedTier) { this.latestAdaptedTier = latestAdaptedTier; }
    public void setAttemptedAt(LocalDateTime attemptedAt) { this.attemptedAt = attemptedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}