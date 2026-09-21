package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Synchronizer-token CSRF protection for state-mutating requests.
 *
 * On a safe-method request for a logged-in user, a random token is minted,
 * stored in the session, and sent as a non-HttpOnly "XSRF-TOKEN" cookie so
 * JS can read it. Mutating requests must echo it back via the X-XSRF-TOKEN
 * header (or "_csrf" form field); missing/wrong token -> 403.
 *
 * Safe methods (GET/HEAD/OPTIONS/TRACE) are exempt, as are /login, /register,
 * /verify-email, /logout — none of these can escalate privilege via CSRF
 * (register/verify require proving inbox ownership; login rotates the
 * session ID on success; logout is harmless).
 *
 * SameSite cookies already block cross-origin submission at the browser
 * level; this adds server-side verification on top.
 *
 * Cookie Secure flag is driven by app.cookie.secure (default true) rather
 * than hardcoded, so local http:// dev doesn't silently break token issuance.
 *
 * Registered as a Spring bean (not `new`) so @Value is actually populated.
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final String SESSION_KEY = "csrfToken";
    private static final String COOKIE_NAME = "XSRF-TOKEN";
    private static final String HEADER_NAME = "X-XSRF-TOKEN";
    private static final String FORM_FIELD  = "_csrf";

    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /**
     * Public paths that work without a session/token — OTP proves intent for /verify-email.
     *
     * /api/materials/cancel-upload is exempt for a different reason: it's called via
     * navigator.sendBeacon() when a user navigates away mid-upload (see quizhub.html /
     * learninghub.html), and sendBeacon() cannot set custom headers (no X-XSRF-TOKEN) and
     * sends a raw JSON body (no _csrf form field to read either). Without this exemption
     * every beacon call was silently rejected with 403 — sendBeacon() never surfaces the
     * failure to JS — so cancelUpload() never ran and every abandoned upload leaked its
     * Material row (and uploaded file / MaterialContent dedup row) forever.
     * Safe to exempt: it only lets the CALLER'S OWN session delete a Material row keyed by
     * a client-generated uploadId that MaterialController already tracks as belonging to
     * that upload attempt — it can't be used to affect another user's data or any other
     * mutation.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/login", "/register", "/verify-email", "/logout", "/api/materials/cancel-upload"
    );

    private final SecureRandom rng = new SecureRandom();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String method = request.getMethod().toUpperCase();
        String path   = request.getRequestURI();

        if (SAFE_METHODS.contains(method) || EXEMPT_PATHS.contains(path)) {
            // Issue a token on GET for logged-in users so the frontend can pick it up.
            if ("GET".equals(method)) {
                HttpSession session = request.getSession(false);
                if (session != null && session.getAttribute("loggedInUserEmail") != null) {
                    ensureToken(session, response);
                }
            }
            return true;
        }

        // Only enforced for logged-in users; unauthenticated mutating calls fall through
        // to each controller's own session check.
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("loggedInUserEmail") == null) {
            return true;
        }

        String sessionToken = (String) session.getAttribute(SESSION_KEY);
        if (sessionToken == null) {
            // No token yet — issue one and force a retry.
            ensureToken(session, response);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF token missing — please retry.");
            return false;
        }

        String submitted = request.getHeader(HEADER_NAME);
        if (submitted == null || submitted.isBlank()) {
            submitted = request.getParameter(FORM_FIELD);
        }

        if (!constantTimeEquals(sessionToken, submitted)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF token invalid.");
            return false;
        }

        return true;
    }

    /** Mints (if needed) and re-sends the CSRF cookie. HttpOnly=false so JS can read it. */
    private void ensureToken(HttpSession session, HttpServletResponse response) {
        String token = (String) session.getAttribute(SESSION_KEY);
        if (token == null) {
            byte[] bytes = new byte[32];
            rng.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(SESSION_KEY, token);
        }
        // Built manually (not Cookie.setSameSite()) for compatibility with older servlet containers.
        String cookieValue = COOKIE_NAME + "=" + token
                + "; Path=/"
                + "; SameSite=Lax"
                + (secureCookies ? "; Secure" : "");
        response.addHeader("Set-Cookie", cookieValue);
    }

    /** Constant-time comparison to avoid a timing oracle on the token. */
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