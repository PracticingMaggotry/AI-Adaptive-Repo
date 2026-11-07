package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * The untouched first quiz of a handout, kept so the next student who sends the same handout gets a copy instead of
 * a new AI generation. Valid only for a student who starts from nothing (no earlier answers, no carried-over
 * evidence), at the tier recorded here, written by the prompt version recorded here. The live questions of a student
 * are overwritten as they adapt, so they cannot serve as the source; this row can.
 */
@Entity
@Table(name = "baseline_templates",
        uniqueConstraints = @UniqueConstraint(name = "uq_baseline_template",
                columnNames = {"content_hash", "tier", "prompt_version"}))
public class BaselineTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content_hash", nullable = false, length = 80)
    private String contentHash;

    @Column(nullable = false, length = 12)
    private String tier;

    @Column(name = "prompt_version", nullable = false, length = 24)
    private String promptVersion;

    @Column(name = "questions_json", columnDefinition = "LONGTEXT", nullable = false)
    private String questionsJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public BaselineTemplate() {}

    public BaselineTemplate(String contentHash, String tier, String promptVersion, String questionsJson) {
        this.contentHash = contentHash;
        this.tier = tier;
        this.promptVersion = promptVersion;
        this.questionsJson = questionsJson;
    }

    public Long getId() { return id; }
    public String getContentHash() { return contentHash; }
    public String getTier() { return tier; }
    public String getPromptVersion() { return promptVersion; }
    public String getQuestionsJson() { return questionsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
