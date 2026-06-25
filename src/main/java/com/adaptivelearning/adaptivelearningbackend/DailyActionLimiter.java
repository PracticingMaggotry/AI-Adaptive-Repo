package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory per-student, per-action, per-day usage counter.
 *
 * Used to cap how many times each student can trigger an expensive
 * AI-backed action per calendar day:
 *   - Material upload            (MaterialController)
 *   - Adapted Quiz generation    (QuizController)
 *   - Targeted Quiz generation   (QuizController)
 *   - Learning Hub regeneration  (AiLessonController)
 *
 * Keyed by (actionType, studentId, date) so each action tracks its own
 * budget independently and counters naturally reset at midnight without
 * needing a scheduled cleanup job — a new LocalDate.now() simply produces
 * a new key. Stale keys from prior days are harmless; they just sit unused
 * until the process restarts, mirroring the same acceptable-tradeoff
 * reasoning LoginRateLimiter already uses for its in-memory tracking.
 *
 * Deliberately in-memory rather than DB-backed: this is a soft usage cap,
 * not a security control, so losing counts on redeploy (resetting everyone's
 * daily budget) is an acceptable failure mode, not a hole that needs closing.
 */
@Component
public class DailyActionLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    private String key(String actionType, String studentId) {
        String id = (studentId == null || studentId.isBlank()) ? "unknown" : studentId.trim().toLowerCase();
        return actionType + ":" + id + ":" + LocalDate.now();
    }

    /** How many of this action the student has already used up today. */
    public int getUsedToday(String actionType, String studentId) {
        AtomicInteger counter = counts.get(key(actionType, studentId));
        return counter == null ? 0 : counter.get();
    }

    /**
     * Atomically checks whether the student is still under the given daily
     * limit and, if so, consumes one unit. Returns true if the action is
     * allowed (and was just counted), false if the daily limit has already
     * been reached (in which case nothing is consumed).
     */
    public boolean tryConsume(String actionType, String studentId, int dailyLimit) {
        AtomicInteger counter = counts.computeIfAbsent(key(actionType, studentId), k -> new AtomicInteger(0));
        while (true) {
            int current = counter.get();
            if (current >= dailyLimit) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }
}