package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * How many lesson parts have covered one concept of one handout for one student. LessonPlanner uses it so a long
 * handout is taught part by part: every concept gets its turn before any concept is repeated.
 * Keyed by scope (the student's handout), like every other piece of learner state.
 */
@Entity
@Table(name = "lesson_coverage",
        uniqueConstraints = @UniqueConstraint(name = "uq_lesson_coverage", columnNames = {"scope_id", "concept_key"}))
public class LessonCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "concept_key", nullable = false, length = 80)
    private String conceptKey;

    @Column(name = "times_taught", nullable = false)
    private int timesTaught;

    @Column(name = "last_taught_at", nullable = false)
    private LocalDateTime lastTaughtAt = LocalDateTime.now();

    public LessonCoverage() {}

    public LessonCoverage(Long scopeId, String conceptKey) {
        this.scopeId = scopeId;
        this.conceptKey = conceptKey;
    }

    public Long getScopeId() { return scopeId; }
    public String getConceptKey() { return conceptKey; }
    public int getTimesTaught() { return timesTaught; }

    public void markTaught() {
        this.timesTaught++;
        this.lastTaughtAt = LocalDateTime.now();
    }
}
