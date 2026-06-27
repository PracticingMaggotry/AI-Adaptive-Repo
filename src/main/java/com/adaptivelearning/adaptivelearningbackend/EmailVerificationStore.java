package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for pending email-verification OTPs.
 *
 * Registration is a two-step flow:
 *   Step 1 — POST /register          → validates fields, sends OTP, stores pending data here
 *   Step 2 — POST /verify-email      → checks OTP, creates the real User row on success
 *
 * Nothing is written to the `users` table until the OTP is confirmed, so
 * unverified addresses never occupy a slot in the unique email index.
 *
 * OTPs expire after EXPIRY_SECONDS (5 minutes). Stale entries are evicted
 * lazily on the next access — no background thread is needed.
 *
 * Deliberately in-memory (not DB-backed): pending registrations are
 * short-lived, high-churn, and don't need to survive a restart — a user
 * whose OTP is lost on redeploy simply re-registers, which is the same
 * outcome as an expired OTP. Mirrors the same acceptable-tradeoff reasoning
 * LoginRateLimiter and DailyActionLimiter already use.
 */
@Component
public class EmailVerificationStore {

    /** Seconds before a pending OTP is considered expired. */
    public static final long EXPIRY_SECONDS = 300; // 5 minutes

    /**
     * Everything needed to create the real User row once the OTP is confirmed.
     * Stored here instead of re-sent on every verify attempt so the password
     * is only hashed once (BCrypt is intentionally expensive).
     */
    public static final class PendingRegistration {
        public final String email;
        public final String encodedPassword;
        public final String fullName;
        public final boolean adminAccount;
        public final String otp;
        public final Instant expiresAt;

        public PendingRegistration(String email, String encodedPassword, String fullName,
                                   boolean adminAccount, String otp) {
            this.email           = email;
            this.encodedPassword = encodedPassword;
            this.fullName        = fullName;
            this.adminAccount    = adminAccount;
            this.otp             = otp;
            this.expiresAt       = Instant.now().plusSeconds(EXPIRY_SECONDS);
        }

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    // Keyed by lower-cased email so lookups are case-insensitive.
    private final ConcurrentHashMap<String, PendingRegistration> store = new ConcurrentHashMap<>();

    private String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Stores (or replaces) a pending registration for this email address. */
    public void put(PendingRegistration pending) {
        store.put(key(pending.email), pending);
    }

    /**
     * Returns the pending registration for this email, or null if none exists
     * or the OTP has already expired. Expired entries are evicted on access.
     */
    public PendingRegistration get(String email) {
        PendingRegistration pending = store.get(key(email));
        if (pending == null) return null;
        if (pending.isExpired()) {
            store.remove(key(email));
            return null;
        }
        return pending;
    }

    /** Removes the pending registration (called after successful verification). */
    public void remove(String email) {
        store.remove(key(email));
    }

    /** True if there is a non-expired pending registration for this email. */
    public boolean hasPending(String email) {
        return get(email) != null;
    }
}