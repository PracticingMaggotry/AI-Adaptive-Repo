package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Server-side policy for authenticated admin sessions.
 *
 *  - An admin session is only valid if the second factor was completed in it (adminMfaOk).
 *  - Idle timeout (default 15 min) and absolute timeout (default 2 h), both enforced server-side.
 *  - Session is bound to the User-Agent it was created with.
 *  - Sensitive operations need a recent step-up (second factor re-entered within N minutes).
 */
@Component
public class AdminSessionGuard {

    public static final String K_MFA_OK = "adminMfaOk";
    public static final String K_LOGIN_AT = "adminLoginAt";
    public static final String K_LAST_SEEN = "adminLastSeen";
    public static final String K_UA = "adminUaHash";
    public static final String K_STEPUP_AT = "adminStepUpAt";

    public enum Verdict { OK, NO_MFA, IDLE, ABSOLUTE, UA_MISMATCH }

    @Value("${admin.session.idle-minutes:15}")
    private long idleMinutes;

    @Value("${admin.session.absolute-minutes:120}")
    private long absoluteMinutes;

    @Value("${admin.stepup.window-minutes:10}")
    private long stepUpMinutes;

    public int idleSeconds() {
        return (int) (idleMinutes * 60);
    }

    /** Called once when an admin session is created, after the second factor succeeded. */
    public void stamp(HttpSession session, HttpServletRequest request) {
        long now = System.currentTimeMillis();
        session.setAttribute(K_MFA_OK, Boolean.TRUE);
        session.setAttribute(K_LOGIN_AT, now);
        session.setAttribute(K_LAST_SEEN, now);
        session.setAttribute(K_STEPUP_AT, now);
        session.setAttribute(K_UA, uaHash(request));
    }

    /** Validates and, if valid, refreshes the idle clock. */
    public Verdict check(HttpSession session, HttpServletRequest request) {
        if (!Boolean.TRUE.equals(session.getAttribute(K_MFA_OK))) return Verdict.NO_MFA;
        long now = System.currentTimeMillis();
        Long loginAt = (Long) session.getAttribute(K_LOGIN_AT);
        Long lastSeen = (Long) session.getAttribute(K_LAST_SEEN);
        if (loginAt == null || lastSeen == null) return Verdict.NO_MFA;
        if (now - loginAt > absoluteMinutes * 60_000L) return Verdict.ABSOLUTE;
        if (now - lastSeen > idleMinutes * 60_000L) return Verdict.IDLE;
        String ua = (String) session.getAttribute(K_UA);
        if (ua == null || !MessageDigest.isEqual(ua.getBytes(StandardCharsets.UTF_8),
                uaHash(request).getBytes(StandardCharsets.UTF_8))) return Verdict.UA_MISMATCH;
        session.setAttribute(K_LAST_SEEN, now);
        return Verdict.OK;
    }

    public boolean stepUpFresh(HttpSession session) {
        Long at = (Long) session.getAttribute(K_STEPUP_AT);
        return at != null && System.currentTimeMillis() - at <= stepUpMinutes * 60_000L;
    }

    public void markStepUp(HttpSession session) {
        session.setAttribute(K_STEPUP_AT, System.currentTimeMillis());
    }

    /** Every state-changing admin API call, plus research-data exports, needs a fresh step-up. */
    public static boolean requiresStepUp(String method, String path) {
        if (path.equals("/api/admin/stepup")) return false;
        String m = method.toUpperCase(Locale.ROOT);
        if (path.startsWith("/api/admin/export/")) return true;
        return path.startsWith("/api/admin/") && !(m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS"));
    }

    private static String uaHash(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((ua == null ? "" : ua).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
