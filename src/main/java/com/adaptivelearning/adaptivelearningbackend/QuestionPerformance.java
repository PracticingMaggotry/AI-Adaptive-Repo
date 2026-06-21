package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "question_performance")
public class QuestionPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(nullable = false)
    private String topic;

    /** One of: Terminology, Computation, Application, Analysis, Process Steps */
    @Column(nullable = false)
    private String category;

    /** "correct", "wrong", or "partial" (partial = graded essay scoring 50-74) */
    @Column(nullable = false)
    private String result;

    /** 0-100 AI-assigned score, only set for ESSAY questions. Null otherwise. */
    @Column(name = "essay_score")
    private Double essayScore;

    private LocalDateTime timestamp;

    public QuestionPerformance() {}

    public QuestionPerformance(String studentId, String topic, String category,
                               String result, Double essayScore, LocalDateTime timestamp) {
        this.studentId = studentId;
        this.topic = topic;
        this.category = category;
        this.result = result;
        this.essayScore = essayScore;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getTopic() { return topic; }
    public String getCategory() { return category; }
    public String getResult() { return result; }
    public Double getEssayScore() { return essayScore; }
    public LocalDateTime getTimestamp() { return timestamp; }

    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setTopic(String topic) { this.topic = topic; }
    public void setCategory(String category) { this.category = category; }
    public void setResult(String result) { this.result = result; }
    public void setEssayScore(Double essayScore) { this.essayScore = essayScore; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}