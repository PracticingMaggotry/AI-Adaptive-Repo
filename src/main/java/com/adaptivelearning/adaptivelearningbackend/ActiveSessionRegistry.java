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

    public void release(String email, String sessionId) {
        if (email != null) active.remove(key(email), sessionId);
    }
}
