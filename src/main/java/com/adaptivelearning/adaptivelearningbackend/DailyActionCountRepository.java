package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/** JPA repository for per-day AI-action usage counters. */
public interface DailyActionCountRepository extends JpaRepository<DailyActionCount, Long> {

    /** Returns today's row for this (actionType, studentId), if any. */
    Optional<DailyActionCount> findByActionTypeAndStudentIdAndActionDate(
            String actionType, String studentId, LocalDate actionDate);

    /** Atomically increments count by 1 only if still below the limit; returns rows updated (0 or 1). */
    @Transactional
    @Modifying
    @Query("""
            UPDATE DailyActionCount d
               SET d.count = d.count + 1
             WHERE d.actionType  = :actionType
               AND d.studentId   = :studentId
               AND d.actionDate  = :actionDate
               AND d.count       < :limit
            """)
    int incrementIfBelow(
            @Param("actionType")  String    actionType,
            @Param("studentId")   String    studentId,
            @Param("actionDate")  LocalDate actionDate,
            @Param("limit")       int       limit);

    /** Deletes rows older than the given cutoff date. */
    @Transactional
    @Modifying
    void deleteByActionDateBefore(LocalDate cutoff);
}