package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;

/** Database-backed per-student, per-action, per-day usage counter, safe across multiple app instances. */
@Component
public class DailyActionLimiter {

    @Autowired
    private DailyActionCountRepository repo;

    // ── Public API ───────────────────────────

    /** How many of this action the student has already used today. */
    public int getUsedToday(String actionType, String studentId) {
        return repo.findByActionTypeAndStudentIdAndActionDate(
                        normalize(actionType), normalize(studentId), LocalDate.now())
                .map(DailyActionCount::getCount)
                .orElse(0);
    }

    /** Atomically checks the daily limit and consumes one unit if allowed; returns false if already at the cap. */
    public boolean tryConsume(String actionType, String studentId, int dailyLimit) {
        String   type = normalize(actionType);
        String   id   = normalize(studentId);
        LocalDate today = LocalDate.now();

        int updated = repo.incrementIfBelow(type, id, today, dailyLimit);
        if (updated > 0) return true;

        int current = getUsedToday(type, id);
        if (current >= dailyLimit) return false;

        // Row missing (first action today) — insert then retry the increment.
        try {
            repo.save(new DailyActionCount(type, id, today));
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent insert from another instance — fine, retry picks it up.
        }

        updated = repo.incrementIfBelow(type, id, today, dailyLimit);
        return updated > 0;
    }

    // ── Housekeeping ──────────────────────────────────────────────────────

    /** Deletes rows older than 30 days, nightly at 02:00. */
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