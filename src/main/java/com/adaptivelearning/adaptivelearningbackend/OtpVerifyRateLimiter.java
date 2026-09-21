package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for POST /verify-email, mirroring
 * {@link LoginRateLimiter}'s design applied to guessing a registration OTP
 * instead of a password.
 *
 * EmailVerificationStore only checks whether a submitted code matches — it
 * never limits guesses, so without this an attacker could brute-force the
 * 6-digit OTP (1,000,000 combos) within its 5-minute validity window.
 *
 * Per-IP stops one attacker grinding many pending registrations; per-account
 * stops a botnet grinding one target email. Either lockout blocks the attempt.
 * AuthController calls check() before comparing the OTP, recordFailure()
 * after each wrong guess, recordSuccess() on a correct one.
 *
 * In-memory, same tradeoff as LoginRateLimiter.
 */
@Component
public class OtpVerifyRateLimiter {

    private static final int MAX_ATTEMPTS = 5;

    /** Matches EmailVerificationStore.EXPIRY_SECONDS — no point tracking failures past OTP expiry. */
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(5);

    private static final Duration[] LOCKOUT_STEPS = {
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(60)
    };

    private final ConcurrentHashMap<String, Tracker> trackers = new ConcurrentHashMap<>();

    public record CheckResult(boolean allowed, long retryAfterSeconds) {
        static final CheckResult OK = new CheckResult(true, 0);
    }

    /** Call before looking up the pending registration / comparing the OTP. */
    public CheckResult check(String ip, String email) {
        long ipWait = remainingLockoutSeconds(ipKey(ip));
        long acctWait = remainingLockoutSeconds(accountKey(email));
        long wait = Math.max(ipWait, acctWait);
        return wait <= 0 ? CheckResult.OK : new CheckResult(false, wait);
    }

    public void recordFailure(String ip, String email) {
        recordFailureForKey(ipKey(ip));
        recordFailureForKey(accountKey(email));
    }

    public void recordSuccess(String ip, String email) {
        trackers.remove(ipKey(ip));
        trackers.remove(accountKey(email));
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

    private void recordFailureForKey(String key) {
        Tracker t = trackers.computeIfAbsent(key, k -> new Tracker());
        synchronized (t) {
            pruneIfWindowExpired(t);
            t.failureCount++;
            t.windowStart = t.windowStart == null ? Instant.now() : t.windowStart;

            if (t.failureCount >= MAX_ATTEMPTS) {
                int offenseIndex = Math.min(t.offenseCount, LOCKOUT_STEPS.length - 1);
                t.lockedUntil = Instant.now().plus(LOCKOUT_STEPS[offenseIndex]);
                t.offenseCount++;
                t.failureCount = 0;
                t.windowStart = null;
            }
        }
    }

    private void pruneIfWindowExpired(Tracker t) {
        if (t.lockedUntil != null && Instant.now().isAfter(t.lockedUntil)) {
            t.lockedUntil = null;
        }
        if (t.windowStart != null && Instant.now().isAfter(t.windowStart.plus(ATTEMPT_WINDOW))) {
            t.failureCount = 0;
            t.windowStart = null;
        }
    }

    private static final class Tracker {
        int failureCount = 0;
        int offenseCount = 0;
        Instant windowStart = null;
        Instant lockedUntil = null;
    }
}