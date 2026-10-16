package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Append-only record of every question served to a student. Stores a full snapshot,
 * with no FK to questions, so it survives regeneration, topic deletion and archiving.
 * NEVER delete from this table (research dataset).
 */
@Entity
@Table(
        name = "question_exposure_log",
        indexes = {
                @Index(name = "idx_qel_student_topic", columnList = "student_id, topic"),
                @Index(name = "idx_qel_serve", columnList = "serve_id")
        }
)
public class QuestionExposureLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Groups all questions returned by one GET /api/quiz/questions call. */
    @Column(name = "serve_id", nullable = false, length = 36)
    private String serveId;

    @Column(name = "student_id", nullable = false, length = 255)
    private String studentId;

    @Column(nullable = false)
    private String topic;

    @Column(name = "difficulty_requested", length = 40)
    private String difficultyRequested;

    /** Difficulty of the question actually served (differs when the fallback chain kicked in). */
    @Column(name = "difficulty_served", length = 40)
    private String difficultyServed;

    @Column(name = "question_id")
    private Long questionId;

    /** Index of the question in the served list. */
    @Column(name = "position_in_set")
    private int positionInSet;

    @Column(name = "question_type", length = 20)
    private String questionType;

    @Column(name = "question_text", columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "option_a", columnDefinition = "TEXT")
    private String optionA;
    @Column(name = "option_b", columnDefinition = "TEXT")
    private String optionB;
    @Column(name = "option_c", columnDefinition = "TEXT")
    private String optionC;
    @Column(name = "option_d", columnDefinition = "TEXT")
    private String optionD;

    @Column(name = "correct_answer", columnDefinition = "TEXT")
    private String correctAnswer;

    /** Full type-specific payload incl. answer key (matching pairs, blanks, rubric, etc.). */
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "served_at", nullable = false)
    private LocalDateTime servedAt;

    public QuestionExposureLog() {}

    public QuestionExposureLog(String serveId, String studentId, String topic, String difficultyRequested,
                               Question q, int positionInSet) {
        this.serveId = serveId;
        this.studentId = studentId;
        this.topic = topic;
        this.difficultyRequested = difficultyRequested;
        this.difficultyServed = q.getDifficulty();
        this.questionId = q.getId();
        this.positionInSet = positionInSet;
        this.questionType = q.getType();
        this.questionText = q.getQuestionText();
        this.optionA = q.getOptionA();
        this.optionB = q.getOptionB();
        this.optionC = q.getOptionC();
        this.optionD = q.getOptionD();
        this.correctAnswer = q.getCorrectAnswer();
        this.payload = q.getPayload();
        this.servedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getServeId() { return serveId; }
    public String getStudentId() { return studentId; }
    public String getTopic() { return topic; }
    public String getDifficultyRequested() { return difficultyRequested; }
    public String getDifficultyServed() { return difficultyServed; }
    public Long getQuestionId() { return questionId; }
    public int getPositionInSet() { return positionInSet; }
    public String getQuestionType() { return questionType; }
    public String getQuestionText() { return questionText; }
    public String getOptionA() { return optionA; }
    public String getOptionB() { return optionB; }
    public String getOptionC() { return optionC; }
    public String getOptionD() { return optionD; }
    public String getCorrectAnswer() { return correctAnswer; }
    public String getPayload() { return payload; }
    public LocalDateTime getServedAt() { return servedAt; }
}