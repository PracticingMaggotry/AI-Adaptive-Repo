package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;


public interface DailyActionCountRepository extends JpaRepository<DailyActionCount, Long> {

    /** Returns the existing row for this (actionType, studentId, today), if any. */
    Optional<DailyActionCount> findByActionTypeAndStudentIdAndActionDate(
            String actionType, String studentId, LocalDate actionDate);

    /**
     * Atomic conditional increment: adds 1 to count only when the current
     * value is strictly below the given limit. Returns the number of rows
     * updated (1 = allowed and counted, 0 = already at or over the limit).
     *
     * Because this is a single UPDATE statement, it is safe under concurrent
     * load across multiple application instances — no SELECT-then-UPDATE
     * race condition can occur.
     */
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

    /**
     * Deletes all rows whose date is strictly before {@code cutoff}.
     * Called by {@link DailyActionLimiter#pruneOldRows()} nightly.
     * Spring Data derives the DELETE from the method name — no @Query needed.
     */
    @Transactional
    @Modifying
    void deleteByActionDateBefore(LocalDate cutoff);
}