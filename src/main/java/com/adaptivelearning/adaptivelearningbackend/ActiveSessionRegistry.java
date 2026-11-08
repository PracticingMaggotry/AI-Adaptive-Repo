package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the one live session per non-admin account: a new login replaces the old one, so two people
 * sharing an account keep logging each other out. In-memory, like the HTTP sessions it mirrors.
 * Disable with login.single-session.enabled=false.
 */
@Component
public class ActiveSessionRegistry {

    @Value("${login.single-session.enabled:true}")
    private boolean enabled;

    private final ConcurrentHashMap<String, String> active = new ConcurrentHashMap<>();

    /**
     * Per account: sessions that signed in before this instant (epoch millis) are no longer valid. Set when the
     * password changes. Unlike {@link #active} this works even when login.single-session.enabled=false, and it
     * is in-memory like the HTTP sessions it governs (both are gone after a restart).
     */
    private final ConcurrentHashMap<String, Long> revokedBefore = new ConcurrentHashMap<>();

    private static String key(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    /** Called on a completed login: this session becomes the account's only live one. */
    public void register(String email, String sessionId) {
        if (enabled) active.put(key(email), sessionId);
    }

    /** True if this session may continue. A session with no entry (e.g. pre-deploy) is adopted. */
    public boolean isCurrent(String email, String sessionId) {
        if (!enabled) return true;
        String current = active.putIfAbsent(key(email), sessionId);
        return current == null || current.equals(sessionId);
    }

    /**
     * Ends every session of this account except {@code keepSessionId}, which the caller has just stamped with an
     * "authAt" of {@code nowMillis}. Other sessions carry an older (or no) "authAt" and fail {@link #isRevoked}.
     */
    public void revokeAllExcept(String email, String keepSessionId, long nowMillis) {
        revokedBefore.merge(key(email), nowMillis, Long::max);
        if (enabled) active.put(key(email), keepSessionId);
    }

    /**
     * True if a session that signed in at {@code authAt} predates a password change. A session with no timestamp
     * (created before this check existed) counts as predating it, but only once a change has happened.
     */
    public boolean isRevoked(String email, Long authAt) {
        Long cutoff = revokedBefore.get(key(email));
        if (cutoff == null) return false;
        return authAt == null || authAt < cutoff;
    }

    public void release(String email, String sessionId) {
        if (email != null) active.remove(key(email), sessionId);
    }
}
