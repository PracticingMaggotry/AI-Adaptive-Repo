package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface QuestionReportRepository extends JpaRepository<QuestionReport, Long> {

    /** All reports for admin review, newest first. */
    List<QuestionReport> findAllByOrderByReportedAtDesc();

    /** Pending-only subset for the badge count in the admin nav. */
    List<QuestionReport> findByStatusOrderByReportedAtDesc(String status);

    /** How many reports a single question has accumulated across all reporters. */
    long countByQuestionId(Long questionId);

    /** Whether this exact student has already reported this exact question. */
    boolean existsByQuestionIdAndReporterEmail(Long questionId, String reporterEmail);

    /** Existing report for this (question, reporter) pair — used for duplicate guard. */
    Optional<QuestionReport> findByQuestionIdAndReporterEmail(Long questionId, String reporterEmail);

    /** All reports for a specific topic (for the admin topic-detail view). */
    List<QuestionReport> findByTopicIgnoreCaseOrderByReportedAtDesc(String topic);

    /** All reports for a specific question. */
    List<QuestionReport> findByQuestionIdOrderByReportedAtDesc(Long questionId);

    /** Count of pending reports — used for the admin nav badge. */
    @Query("SELECT COUNT(r) FROM QuestionReport r WHERE r.status = 'PENDING'")
    long countPending();

    /** Delete all reports for questions belonging to a specific topic
     *  (called when a topic is deleted so orphan rows don't pile up). */
    @Query("DELETE FROM QuestionReport r WHERE LOWER(r.topic) = LOWER(:topic)")
    @org.springframework.data.jpa.repository.Modifying
    @jakarta.transaction.Transactional
    void deleteByTopicIgnoreCase(@Param("topic") String topic);

    /** Delete all reports for a specific question ID (when the question is deleted). */
    @jakarta.transaction.Transactional
    void deleteByQuestionId(Long questionId);

    /**
     * Batch delete reports for a specific set of question IDs. Used when a
     * single student deletes their own topic (or an admin clears AI-test-data)
     * — deleting reports scoped to that student's exact deleted question IDs,
     * rather than by topic name alone, since {@link #deleteByTopicIgnoreCase}
     * would incorrectly wipe out other students' pending reports on
     * different questions that merely share the same topic name.
     */
    @org.springframework.data.jpa.repository.Modifying
    @jakarta.transaction.Transactional
    void deleteByQuestionIdIn(java.util.List<Long> questionIds);
}