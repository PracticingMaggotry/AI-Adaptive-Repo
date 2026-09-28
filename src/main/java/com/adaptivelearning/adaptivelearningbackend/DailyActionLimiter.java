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

    @Autowired
    private UserDailyLimitOverrideRepository overrideRepo;

    @Autowired
    private ConfigurationService configurationService;

    // ── Public API ───────────────────────────

    /** How many of this action the student has already used today. */
    public int getUsedToday(String actionType, String studentId) {
        return repo.findByActionTypeAndStudentIdAndActionDate(
                        normalize(actionType), normalize(studentId), LocalDate.now())
                .map(DailyActionCount::getCount)
                .orElse(0);
    }

    /**
     * The cap that actually applies to this student for this action: their personal
     * override if an admin set one, otherwise the platform-wide default.
     */
    public int getEffectiveLimit(String actionType, String studentId) {
        String type = normalize(actionType);
        String id   = normalize(studentId);
        return overrideRepo.findByActionTypeAndStudentId(type, id)
                .map(UserDailyLimitOverride::getCustomLimit)
                .orElseGet(() -> configurationService.getGlobalLimitForActionType(type));
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

    // ── Admin overrides ──────────────────────────────────────────────────

    /** Today's used count for every action type this student has any usage for. Keyed by actionType. */
    public java.util.Map<String, Integer> getUsageForStudentToday(String studentId) {
        String id = normalize(studentId);
        java.util.Map<String, Integer> usage = new java.util.LinkedHashMap<>();
        for (DailyActionCount row : repo.findByStudentIdAndActionDate(id, LocalDate.now())) {
            usage.put(row.getActionType(), row.getCount());
        }
        return usage;
    }

    /** Admin override: sets a student's used-today count for an action type directly (e.g. to reset it or unblock them). */
    public void setUsedToday(String actionType, String studentId, int count) {
        String type = normalize(actionType);
        String id   = normalize(studentId);
        LocalDate today = LocalDate.now();
        final int safeCount = Math.max(count, 0);

        java.util.Optional<DailyActionCount> existing =
                repo.findByActionTypeAndStudentIdAndActionDate(type, id, today);
        DailyActionCount row = existing.orElseGet(() -> new DailyActionCount(type, id, today));
        row.setCount(safeCount);
        try {
            repo.save(row);
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent insert from another instance — re-fetch and retry once.
            repo.findByActionTypeAndStudentIdAndActionDate(type, id, today)
                    .ifPresent(r -> { r.setCount(safeCount); repo.save(r); });
        }
    }

    /** This student's personal cap overrides, keyed by actionType (only entries that have one). */
    public java.util.Map<String, Integer> getOverridesForStudent(String studentId) {
        String id = normalize(studentId);
        java.util.Map<String, Integer> overrides = new java.util.LinkedHashMap<>();
        for (UserDailyLimitOverride row : overrideRepo.findByStudentId(id)) {
            overrides.put(row.getActionType(), row.getCustomLimit());
        }
        return overrides;
    }

    /** Admin: sets (or replaces) this student's personal daily cap for an action type. */
    public void setPersonalLimit(String actionType, String studentId, int customLimit) {
        String type = normalize(actionType);
        String id   = normalize(studentId);
        final int safeLimit = Math.max(customLimit, 0);

        java.util.Optional<UserDailyLimitOverride> existing =
                overrideRepo.findByActionTypeAndStudentId(type, id);
        UserDailyLimitOverride row = existing.orElseGet(() -> new UserDailyLimitOverride(type, id, safeLimit));
        row.setCustomLimit(safeLimit);
        try {
            overrideRepo.save(row);
        } catch (DataIntegrityViolationException ignored) {
            overrideRepo.findByActionTypeAndStudentId(type, id)
                    .ifPresent(r -> { r.setCustomLimit(customLimit); overrideRepo.save(r); });
        }
    }

    /** Admin: removes this student's personal cap, so they fall back to the platform-wide default. */
    public void clearPersonalLimit(String actionType, String studentId) {
        overrideRepo.deleteByActionTypeAndStudentId(normalize(actionType), normalize(studentId));
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