package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for pending email-verification OTPs.
 *
 * Registration is two steps: POST /register validates + sends an OTP and
 * stores the pending data here; POST /verify-email checks the OTP and only
 * then creates the User row. Nothing occupies the unique email index until
 * verified.
 *
 * OTPs expire after EXPIRY_SECONDS; stale entries are evicted lazily on access.
 * Deliberately in-memory — short-lived, high-churn, safe to lose on restart
 * (same as re-registering after an expired OTP).
 */
@Component
public class EmailVerificationStore {

    public static final long EXPIRY_SECONDS = 300; // 5 minutes

    /** Everything needed to create the User row once the OTP is confirmed. */
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

    private final ConcurrentHashMap<String, PendingRegistration> store = new ConcurrentHashMap<>();

    private String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void put(PendingRegistration pending) {
        store.put(key(pending.email), pending);
    }

    /** Returns the pending registration, or null if none exists or the OTP expired. */
    public PendingRegistration get(String email) {
        PendingRegistration pending = store.get(key(email));
        if (pending == null) return null;
        if (pending.isExpired()) {
            store.remove(key(email));
            return null;
        }
        return pending;
    }

    public void remove(String email) {
        store.remove(key(email));
    }

    public boolean hasPending(String email) {
        return get(email) != null;
    }
}