package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for POST /verify-email.
 *
 * Mirrors {@link LoginRateLimiter}'s design exactly — same per-IP + per-account
 * dual-key tracking, same escalating lockout steps, same "check before,
 * record after" call shape — applied to a different guessing target: the
 * registration OTP instead of a login password.
 *
 * Without this, {@link EmailVerificationStore} only checks whether a
 * submitted code is correct — it never limits how many times someone may
 * guess. An attacker who knows (or guesses) a pending registration's email
 * could brute-force the 6-digit OTP (1,000,000 possibilities) within its
 * 5-minute validity window ({@link EmailVerificationStore#EXPIRY_SECONDS})
 * with zero consequence beyond the window itself eventually expiring.
 *
 *   - Per-IP:      stops one attacker guessing across many different
 *                  pending registrations from a single address.
 *   - Per-account: stops an attacker who controls many IPs (rotating
 *                  proxies/botnet) from grinding through codes for ONE
 *                  specific target email.
 * Blocking on IP alone would leave the second case wide open; blocking on
 * account alone would leave the first wide open. Both keys are checked, and
 * either one being locked is enough to reject the attempt — same rule
 * LoginRateLimiter uses.
 *
 * AuthController calls {@link #check} BEFORE looking up the pending
 * registration or comparing the OTP, records {@link #recordFailure} after
 * every wrong guess, and calls {@link #recordSuccess} on a correct guess to
 * wipe that key's history — the same "check/recordFailure/recordSuccess"
 * shape LoginRateLimiter uses around password verification.
 *
 * Deliberately in-memory (ConcurrentHashMap), the same tradeoff
 * LoginRateLimiter and IpBlockFilter already make: short-lived, high-churn
 * data that doesn't need to survive a restart.
 */
@Component
public class OtpVerifyRateLimiter {

    /** Wrong-OTP guesses allowed within the tracking window before lockout. */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * How far back a failure still "counts" toward MAX_ATTEMPTS. Matches
     * EmailVerificationStore.EXPIRY_SECONDS (5 minutes) — guesses against
     * an OTP that has already expired anyway are moot, so there's no need
     * to track failures any longer than the code itself stays valid.
     */
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(5);

    /** Lockout duration sequence, indexed by (offense count - 1); last value repeats. */
    private static final Duration[] LOCKOUT_STEPS = {
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(60)
    };

    private final ConcurrentHashMap<String, Tracker> trackers = new ConcurrentHashMap<>();

    /**
     * Result of a pre-verification check: whether the request may proceed,
     * and if not, how many seconds remain until it may be retried.
     */
    public record CheckResult(boolean allowed, long retryAfterSeconds) {
        static final CheckResult OK = new CheckResult(true, 0);
    }

    /**
     * Call BEFORE looking up the pending registration / comparing the
     * submitted OTP. Checks both the IP and account keys; if either is
     * currently locked out, the longer of the two remaining wait times is
     * returned so the caller can give one honest answer to the client.
     */
    public CheckResult check(String ip, String email) {
        long ipWait = remainingLockoutSeconds(ipKey(ip));
        long acctWait = remainingLockoutSeconds(accountKey(email));
        long wait = Math.max(ipWait, acctWait);
        return wait <= 0 ? CheckResult.OK : new CheckResult(false, wait);
    }

    /** Call AFTER a submitted OTP fails to match (or no pending registration exists). */
    public void recordFailure(String ip, String email) {
        recordFailureForKey(ipKey(ip));
        recordFailureForKey(accountKey(email));
    }

    /** Call AFTER a submitted OTP correctly matches, to wipe prior failure history. */
    public void recordSuccess(String ip, String email) {
        trackers.remove(ipKey(ip));
        trackers.remove(accountKey(email));
    }

    // ── Internals (identical shape to LoginRateLimiter) ──────────────────

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
                Duration lockout = LOCKOUT_STEPS[offenseIndex];
                t.lockedUntil = Instant.now().plus(lockout);
                t.offenseCount++;
                // Reset the raw failure counter for the next window, but keep
                // offenseCount so repeated lockouts keep escalating.
                t.failureCount = 0;
                t.windowStart = null;
            }
        }
    }

    /** Drops stale failure counts once the tracking window has elapsed, unless still locked out. */
    private void pruneIfWindowExpired(Tracker t) {
        if (t.lockedUntil != null && Instant.now().isAfter(t.lockedUntil)) {
            t.lockedUntil = null; // lockout served; offenseCount intentionally persists
        }
        if (t.windowStart != null && Instant.now().isAfter(t.windowStart.plus(ATTEMPT_WINDOW))) {
            t.failureCount = 0;
            t.windowStart = null;
        }
    }

    /** Per-key mutable state. Access only while holding the instance's own monitor. */
    private static final class Tracker {
        int failureCount = 0;
        int offenseCount = 0;
        Instant windowStart = null;
        Instant lockedUntil = null;
    }
}