package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for POST /login.
 *
 * Previously AuthController.loginUser() had no defence beyond BCrypt's own
 * cost factor — an attacker (or a leaked-credential stuffing script) could
 * submit unlimited password guesses per second with no consequence. This
 * class is the real gate: AuthController consults it BEFORE touching the
 * database or calling passwordEncoder.matches(), and records every failure
 * AFTER a failed match.
 *
 * Two independent tracks are kept, mirroring why IpBlockFilter alone isn't
 * enough here:
 *   - Per-IP:      stops one attacker hammering many different accounts
 *                  from a single address (credential stuffing).
 *   - Per-account: stops an attacker who controls many IPs (rotating
 *                  proxies/botnet) from hammering ONE specific account.
 * Blocking on IP alone would leave the second case wide open; blocking on
 * account alone would leave the first wide open. Both keys are checked, and
 * either one being locked is enough to reject the attempt.
 *
 * Lockout duration grows with repeated offenses (5 min → 15 min → 60 min,
 * capped) rather than a single fixed window, so a script that just waits
 * out one lockout and resumes doesn't get the same cheap retry budget every
 * time. A successful login clears that key's history entirely.
 *
 * Deliberately in-memory (ConcurrentHashMap), same approach IpBlockFilter
 * uses for its blocked-IP cache: this data is short-lived, high-churn, and
 * doesn't need to survive a restart — losing it on redeploy just means
 * counters reset to zero, which is a safe failure mode (not a security
 * hole, since blocked_ips/the password hash itself are the durable controls).
 *
 * NOTE: in a multi-instance deployment behind a load balancer, each instance
 * keeps its own counters. That weakens (but does not eliminate) the
 * protection; a shared store (e.g. Redis) would be the next step if this
 * app is ever scaled horizontally.
 */
@Component
public class LoginRateLimiter {

    /** Failures allowed within the tracking window before locking the key out. */
    private static final int MAX_ATTEMPTS = 5;

    /** How far back a failure still "counts" toward MAX_ATTEMPTS. */
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    /** Lockout duration sequence, indexed by (offense count - 1); last value repeats. */
    private static final Duration[] LOCKOUT_STEPS = {
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(60)
    };

    private final ConcurrentHashMap<String, Tracker> trackers = new ConcurrentHashMap<>();

    /**
     * Result of a pre-login check: whether the request may proceed, and if
     * not, how many seconds remain until it may be retried.
     */
    public record CheckResult(boolean allowed, long retryAfterSeconds) {
        static final CheckResult OK = new CheckResult(true, 0);
    }

    /**
     * Call BEFORE attempting password verification. Checks both the IP and
     * account keys; if either is currently locked out, the longer of the two
     * remaining wait times is returned so the caller can give one honest
     * answer to the client.
     */
    public CheckResult check(String ip, String email) {
        long ipWait = remainingLockoutSeconds(ipKey(ip));
        long acctWait = remainingLockoutSeconds(accountKey(email));
        long wait = Math.max(ipWait, acctWait);
        return wait <= 0 ? CheckResult.OK : new CheckResult(false, wait);
    }

    /** Call AFTER a failed password match (or unknown email) to record the attempt. */
    public void recordFailure(String ip, String email) {
        recordFailureForKey(ipKey(ip));
        recordFailureForKey(accountKey(email));
    }

    /** Call AFTER a successful login to wipe any prior failure history for this IP/account. */
    public void recordSuccess(String ip, String email) {
        trackers.remove(ipKey(ip));
        trackers.remove(accountKey(email));
    }

    // ── Internals ─────────────────────────────────────────────────────────

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