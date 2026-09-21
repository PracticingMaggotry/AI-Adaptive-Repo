package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory abuse protection for POST /register, mirroring
 * {@link LoginRateLimiter}'s design.
 *
 * Per-IP stops one attacker spamming registration OTP emails (burning the
 * Resend quota); per-account stops a botnet harassing one victim's inbox
 * with unwanted codes. Either lockout blocks the attempt.
 *
 * AuthController calls check() before any password hashing, email lookup,
 * or EmailService call. recordAttempt() is called once validation passes
 * and a real send is about to happen — every such attempt counts, regardless
 * of whether the send succeeds or verification is ever completed.
 *
 * In-memory, same tradeoff as LoginRateLimiter. In a
 * multi-instance deployment each instance keeps its own counters.
 */
@Component
public class RegistrationRateLimiter {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(10);
    private static final Duration[] LOCKOUT_STEPS = {
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofMinutes(120)
    };

    private final ConcurrentHashMap<String, Tracker> trackers = new ConcurrentHashMap<>();

    public record CheckResult(boolean allowed, long retryAfterSeconds) {
        static final CheckResult OK = new CheckResult(true, 0);
    }

    /** Call before sending a registration OTP email. */
    public CheckResult check(String ip, String email) {
        long ipWait = remainingLockoutSeconds(ipKey(ip));
        long acctWait = remainingLockoutSeconds(accountKey(email));
        long wait = Math.max(ipWait, acctWait);
        return wait <= 0 ? CheckResult.OK : new CheckResult(false, wait);
    }

    /** Call after a real send attempt, regardless of outcome. */
    public void recordAttempt(String ip, String email) {
        recordAttemptForKey(ipKey(ip));
        recordAttemptForKey(accountKey(email));
    }

    private String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }

    private String accountKey(String email) {
        return "acct:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private long remainingLockoutSeconds(String key) {
        Tracker t = trackers.get(key);
        if (t == null) return 0;
        synchronized (t) {
            pruneIfWindowExpired(t);
            if (t.lockedUntil != null && Instant.now().isBefore(t.lockedUntil)) {
                return Duration.between(Instant.now(), t.lockedUntil).getSeconds() + 1;
            }
            return 0;
        }
    }

    private void recordAttemptForKey(String key) {
        Tracker t = trackers.computeIfAbsent(key, k -> new Tracker());
        synchronized (t) {
            pruneIfWindowExpired(t);
            t.attemptCount++;
            t.windowStart = t.windowStart == null ? Instant.now() : t.windowStart;

            if (t.attemptCount >= MAX_ATTEMPTS) {
                int offenseIndex = Math.min(t.offenseCount, LOCKOUT_STEPS.length - 1);
                t.lockedUntil = Instant.now().plus(LOCKOUT_STEPS[offenseIndex]);
                t.offenseCount++;
                t.attemptCount = 0;
                t.windowStart = null;
            }
        }
    }

    private void pruneIfWindowExpired(Tracker t) {
        if (t.lockedUntil != null && Instant.now().isAfter(t.lockedUntil)) {
            t.lockedUntil = null;
        }
        if (t.windowStart != null && Instant.now().isAfter(t.windowStart.plus(ATTEMPT_WINDOW))) {
            t.attemptCount = 0;
            t.windowStart = null;
        }
    }

    private static final class Tracker {
        int attemptCount = 0;
        int offenseCount = 0;
        Instant windowStart = null;
        Instant lockedUntil = null;
    }
}