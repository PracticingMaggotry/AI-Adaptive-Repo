package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_items")
public class KnowledgeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String topic;

    @Column(length = 300)
    private String subtopic;

    @Column(length = 300)
    private String keyTerm;

    @Column(length = 1600)
    private String explanation;

    @Column(length = 1000)
    private String studyTip;

    private String sourceFile;
    private String createdBy;
    private LocalDateTime createdAt;

    public KnowledgeItem() {}

    public KnowledgeItem(String topic, String subtopic, String keyTerm, String explanation, String studyTip, String sourceFile, String createdBy) {
        this.topic = topic;
        this.subtopic = subtopic;
        this.keyTerm = keyTerm;
        this.explanation = explanation;
        this.studyTip = studyTip;
        this.sourceFile = sourceFile;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getTopic() { return topic; }
    public String getSubtopic() { return subtopic; }
    public String getKeyTerm() { return keyTerm; }
    public String getExplanation() { return explanation; }
    public String getStudyTip() { return studyTip; }
    public String getSourceFile() { return sourceFile; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setTopic(String topic) { this.topic = topic; }
    public void setSubtopic(String subtopic) { this.subtopic = subtopic; }
    public void setKeyTerm(String keyTerm) { this.keyTerm = keyTerm; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public void setStudyTip(String studyTip) { this.studyTip = studyTip; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
