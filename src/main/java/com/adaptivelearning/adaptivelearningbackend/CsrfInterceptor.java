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
 * Synchronizer-token CSRF protection for all state-mutating requests.
 *
 * How it works
 * ─────────────
 * 1. On the first GET (or any safe method) for a logged-in user, a random
 *    token is minted, stored in the session, and also sent to the browser as
 *    an HttpOnly=false cookie named "XSRF-TOKEN" (the conventional name that
 *    Axios and the Fetch API are commonly configured to read).
 *
 * 2. On every POST / PUT / DELETE / PATCH the interceptor reads the token
 *    back from the "X-XSRF-TOKEN" request header (sent by JS) or, for
 *    traditional HTML form submissions, from the "_csrf" form field.
 *
 * 3. If the request carries a valid session but the submitted token is absent
 *    or wrong, the request is rejected with 403.
 *
 * Safe-method exemptions (GET / HEAD / OPTIONS / TRACE) — per RFC 7231 these
 * must not cause side-effects, so they are never checked. Public endpoints
 * that require no session (/login, /register, /verify-email, /logout) are
 * exempted because a CSRF attack against them cannot escalate privilege:
 *
 *   /register      — creating an account you don't own is harmless to you
 *   /verify-email  — requires knowing a valid OTP from the target inbox;
 *                    an attacker cannot complete verification for an address
 *                    they don't control, so no token is needed here either
 *   /login         — session fixation is mitigated by request.changeSessionId()
 *                    in AuthController immediately after a successful match
 *   /logout        — an attacker-initiated logout is annoying but not a
 *                    privilege escalation
 *
 * Cross-origin SameSite note: if the cookie's SameSite attribute is "Strict"
 * or "Lax" (the default for modern browsers) a cross-origin POST will not
 * carry the cookie at all, so the double-submit pattern also blocks CSRF at
 * the browser level. This class adds server-side verification on top of that.
 *
 * Cookie Secure flag: whether the XSRF-TOKEN cookie is marked Secure (i.e.
 * only ever sent by the browser over HTTPS) is controlled by the
 * app.cookie.secure property rather than being hardcoded. This makes the
 * "every environment this app is deployed to is HTTPS-only" assumption an
 * explicit, overridable setting instead of a silent one baked into the
 * Set-Cookie string. Defaults to true (fail-safe for production); set
 * app.cookie.secure=false only in a local/dev profile that serves the app
 * over plain http://, since a browser will otherwise silently refuse to
 * store a Secure cookie sent over a non-TLS connection — which would show
 * up as every state-changing request failing with "CSRF token missing"
 * rather than as an obvious configuration error.
 *
 * Registered as a Spring bean (rather than instantiated with `new` in
 * WebConfig) specifically so @Value below is actually populated —
 * field injection is a no-op on objects Spring never constructs.
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final String SESSION_KEY = "csrfToken";
    private static final String COOKIE_NAME = "XSRF-TOKEN";
    private static final String HEADER_NAME = "X-XSRF-TOKEN";
    private static final String FORM_FIELD  = "_csrf";

    /**
     * Whether the XSRF-TOKEN cookie carries the Secure attribute. See the
     * class javadoc above for the tradeoffs. Defaults to true.
     */
    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    /** Methods that do not mutate state and therefore need no CSRF check. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /**
     * Paths that are intentionally public and must work without a session
     * (and therefore without a CSRF token).
     *
     * /verify-email is added here because:
     *   - It is called from Register.html before any session exists.
     *   - The OTP itself is the proof-of-intent: an attacker who doesn't
     *     control the target inbox cannot obtain a valid OTP, so no extra
     *     CSRF token is needed to protect this endpoint.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/login", "/register", "/verify-email", "/logout"
    );

    private final SecureRandom rng = new SecureRandom();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String method = request.getMethod().toUpperCase();
        String path   = request.getRequestURI();

        // ── Always exempt: safe methods and public auth paths ────────────
        if (SAFE_METHODS.contains(method) || EXEMPT_PATHS.contains(path)) {
            // Still issue a token on GET requests for logged-in users so the
            // JS frontend can pick it up from the cookie.
            if ("GET".equals(method)) {
                HttpSession session = request.getSession(false);
                if (session != null && session.getAttribute("loggedInUserEmail") != null) {
                    ensureToken(session, response);
                }
            }
            return true;
        }

        // ── Only enforce for logged-in users ─────────────────────────────
        // Unauthenticated mutating calls (there shouldn't be any meaningful
        // ones, but just in case) fall through; AuthInterceptor / controller
        // session checks will reject them on their own terms.
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("loggedInUserEmail") == null) {
            return true;
        }

        String sessionToken = (String) session.getAttribute(SESSION_KEY);
        if (sessionToken == null) {
            // Session exists but has no token yet — issue one and reject this
            // request, forcing the client to retry with the new token.
            ensureToken(session, response);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF token missing — please retry.");
            return false;
        }

        // ── Validate the submitted token ──────────────────────────────────
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

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Generates a new CSRF token (if the session doesn't already have one),
     * stores it in the session, and writes it to the XSRF-TOKEN cookie so the
     * JS frontend can read it (HttpOnly=false is intentional — JS must be able
     * to read the value to send it back in the X-XSRF-TOKEN header).
     */
    private void ensureToken(HttpSession session, HttpServletResponse response) {
        String token = (String) session.getAttribute(SESSION_KEY);
        if (token == null) {
            byte[] bytes = new byte[32];
            rng.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(SESSION_KEY, token);
        }
        // Re-send the cookie so the browser always has a fresh copy.
        // SameSite=Lax is set via the Set-Cookie header directly because
        // Cookie.setSameSite() requires Servlet 6.1+ and may not be available
        // on all containers (e.g. Tomcat 10.x bundled with Spring Boot 3.0/3.1).
        // Secure is appended based on app.cookie.secure (see class javadoc) —
        // explicit and configurable rather than always-on or always-off.
        String cookieValue = COOKIE_NAME + "=" + token
                + "; Path=/"
                + "; SameSite=Lax"
                + (secureCookies ? "; Secure" : "");
        response.addHeader("Set-Cookie", cookieValue);
    }

    /**
     * Constant-time string comparison to prevent timing-based token oracle.
     * Returns false if either argument is null.
     */
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