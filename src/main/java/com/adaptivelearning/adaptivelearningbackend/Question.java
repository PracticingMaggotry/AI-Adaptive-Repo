package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;

@Entity
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String topic;

    @Column(columnDefinition = "TEXT")
    private String difficulty;

    @Column(name = "question_text", columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "optiona", columnDefinition = "TEXT")
    private String optionA;

    @Column(name = "optionb", columnDefinition = "TEXT")
    private String optionB;

    @Column(name = "optionc", columnDefinition = "TEXT")
    private String optionC;

    @Column(name = "optiond", columnDefinition = "TEXT")
    private String optionD;

    @Column(name = "correct_answer", columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(columnDefinition = "TEXT")
    private String hint;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(nullable = false)
    private String type = "MCQ";

    @Column(columnDefinition = "TEXT")
    private String payload;

    // nullable=false: no Question row should ever lack an owner — three separate handlers
    // (QuizController.checkAnswer/submitQuiz, QuestionReportController.fileReport) used to
    // fail-open on a null ownerId, treating "unknown owner" as "accessible to anyone". Those
    // call sites are now fixed to fail closed, and this constraint backs that up at the
    // schema level so a future insert path can't quietly recreate the same hole.
    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    public Question() {
    }

    public Question(
            String topic,
            String difficulty,
            String questionText,
            String optionA,
            String optionB,
            String optionC,
            String optionD,
            String correctAnswer
    ) {
        this.topic = topic;
        this.difficulty = difficulty;
        this.questionText = questionText;
        this.optionA = optionA;
        this.optionB = optionB;
        this.optionC = optionC;
        this.optionD = optionD;
        this.correctAnswer = correctAnswer;
    }

    public Long getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public String getQuestionText() {
        return questionText;
    }

    public String getOptionA() {
        return optionA;
    }

    public String getOptionB() {
        return optionB;
    }

    public String getOptionC() {
        return optionC;
    }

    public String getOptionD() {
        return optionD;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public String getHint() {
        return hint;
    }

    public String getExplanation() {
        return explanation;
    }

    public String getType() { return type; }

    public String getPayload() { return payload; }

    public String getOwnerId() { return ownerId; }

    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public void setId(Long id) {
        this.id = id;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public void setOptionA(String optionA) {
        this.optionA = optionA;
    }

    public void setOptionB(String optionB) {
        this.optionB = optionB;
    }

    public void setOptionC(String optionC) {
        this.optionC = optionC;
    }

    public void setOptionD(String optionD) {
        this.optionD = optionD;
    }

    public void setCorrectAnswer(String correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public void setType(String type) { this.type = type; }

    public void setPayload(String payload) { this.payload = payload; }
}