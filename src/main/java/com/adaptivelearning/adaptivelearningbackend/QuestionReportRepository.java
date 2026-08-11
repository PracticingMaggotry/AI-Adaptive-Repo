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

    /** Deletes all reports for a topic (used on topic deletion). */
    @Query("DELETE FROM QuestionReport r WHERE LOWER(r.topic) = LOWER(:topic)")
    @org.springframework.data.jpa.repository.Modifying
    @jakarta.transaction.Transactional
    void deleteByTopicIgnoreCase(@Param("topic") String topic);

    /** Deletes all reports for one question. */
    @jakarta.transaction.Transactional
    void deleteByQuestionId(Long questionId);

    /** Batch delete by exact question IDs — scoped to one student's deletion, unlike {@link #deleteByTopicIgnoreCase}. */
    @org.springframework.data.jpa.repository.Modifying
    @jakarta.transaction.Transactional
    void deleteByQuestionIdIn(java.util.List<Long> questionIds);
}