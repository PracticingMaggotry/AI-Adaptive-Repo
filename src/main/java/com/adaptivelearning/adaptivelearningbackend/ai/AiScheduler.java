package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.*;
import java.util.concurrent.Callable;

/**
 * Admission control for calls to the Anthropic API. Replaces a single JVM-wide {@code Semaphore(4)} that every call
 * (a 12k-token quiz generation lasting minutes, or a one-second lesson) had to share equally.
 *
 * <ul>
 *   <li><b>Lanes.</b> INTERACTIVE work (lesson, answer grading) outranks STANDARD (question generation), which
 *       outranks BULK (summaries, categorisation). {@code reservedInteractive} slots can never be taken by the
 *       other lanes, so a burst of quiz generation cannot block a student who just wants to read a lesson.
 *       BULK work that has waited long enough is promoted so it cannot starve.</li>
 *   <li><b>Fairness.</b> Inside a lane the student with the fewest calls in flight goes first, and no student may
 *       hold more than {@code perUserMax} slots, so one heavy user cannot fill the gate.</li>
 *   <li><b>Self-tuning limit.</b> The number of concurrent calls is not a fixed 4: it grows slowly while calls
 *       succeed and is halved (and new calls paused for Retry-After) when the API answers 429. The real ceiling
 *       is the account's rate limit, which this discovers instead of guessing.</li>
 *   <li><b>Bounded wait.</b> A caller waits at most {@code maxWaitMs} and then gets a {@link BusyException}.</li>
 * </ul>
 * Pure Java; thread-safe.
 */
public final class AiScheduler {

    public enum Lane { INTERACTIVE, STANDARD, BULK }

    public static final class BusyException extends RuntimeException {
        public BusyException(String message) { super(message); }
    }

    public record Stats(int limit, int inFlight, int queuedInteractive, int queuedStandard, int queuedBulk,
                        long totalGranted, long totalRejected, long totalRateLimited) {}

    private static final class Waiter {
        final String user;
        final Lane lane;
        final long enqueuedMs;
        boolean granted;
        Waiter(String user, Lane lane, long now) { this.user = user; this.lane = lane; this.enqueuedMs = now; }
    }

    private final int maxLimit, minLimit, reservedInteractive, perUserMax;
    private final long bulkPromoteMs;
    private final java.util.function.LongSupplier clock;

    private int limit;
    private int inFlight;
    private int nonInteractiveInFlight;
    private int successesSinceChange;
    private long notBeforeMs;
    private long granted, rejected, rateLimited;
    private final Map<String, Integer> inFlightByUser = new HashMap<>();
    private final List<Waiter> queue = new ArrayList<>();

    public AiScheduler(int maxLimit, int minLimit, int reservedInteractive, int perUserMax, long bulkPromoteMs) {
        this(maxLimit, minLimit, reservedInteractive, perUserMax, bulkPromoteMs, System::currentTimeMillis);
    }

    AiScheduler(int maxLimit, int minLimit, int reservedInteractive, int perUserMax, long bulkPromoteMs,
                java.util.function.LongSupplier clock) {
        this.maxLimit = Math.max(1, maxLimit);
        this.minLimit = Math.max(1, Math.min(minLimit, this.maxLimit));
        this.reservedInteractive = Math.max(0, Math.min(reservedInteractive, this.maxLimit - 1));
        this.perUserMax = Math.max(1, perUserMax);
        this.bulkPromoteMs = bulkPromoteMs;
        this.clock = clock;
        this.limit = this.maxLimit;
    }

    public static AiScheduler withDefaults() {
        int max = Integer.getInteger("ai.max.concurrent", AiPolicy.SCHED_MAX_CONCURRENT);
        return new AiScheduler(max, AiPolicy.SCHED_MIN_CONCURRENT, AiPolicy.SCHED_RESERVED_INTERACTIVE,
                AiPolicy.SCHED_PER_USER_MAX, AiPolicy.SCHED_BULK_PROMOTE_MS);
    }

    /** Runs {@code task} once a slot is available; blocks the caller until then (at most {@code maxWaitMs}). */
    public <T> T run(String user, Lane lane, long maxWaitMs, Callable<T> task) throws Exception {
        Waiter w = acquire(user == null || user.isBlank() ? "_system" : user, lane, maxWaitMs);
        try {
            return task.call();
        } finally {
            release(w);
        }
    }

    private synchronized Waiter acquire(String user, Lane lane, long maxWaitMs) {
        long now = clock.getAsLong();
        long deadline = now + maxWaitMs;
        Waiter w = new Waiter(user, lane, now);
        queue.add(w);
        dispatch();
        while (!w.granted) {
            long remaining = deadline - clock.getAsLong();
            if (remaining <= 0) {
                queue.remove(w);
                rejected++;
                throw new BusyException("AI service is busy (" + lane + " queue); please try again shortly.");
            }
            try {
                wait(Math.min(remaining, 250L)); // short ticks so a Retry-After pause can expire
            } catch (InterruptedException ie) {
                queue.remove(w);
                Thread.currentThread().interrupt();
                throw new BusyException("Interrupted while waiting for the AI service.");
            }
            dispatch();
        }
        return w;
    }

    private synchronized void release(Waiter w) {
        inFlight--;
        if (w.lane != Lane.INTERACTIVE) nonInteractiveInFlight--;
        inFlightByUser.merge(w.user, -1, Integer::sum);
        if (inFlightByUser.getOrDefault(w.user, 0) <= 0) inFlightByUser.remove(w.user);
        dispatch();
        notifyAll();
    }

    private void dispatch() {
        boolean any = false;
        while (clock.getAsLong() >= notBeforeMs) {
            Waiter best = null;
            long now = clock.getAsLong();
            for (Waiter w : queue) {
                if (!canStart(w)) continue;
                if (best == null || better(w, best, now)) best = w;
            }
            if (best == null) break;
            queue.remove(best);
            best.granted = true;
            inFlight++;
            if (best.lane != Lane.INTERACTIVE) nonInteractiveInFlight++;
            inFlightByUser.merge(best.user, 1, Integer::sum);
            granted++;
            any = true;
        }
        if (any) notifyAll();
    }

    private boolean canStart(Waiter w) {
        if (inFlight >= limit) return false;
        if (inFlightByUser.getOrDefault(w.user, 0) >= perUserMax) return false;
        if (w.lane != Lane.INTERACTIVE && nonInteractiveInFlight >= Math.max(1, limit - reservedInteractive)) return false;
        return true;
    }

    private int rank(Waiter w, long now) {
        if (w.lane == Lane.BULK && now - w.enqueuedMs >= bulkPromoteMs) return Lane.STANDARD.ordinal();
        return w.lane.ordinal();
    }

    private boolean better(Waiter a, Waiter b, long now) {
        int ra = rank(a, now), rb = rank(b, now);
        if (ra != rb) return ra < rb;
        int ua = inFlightByUser.getOrDefault(a.user, 0), ub = inFlightByUser.getOrDefault(b.user, 0);
        if (ua != ub) return ua < ub;
        return a.enqueuedMs < b.enqueuedMs;
    }

    /** Call when the API answered 429 / overloaded: halve the concurrency limit and pause new calls. */
    public synchronized void onRateLimited(long retryAfterMs) {
        rateLimited++;
        limit = Math.max(minLimit, limit / 2);
        successesSinceChange = 0;
        long pause = Math.max(1000L, Math.min(retryAfterMs, 60_000L));
        notBeforeMs = Math.max(notBeforeMs, clock.getAsLong() + pause);
    }

    /** Call after a successful API call: after {@code limit} successes in a row the limit grows by one. */
    public synchronized void onSuccess() {
        if (limit >= maxLimit) return;
        if (++successesSinceChange >= limit) {
            limit++;
            successesSinceChange = 0;
            dispatch();
        }
    }

    public synchronized Stats stats() {
        int qi = 0, qs = 0, qb = 0;
        for (Waiter w : queue) {
            if (w.lane == Lane.INTERACTIVE) qi++; else if (w.lane == Lane.STANDARD) qs++; else qb++;
        }
        return new Stats(limit, inFlight, qi, qs, qb, granted, rejected, rateLimited);
    }
}
