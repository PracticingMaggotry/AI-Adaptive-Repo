package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores the LOCKED first-ever general quiz score per (studentId, topic),
 * plus the most recent Adapted Quiz score/tier if one has been completed.
 *
 * Rules enforced by callers (QuizController):
 *   - generalScore is set ONCE, on the very first general quiz submission
 *     for this (studentId, topic). It is NEVER overwritten afterward,
 *     even if the student retakes the same quiz.
 *   - latestAdaptedScore / latestAdaptedTier are updated EVERY time the
 *     student completes an Adapted Quiz (isAdapted = true on submit).
 *   - Targeted Problems quiz submissions never touch this table at all.
 *
 * The Learning Hub lesson endpoint (/api/ai/lesson) reads this table to
 * decide which score/tier should drive lesson content generation.
 */
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

    /** Score (0-100) from the very first general quiz attempt. Locked after first write. */
    @Column(name = "general_score", nullable = false)
    private double generalScore;

    /** Difficulty the general quiz was taken at (usually "Easy"). */
    @Column(name = "general_difficulty")
    private String generalDifficulty;

    /** Score (0-100) from the most recently completed Adapted Quiz, or null if none yet. */
    @Column(name = "latest_adapted_score")
    private Double latestAdaptedScore;

    /** Tier ("Easy"/"Medium"/"Hard") of the most recently completed Adapted Quiz, or null. */
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