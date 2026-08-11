package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "attempts")
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String studentId;
    private String topic;
    private String difficulty;
    private int totalItems;
    private int correctAnswers;
    private double performanceScore;
    private String nextDiff;
    private LocalDateTime timestamp;

    /** Full per-question breakdown (JSON array) for this attempt. */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    public Attempt() {
    }

    public Attempt(String studentId, String topic, String difficulty, int totalItems,
                   int correctAnswers, double performanceScore, String nextDiff,
                   LocalDateTime timestamp) {
        this.studentId = studentId;
        this.topic = topic;
        this.difficulty = difficulty;
        this.totalItems = totalItems;
        this.correctAnswers = correctAnswers;
        this.performanceScore = performanceScore;
        this.nextDiff = nextDiff;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getTopic() {
        return topic;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public int getCorrectAnswers() {
        return correctAnswers;
    }

    public double getPerformanceScore() {
        return performanceScore;
    }

    public String getNextDiff() {
        return nextDiff;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getDetails() {
        return details;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public void setTotalItems(int totalItems) {
        this.totalItems = totalItems;
    }

    public void setCorrectAnswers(int correctAnswers) {
        this.correctAnswers = correctAnswers;
    }

    public void setPerformanceScore(double performanceScore) {
        this.performanceScore = performanceScore;
    }

    public void setNextDiff(String nextDiff) {
        this.nextDiff = nextDiff;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}