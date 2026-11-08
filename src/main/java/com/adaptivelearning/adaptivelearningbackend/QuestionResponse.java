package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Append-only record of ONE graded answer, with a full snapshot of what was asked. This is the
 * permanent memory of the
 * AI layer. Because it is permanent:
 * <ul>
 *   <li>mistakes never fall out of a window and never lose their detail when the question is regenerated;</li>
 *   <li>every answer is tied to a concept, tier, purpose and time, so mastery can be replayed and decays with time;</li>
 *   <li>tier/type/credit survive question deletion, so "are Hard questions really harder?" stays measurable.</li>
 * </ul>
 * Like QuestionExposureLog this is a research dataset: topic deletion closes the scope (so nothing
 * is inherited) but
 * does not erase these rows. Account-level erasure follows the same policy as the exposure log.
 */
@Entity
@Table(name = "question_response", indexes = {
        @Index(name = "idx_qr_scope", columnList = "scope_id"),
        @Index(name = "idx_qr_student_topic", columnList = "student_id, topic"),
        @Index(name = "idx_qr_tier", columnList = "tier")
})
public class QuestionResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(nullable = false)
    private String topic;

    @Column(name = "attempt_id")
    private Long attemptId;

    /** Source question row; may no longer exist. The snapshot below is the record. */
    @Column(name = "question_id")
    private Long questionId;

    /** Null for questions that were not tagged (legacy or free-form): kept for research, ignored by the mastery model. */
    @Column(name = "concept_key", length = 120)
    private String conceptKey;

    /** "blueprint" when the server assigned the concept, "lexical" when matched afterwards by word overlap. */
    @Column(name = "concept_source", length = 12)
    private String conceptSource;

    @Column(name = "question_type", length = 20)
    private String questionType;

    /** Tier the quiz was played at (Easy/Medium/Hard/Targeted). */
    @Column(length = 12)
    private String tier;

    @Column(length = 24)
    private String purpose;

    @Column(name = "bloom_level")
    private Integer bloomLevel;

    /** Demand level 1-5 the question was planned at (null for older 3-tier questions); input to AbilityModel. */
    @Column(name = "demand_level")
    private Integer demandLevel;

    @Column(length = 40)
    private String category;

    /** 0..1 credit (partial credit for matching/sorting/essays). */
    @Column(nullable = false)
    private double credit;

    /** correct / partial / wrong */
    @Column(nullable = false, length = 10)
    private String result;

    @Column(name = "time_spent_seconds")
    private Integer timeSpentSeconds;

    @Column(name = "answered_at", nullable = false)
    private LocalDateTime answeredAt = LocalDateTime.now();

    @Column(name = "prompt_version", length = 24)
    private String promptVersion;

    @Column(name = "question_text", columnDefinition = "TEXT")
    private String questionText;
    @Column(name = "student_answer", columnDefinition = "TEXT")
    private String studentAnswer;
    @Column(name = "correct_answer", columnDefinition = "TEXT")
    private String correctAnswer;
    @Column(columnDefinition = "TEXT")
    private String explanation;
    /** Structure of the question (pairs, blanks, clues...). Stored for wrong/partial answers only, to bound size. */
    @Column(columnDefinition = "TEXT")
    private String payload;
    @Column(name = "missed_points", columnDefinition = "TEXT")
    private String missedPoints;
    @Column(columnDefinition = "TEXT")
    private String feedback;

    public QuestionResponse() {}

    public Long getId() { return id; }
    public Long getScopeId() { return scopeId; }
    public String getStudentId() { return studentId; }
    public String getTopic() { return topic; }
    public Long getAttemptId() { return attemptId; }
    public Long getQuestionId() { return questionId; }
    public String getConceptKey() { return conceptKey; }
    public String getConceptSource() { return conceptSource; }
    public String getQuestionType() { return questionType; }
    public String getTier() { return tier; }
    public String getPurpose() { return purpose; }
    public Integer getBloomLevel() { return bloomLevel; }
    public Integer getDemandLevel() { return demandLevel; }
    public String getCategory() { return category; }
    public double getCredit() { return credit; }
    public String getResult() { return result; }
    public Integer getTimeSpentSeconds() { return timeSpentSeconds; }
    public LocalDateTime getAnsweredAt() { return answeredAt; }
    public String getPromptVersion() { return promptVersion; }
    public String getQuestionText() { return questionText; }
    public String getStudentAnswer() { return studentAnswer; }
    public String getCorrectAnswer() { return correctAnswer; }
    public String getExplanation() { return explanation; }
    public String getPayload() { return payload; }
    public String getMissedPoints() { return missedPoints; }
    public String getFeedback() { return feedback; }

    public void setScopeId(Long v) { this.scopeId = v; }
    public void setStudentId(String v) { this.studentId = v; }
    public void setTopic(String v) { this.topic = v; }
    public void setAttemptId(Long v) { this.attemptId = v; }
    public void setQuestionId(Long v) { this.questionId = v; }
    public void setConceptKey(String v) { this.conceptKey = v; }
    public void setConceptSource(String v) { this.conceptSource = v; }
    public void setQuestionType(String v) { this.questionType = v; }
    public void setTier(String v) { this.tier = v; }
    public void setPurpose(String v) { this.purpose = v; }
    public void setBloomLevel(Integer v) { this.bloomLevel = v; }
    public void setDemandLevel(Integer v) { this.demandLevel = v; }
    public void setCategory(String v) { this.category = v; }
    public void setCredit(double v) { this.credit = v; }
    public void setResult(String v) { this.result = v; }
    public void setTimeSpentSeconds(Integer v) { this.timeSpentSeconds = v; }
    public void setAnsweredAt(LocalDateTime v) { this.answeredAt = v; }
    public void setPromptVersion(String v) { this.promptVersion = v; }
    public void setQuestionText(String v) { this.questionText = v; }
    public void setStudentAnswer(String v) { this.studentAnswer = v; }
    public void setCorrectAnswer(String v) { this.correctAnswer = v; }
    public void setExplanation(String v) { this.explanation = v; }
    public void setPayload(String v) { this.payload = v; }
    public void setMissedPoints(String v) { this.missedPoints = v; }
    public void setFeedback(String v) { this.feedback = v; }
}
