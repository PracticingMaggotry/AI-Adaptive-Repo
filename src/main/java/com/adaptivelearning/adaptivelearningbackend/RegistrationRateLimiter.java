package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory abuse protection for POST /register.
 *
 * Mirrors {@link LoginRateLimiter}'s design exactly — same per-IP + per-account
 * dual-key tracking, same escalating lockout steps, same "in-memory is fine
 * for this" reasoning — rather than inventing a new throttling mechanism for
 * a different endpoint. The threat model is different from login
 * brute-forcing, but the shape of the defense is identical:
 *
 *   - Per-IP:      stops one attacker spamming registration for many
 *                  different (real or fake) email addresses from a single
 *                  address. Every call that gets this far sends a real OTP
 *                  email via Resend, so unthrottled this burns the Resend
 *                  quota (3,000/month, 100/day on the free tier) fast.
 *   - Per-account: stops an attacker who controls many IPs (rotating
 *                  proxies/botnet) from repeatedly spamming OTP emails at
 *                  ONE specific victim's inbox as harassment, even though
 *                  that victim never asked for an account.
 * Blocking on IP alone would leave the second case wide open; blocking on
 * account alone would leave the first wide open. Both keys are checked, and
 * either one being locked is enough to reject the attempt — same rule
 * LoginRateLimiter uses.
 *
 * AuthController calls {@link #check} BEFORE any password hashing,
 * duplicate-email lookup, or (most importantly) the actual call to
 * EmailService — so a throttled request never sends mail or does real work.
 * {@link #recordAttempt} is called once validation has passed and a real
 * send is about to be attempted; every such attempt counts toward the cap
 * regardless of whether the Resend API call itself succeeds or the person
 * ever completes verification, since spamming this endpoint is the abuse
 * being throttled, not just successful deliveries.
 *
 * Deliberately in-memory (ConcurrentHashMap), the same tradeoff
 * LoginRateLimiter and IpBlockFilter already make: this data is
 * short-lived, high-churn, and doesn't need to survive a restart — losing
 * it on redeploy just resets counters to zero, a safe failure mode.
 *
 * NOTE: in a multi-instance deployment behind a load balancer, each
 * instance keeps its own counters, which weakens (but does not eliminate)
 * the protection — same caveat LoginRateLimiter documents.
 */
@Component
public class RegistrationRateLimiter {

    /** Registration OTP sends allowed within the tracking window before lockout. */
    private static final int MAX_ATTEMPTS = 3;

    /** How far back an attempt still "counts" toward MAX_ATTEMPTS. */
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(10);

    /** Lockout duration sequence, indexed by (offense count - 1); last value repeats. */
    private static final Duration[] LOCKOUT_STEPS = {
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofMinutes(120)
    };

    private final ConcurrentHashMap<String, Tracker> trackers = new ConcurrentHashMap<>();

    /**
     * Result of a pre-registration check: whether the request may proceed,
     * and if not, how many seconds remain until it may be retried.
     */
    public record CheckResult(boolean allowed, long retryAfterSeconds) {
        static final CheckResult OK = new CheckResult(true, 0);
    }

    /**
     * Call BEFORE sending a registration OTP email. Checks both the IP and
     * account keys; if either is currently locked out, the longer of the
     * two remaining wait times is returned so the caller can give one
     * honest answer to the client.
     */
    public CheckResult check(String ip, String email) {
        long ipWait = remainingLockoutSeconds(ipKey(ip));
        long acctWait = remainingLockoutSeconds(accountKey(email));
        long wait = Math.max(ipWait, acctWait);
        return wait <= 0 ? CheckResult.OK : new CheckResult(false, wait);
    }

    /**
     * Call AFTER a real OTP send attempt is made (i.e. validation passed
     * and EmailService is about to be / was just invoked) — regardless of
     * whether that send succeeds or the registration is ever verified.
     */
    public void recordAttempt(String ip, String email) {
        recordAttemptForKey(ipKey(ip));
        recordAttemptForKey(accountKey(email));
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

    private void recordAttemptForKey(String key) {
        Tracker t = trackers.computeIfAbsent(key, k -> new Tracker());
        synchronized (t) {
            pruneIfWindowExpired(t);
            t.attemptCount++;
            t.windowStart = t.windowStart == null ? Instant.now() : t.windowStart;

            if (t.attemptCount >= MAX_ATTEMPTS) {
                int offenseIndex = Math.min(t.offenseCount, LOCKOUT_STEPS.length - 1);
                Duration lockout = LOCKOUT_STEPS[offenseIndex];
                t.lockedUntil = Instant.now().plus(lockout);
                t.offenseCount++;
                // Reset the raw attempt counter for the next window, but keep
                // offenseCount so repeated lockouts keep escalating.
                t.attemptCount = 0;
                t.windowStart = null;
            }
        }
    }

    /** Drops stale attempt counts once the tracking window has elapsed, unless still locked out. */
    private void pruneIfWindowExpired(Tracker t) {
        if (t.lockedUntil != null && Instant.now().isAfter(t.lockedUntil)) {
            t.lockedUntil = null; // lockout served; offenseCount intentionally persists
        }
        if (t.windowStart != null && Instant.now().isAfter(t.windowStart.plus(ATTEMPT_WINDOW))) {
            t.attemptCount = 0;
            t.windowStart = null;
        }
    }

    /** Per-key mutable state. Access only while holding the instance's own monitor. */
    private static final class Tracker {
        int attemptCount = 0;
        int offenseCount = 0;
        Instant windowStart = null;
        Instant lockedUntil = null;
    }
}