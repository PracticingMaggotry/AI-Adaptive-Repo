package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for pending password-reset codes (one per account).
 *
 * Same trade-off as {@link EmailVerificationStore}: short-lived, high-churn, safe to lose on restart (the user
 * just requests another code). With more than one app instance each instance would keep its own copy, so a code
 * issued by one could not be redeemed on another; this deployment runs a single instance.
 *
 * Besides the time limit, each code survives at most {@link #MAX_WRONG_GUESSES} wrong guesses and is then
 * destroyed. That cap is independent of {@link OtpVerifyRateLimiter}, so it still holds if the limiter's
 * counters are lost (restart) or the guesses come from many IPs.
 */
@Component
public class PasswordResetStore {

    public static final long EXPIRY_SECONDS = 300; // 5 minutes, matches the "expires in 5 minutes" email text
    public static final int MAX_WRONG_GUESSES = 5;

    public enum Verdict {
        /** The code is right. It stays valid until {@link #remove} is called. */
        OK,
        /** The code is wrong; the pending reset is still alive unless the guess cap was just hit. */
        WRONG,
        /** Nothing pending for this email, or the code expired. */
        NONE
    }

    private static final class Pending {
        final String otp;
        final Instant expiresAt;
        int wrongGuesses = 0;

        Pending(String otp, Instant expiresAt) {
            this.otp = otp;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, Pending> store = new ConcurrentHashMap<>();

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Issues (or replaces) the pending code for this account. A new code resets the guess counter. */
    public void put(String email, String otp) {
        put(email, otp, Instant.now().plusSeconds(EXPIRY_SECONDS));
    }

    /** Package-private so tests can create an already-expired code. */
    void put(String email, String otp, Instant expiresAt) {
        store.put(key(email), new Pending(otp, expiresAt));
    }

    /**
     * Compares a submitted code against the pending one. Evaluated atomically per account, so concurrent
     * guesses cannot both slip under the wrong-guess cap.
     */
    public Verdict check(String email, String submitted) {
        Verdict[] out = {Verdict.NONE};
        store.compute(key(email), (k, pending) -> {
            if (pending == null || pending.isExpired()) {
                out[0] = Verdict.NONE;
                return null;
            }
            if (constantTimeEquals(pending.otp, submitted)) {
                out[0] = Verdict.OK;
                return pending;
            }
            out[0] = Verdict.WRONG;
            pending.wrongGuesses++;
            return pending.wrongGuesses >= MAX_WRONG_GUESSES ? null : pending;
        });
        return out[0];
    }

    public void remove(String email) {
        store.remove(key(email));
    }

    public boolean hasPending(String email) {
        Pending p = store.get(key(email));
        return p != null && !p.isExpired();
    }

    /** Constant-time comparison to avoid a timing oracle on the code. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < ab.length; i++) diff |= (ab[i] ^ bb[i]);
        return diff == 0;
    }
}
