package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores generated lesson content so Claude is only called ONCE per
 * (studentId, topic, tier) combination.
 *
 * A new row is written when:
 *   1. The student opens a lesson for the first time after their first quiz.
 *   2. The student completes an Adapted Quiz that moves them to a new tier,
 *      because currentTier will differ from the cached tier.
 *
 * On all other opens (reloads, re-visits, retakes of the same difficulty)
 * the cached contentJson is returned immediately with zero Claude calls.
 */
@Entity
@Table(
        name = "lesson_cache",
        uniqueConstraints = @UniqueConstraint(columnNames = {"student_id", "topic", "tier"})
)
public class LessonCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(nullable = false)
    private String topic;

    /** "Easy", "Medium", or "Hard" */
    @Column(nullable = false)
    private String tier;

    /** The performance score that drove this lesson generation (0-100). */
    private double lessonScore;

    /** Full JSON string returned by Claude — stored as-is for fast retrieval. */
    @Column(name = "content_json", columnDefinition = "TEXT", nullable = false)
    private String contentJson;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    public LessonCache() {}

    public LessonCache(String studentId, String topic, String tier,
                       double lessonScore, String contentJson) {
        this.studentId   = studentId;
        this.topic       = topic;
        this.tier        = tier;
        this.lessonScore = lessonScore;
        this.contentJson = contentJson;
        this.generatedAt = LocalDateTime.now();
    }

    public Long getId()            { return id; }
    public String getStudentId()   { return studentId; }
    public String getTopic()       { return topic; }
    public String getTier()        { return tier; }
    public double getLessonScore() { return lessonScore; }
    public String getContentJson() { return contentJson; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }

    public void setId(Long id)                       { this.id = id; }
    public void setStudentId(String studentId)       { this.studentId = studentId; }
    public void setTopic(String topic)               { this.topic = topic; }
    public void setTier(String tier)                 { this.tier = tier; }
    public void setLessonScore(double lessonScore)   { this.lessonScore = lessonScore; }
    public void setContentJson(String contentJson)   { this.contentJson = contentJson; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
}