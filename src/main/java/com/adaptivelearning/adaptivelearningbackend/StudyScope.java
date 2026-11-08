package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One student's study of ONE specific handout. All learner state (responses, mastery, lessons,
 * baseline) hangs off a
 * scope, not off the topic NAME — so uploading a different handout under a name that already exists
 * starts clean
 * instead of inheriting the old handout's mistakes, baseline and lessons.
 *
 * A new scope is opened when the handout's content hash changes. Re-uploading identical content
 * keeps the scope.
 * Superseded or deleted scopes are kept for history and never read for the current handout.
 */
@Entity
@Table(name = "study_scope", indexes = {
        @Index(name = "idx_scope_student_topic", columnList = "student_id, topic_key, status")
})
public class StudyScope {

    public static final String ACTIVE = "ACTIVE", SUPERSEDED = "SUPERSEDED", DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    /** Display name as the student typed it. */
    @Column(nullable = false)
    private String topic;

    /** Lower-cased, trimmed topic used for lookups. */
    @Column(name = "topic_key", nullable = false)
    private String topicKey;

    @Column(name = "content_hash", length = 80)
    private String contentHash;

    /** JSON array [{"key","term","definition"}] built from the handout's knowledge extract, in handout order. */
    @Column(name = "concepts_json", columnDefinition = "TEXT")
    private String conceptsJson;

    @Column(name = "word_count")
    private Integer wordCount;

    @Column(nullable = false, length = 12)
    private String status = ACTIVE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "superseded_by")
    private Long supersededBy;

    /** Set when concept-level evidence was carried over from an earlier version of the same handout. */
    @Column(name = "carried_from_scope_id")
    private Long carriedFromScopeId;

    /** The locked first-quiz result of this handout, archived here when the scope closes (see FirstQuizResult). */
    @Column(name = "baseline_score")
    private Double baselineScore;
    @Column(name = "baseline_tier", length = 12)
    private String baselineTier;

    /** True once attempts recorded before scopes existed have been imported as responses. */
    @Column(name = "legacy_imported", nullable = false)
    private boolean legacyImported = false;

    public StudyScope() {}

    public StudyScope(String studentId, String topic, String contentHash, String conceptsJson, Integer wordCount) {
        this.studentId = studentId;
        this.topic = topic;
        this.topicKey = keyOf(topic);
        this.contentHash = contentHash;
        this.conceptsJson = conceptsJson;
        this.wordCount = wordCount;
    }

    public static String keyOf(String topic) { return topic == null ? "" : topic.trim().toLowerCase(java.util.Locale.ROOT); }

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getTopic() { return topic; }
    public String getTopicKey() { return topicKey; }
    public String getContentHash() { return contentHash; }
    public String getConceptsJson() { return conceptsJson; }
    public Integer getWordCount() { return wordCount; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public Long getSupersededBy() { return supersededBy; }
    public Long getCarriedFromScopeId() { return carriedFromScopeId; }
    public Double getBaselineScore() { return baselineScore; }
    public String getBaselineTier() { return baselineTier; }
    public boolean isLegacyImported() { return legacyImported; }

    public void setContentHash(String v) { this.contentHash = v; }
    public void setConceptsJson(String v) { this.conceptsJson = v; }
    public void setWordCount(Integer v) { this.wordCount = v; }
    public void setStatus(String v) { this.status = v; }
    public void setEndedAt(LocalDateTime v) { this.endedAt = v; }
    public void setSupersededBy(Long v) { this.supersededBy = v; }
    public void setCarriedFromScopeId(Long v) { this.carriedFromScopeId = v; }
    public void setBaselineScore(Double v) { this.baselineScore = v; }
    public void setBaselineTier(String v) { this.baselineTier = v; }
    public void setLegacyImported(boolean v) { this.legacyImported = v; }
}
