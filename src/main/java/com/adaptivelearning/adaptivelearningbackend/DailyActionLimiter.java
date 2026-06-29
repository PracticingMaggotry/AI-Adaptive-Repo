package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Per-student, per-action, per-day usage counter backed by the database.
 *
 * Replaces the previous in-memory ConcurrentHashMap implementation, which
 * gave every application instance its own independent counter — so running
 * two instances effectively doubled each student's daily budget, and a
 * restart silently reset all counters to zero mid-day.
 *
 * The new implementation stores one row per (actionType, studentId, date)
 * in the {@code daily_action_counts} table. Both methods are safe under
 * concurrent load across multiple instances:
 *
 *   tryConsume  — issues a single conditional UPDATE (count < limit)
 *                 rather than a SELECT-then-UPDATE, so no two instances
 *                 can simultaneously grant the same "last slot". If the
 *                 row does not exist yet it is inserted first (with count=0)
 *                 and the UPDATE is retried; a UNIQUE constraint violation
 *                 on the INSERT means another instance beat us to it, in
 *                 which case we retry the UPDATE directly.
 *
 *   getUsedToday — simple read; stale by milliseconds under very high
 *                  concurrency but acceptable for a soft usage display.
 *
 * Callers are unchanged — same tryConsume(actionType, studentId, limit)
 * and getUsedToday(actionType, studentId) signatures as before.
 *
 * Old rows are pruned automatically: a @Scheduled job runs every day at
 * 02:00 server time and deletes rows older than 30 days. This requires
 * @EnableScheduling on the application class (already present via
 * Spring Boot's auto-configuration when spring-context is on the classpath;
 * add @EnableScheduling to AdaptiveLearningBackendApplication if the job
 * is ever found not to run).
 */
@Component
public class DailyActionLimiter {

    @Autowired
    private DailyActionCountRepository repo;

    // ── Public API (same signatures as before) ───────────────────────────

    /** How many of this action the student has already used today. */
    public int getUsedToday(String actionType, String studentId) {
        return repo.findByActionTypeAndStudentIdAndActionDate(
                        normalize(actionType), normalize(studentId), LocalDate.now())
                .map(DailyActionCount::getCount)
                .orElse(0);
    }

    /**
     * Atomically checks whether the student is still under the given daily
     * limit and, if so, consumes one unit.
     *
     * Returns {@code true} if the action is allowed (and has been counted),
     * {@code false} if the daily limit has already been reached.
     */
    public boolean tryConsume(String actionType, String studentId, int dailyLimit) {
        String   type = normalize(actionType);
        String   id   = normalize(studentId);
        LocalDate today = LocalDate.now();

        // Fast path: try to increment an existing row whose count is still
        // below the limit. This is the common case once the first action of
        // the day has been recorded.
        int updated = repo.incrementIfBelow(type, id, today, dailyLimit);
        if (updated > 0) return true;

        // The row either does not exist yet (first action of the day for
        // this student+actionType) or the count is already at the limit.
        // Distinguish the two by checking the current count.
        int current = getUsedToday(type, id);
        if (current >= dailyLimit) return false;

        // Row is missing — insert it with count=0, then retry the UPDATE.
        // A concurrent INSERT from another instance will cause a unique
        // constraint violation; we catch that and fall through to the retry.
        try {
            repo.save(new DailyActionCount(type, id, today));
        } catch (DataIntegrityViolationException ignored) {
            // Another instance inserted the row first — that's fine, we'll
            // pick it up on the UPDATE retry below.
        }

        updated = repo.incrementIfBelow(type, id, today, dailyLimit);
        return updated > 0;
    }

    // ── Housekeeping ──────────────────────────────────────────────────────

    /**
     * Deletes rows older than 30 days every night at 02:00 server time.
     * Old rows never affect correctness (all queries are date-scoped to
     * today), but without this they accumulate indefinitely — one row per
     * (actionType × student × day) for every active student.
     *
     * The cron expression is: second minute hour day-of-month month day-of-week
     *   "0 0 2 * * *" — fires at 02:00:00 every day.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void pruneOldRows() {
        LocalDate cutoff = LocalDate.now().minusDays(30);
        repo.deleteByActionDateBefore(cutoff);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private static String normalize(String value) {
        return (value == null || value.isBlank())
                ? "unknown"
                : value.trim().toLowerCase(Locale.ROOT);
    }
}