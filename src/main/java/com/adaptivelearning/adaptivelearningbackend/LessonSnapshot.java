package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A generated lesson, valid for ONE (handout, tier, learner-state) combination. Replaces LessonCache, whose key was
 * (student, topic, tier): it served a lesson written for old mistakes after those mistakes had changed, and it carried
 * over to a different handout with the same name. The state hash comes from LearnerPrompt.stateHash(...), so a lesson
 * is reused until the open weaknesses / slips / recovered concepts it describes actually change.
 */
@Entity
@Table(name = "lesson_snapshot", indexes = {
        @Index(name = "idx_lesson_snap", columnList = "scope_id, tier, state_hash")
})
public class LessonSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false, length = 12)
    private String tier;

    @Column(name = "state_hash", nullable = false, length = 40)
    private String stateHash;

    @Column(name = "prompt_version", length = 24)
    private String promptVersion;

    @Column(name = "lesson_score")
    private double lessonScore;

    @Column(name = "content_json", columnDefinition = "TEXT", nullable = false)
    private String contentJson;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();

    public LessonSnapshot() {}

    public LessonSnapshot(Long scopeId, String studentId, String topic, String tier, String stateHash,
                          String promptVersion, double lessonScore, String contentJson) {
        this.scopeId = scopeId; this.studentId = studentId; this.topic = topic; this.tier = tier;
        this.stateHash = stateHash; this.promptVersion = promptVersion; this.lessonScore = lessonScore;
        this.contentJson = contentJson;
    }

    public Long getId() { return id; }
    public Long getScopeId() { return scopeId; }
    public String getStudentId() { return studentId; }
    public String getTopic() { return topic; }
    public String getTier() { return tier; }
    public String getStateHash() { return stateHash; }
    public String getPromptVersion() { return promptVersion; }
    public double getLessonScore() { return lessonScore; }
    public String getContentJson() { return contentJson; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
}
