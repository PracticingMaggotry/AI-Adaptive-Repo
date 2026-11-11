package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side gatekeeper for every *.html page and every /api/** call. This app has no Spring
 * Security, so without this any static page is reachable by URL regardless of login state —
 * client-side redirects alone are trivially bypassed (disable JS, view source).
 *
 * Rules:
 *   1. Logged-out users can only reach login.html / Register.html; every
 *      other *.html request is redirected to /login.html.
 *   2. Logged-in students can't reach admin-only pages and vice versa.
 *   3. An already-logged-in user hitting login/register is sent to their
 *      role's dashboard instead.
 *   4. A logged-in student whose email has been banned or whose account has been suspended is signed
 *      out on their very next page load OR API call — not just at next login.
 *
 * CSS/JS/images and the public auth endpoints pass straight through.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PAGES = Set.of(
            "/login.html", "/Register.html", "/register.html", "/admin-mfa.html"
    );

    /**
     * Where an admin is sent when a redirect is needed (root, login/register while logged in, or a student-only page).
     * Direct to the Overview so the retired /admindashboard.html redirect page is not an extra hop. The URL fragment is
     * only used by the browser; the server-side checks below still see just the path.
     */
    private static final String ADMIN_LANDING = "/admin.html#/overview";

    // /admindashboard.html stays here while the file exists: it is a redirect page that other pages still link to.
    private static final Set<String> ADMIN_ONLY_PAGES = Set.of(
            "/admin.html", "/admindashboard.html", "/admin-stepup.html"
    );

    private static final Set<String> STUDENT_ONLY_PAGES = Set.of(
            "/dashboard.html", "/quizhub.html", "/learninghub.html",
            "/quizfinish.html", "/reports.html", "/quizpage.html",
            "/profile.html", "/sus.html"
    );

    /** The feedback (SUS) form: only enrolled study participants may open it. */
    private static final String FEEDBACK_PAGE = "/sus.html";

    /** Public auth endpoints — never subject to the live ban/suspension session check. */
    private static final Set<String> AUTH_ENDPOINTS = Set.of(
            "/login", "/login/verify", "/login/resend", "/register", "/verify-email", "/logout",
            "/login/admin-mfa", "/admin/mfa/enroll/start", "/admin/mfa/enroll/confirm"
    );

    @Autowired private AccountAccessService accountAccessService;
    @Autowired private ConsentService consentService;
    @Autowired private ActiveSessionRegistry activeSessions;
    @Autowired private AdminSessionGuard adminGuard;
    @Autowired private ParticipantCodeRepository participantCodes;

    /** Everything the checks need to judge one request: what was asked for, and who (if anyone) is asking. */
    private record Req(HttpServletRequest request, HttpServletResponse response, String path,
                       HttpSession session, String email, boolean isAdmin,
                       boolean isRoot, boolean isFeedbackPage, boolean isHtmlPage, boolean isApiCall) {

        boolean loggedIn() { return isSignedIn(email); }

        /** Signed in and not on a public auth endpoint: the requests the live session checks apply to. */
        boolean signedInOnProtectedPath() { return loggedIn() && !AUTH_ENDPOINTS.contains(path); }
    }

    /**
     * Runs the checks in a fixed order and stops at the first one that answers the request. Each {@code ended...} /
     * {@code missing...} check returns true when it has already written the response (redirect or JSON error), so the
     * request must not go any further.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        boolean isRoot = path.equals("/");
        boolean isFeedbackPage = isFeedbackPage(request);
        boolean isHtmlPage = isHtmlRequest(path, isRoot, isFeedbackPage);
        boolean isApiCall = path.startsWith("/api/");

        if (!isHtmlPage && !isApiCall) {
            return true;
        }

        HttpSession session = request.getSession(false);
        String email = sessionEmail(session);
        boolean isAdmin = isAdminSession(session, email);
        Req req = new Req(request, response, path, session, email, isAdmin,
                isRoot, isFeedbackPage, isHtmlPage, isApiCall);

        if (endedAdminSession(req) || endedBlockedAccount(req) || endedByPasswordChange(req)
                || endedByNewerLogin(req) || missingConsent(req)) {
            return false;
        }

        // API calls are otherwise authorised by each controller's own session check.
        if (!isHtmlPage) {
            return true;
        }

        String redirect = pageRedirect(req);
        if (redirect == null) {
            return true;
        }
        response.sendRedirect(redirect);
        return false;
    }

    private static boolean isHtmlRequest(String path, boolean isRoot, boolean isFeedbackPage) {
        return isRoot || isFeedbackPage || path.toLowerCase(Locale.ROOT).endsWith(".html");
    }

    private static String sessionEmail(HttpSession session) {
        return session != null ? (String) session.getAttribute("loggedInUserEmail") : null;
    }

    private static boolean isSignedIn(String email) {
        return email != null && !email.isBlank();
    }

    private static boolean isAdminSession(HttpSession session, String email) {
        return isSignedIn(email) && Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    /**
     * The feedback form is matched on the decoded, normalised, lower-cased path, so /SUS.html, /%73us.html,
     * /sus%2Ehtml, /sus.html;x=1 and /x/../sus.html are all treated as sus.html (the raw URI alone would let
     * some of them slip past both the .html test and the page rules).
     */
    private static boolean isFeedbackPage(HttpServletRequest request) {
        return FEEDBACK_PAGE.equals(StringUtils.cleanPath(
                UrlPathHelper.defaultInstance.getPathWithinApplication(request)).toLowerCase(Locale.ROOT));
    }

    // ---- session checks (each returns true when it has already answered the request) ----

    /**
     * Admin sessions: must have completed the second factor, and are subject to idle/absolute timeouts and
     * User-Agent binding (OWASP Session Management Cheat Sheet; NIST SP 800-63B-4 section 5.2). Sensitive
     * operations additionally need a recent step-up. A pre-existing admin session without MFA is rejected here.
     */
    private boolean endedAdminSession(Req r) throws IOException {
        if (!(r.signedInOnProtectedPath() && r.isAdmin())) {
            return false;
        }
        AdminSessionGuard.Verdict verdict = adminGuard.check(r.session(), r.request());
        if (verdict != AdminSessionGuard.Verdict.OK) {
            activeSessions.release(r.email(), r.session().getId());
            String reason = verdict.name().toLowerCase(Locale.ROOT);
            endSession(r, "/login.html?admin_ended=" + reason,
                    "{\"success\":false,\"adminSessionEnded\":true,\"message\":"
                            + "\"Your admin session ended (" + reason + "). Please sign in again.\"}");
            return true;
        }
        if (r.isApiCall() && AdminSessionGuard.requiresStepUp(r.request().getMethod(), r.path())
                && !adminGuard.stepUpFresh(r.session())) {
            challengeStepUp(r);
            return true;
        }
        return false;
    }

    /** Ban/suspension takes effect immediately, not just on next login. Admins can't be banned, so they skip the lookup. */
    private boolean endedBlockedAccount(Req r) throws IOException {
        if (!(r.signedInOnProtectedPath() && !r.isAdmin()) || !accountAccessService.isCurrentlyBlocked(r.email())) {
            return false;
        }
        endSession(r, "/login.html?suspended=1",
                "{\"success\":false,\"message\":\"Your session was ended — this account has been suspended or banned.\"}");
        return true;
    }

    /**
     * A password change ends every session that signed in before it (the session that made the change is
     * re-stamped, so it carries on).
     */
    private boolean endedByPasswordChange(Req r) throws IOException {
        if (!(r.signedInOnProtectedPath() && !r.isAdmin())
                || !activeSessions.isRevoked(r.email(), (Long) r.session().getAttribute("authAt"))) {
            return false;
        }
        endSession(r, "/login.html?pwchanged=1",
                "{\"success\":false,\"message\":\"Your password was changed, so this session was ended. Please sign in again.\"}");
        return true;
    }

    /** One live session per account: logging in elsewhere ends this one. */
    private boolean endedByNewerLogin(Req r) throws IOException {
        if (!r.signedInOnProtectedPath() || activeSessions.isCurrent(r.email(), r.session().getId())) {
            return false;
        }
        endSession(r, "/login.html?replaced=1",
                "{\"success\":false,\"message\":\"This account was logged in on another device, so this session was ended.\"}");
        return true;
    }

    /** Enrolled study participants must accept the consent notice before using AI features. */
    private boolean missingConsent(Req r) throws IOException {
        boolean consentApplies = r.loggedIn() && !r.isAdmin() && r.isApiCall() && requiresConsent(r.path());
        if (!consentApplies || consentService.hasConsented(r.email())) {
            return false;
        }
        sendJson(r.response(), HttpServletResponse.SC_FORBIDDEN, "{\"success\":false,\"consentRequired\":true,"
                + "\"message\":\"Please accept the study consent notice first.\"}");
        return true;
    }

    private static boolean requiresConsent(String path) {
        return path.startsWith("/api/quiz/") || path.startsWith("/api/ai/")
                || path.equals("/api/materials/upload")
                || (path.startsWith("/api/assigned-handouts/") && path.endsWith("/process"))
                || path.equals("/api/sus/submit");
    }

    // ---- responses ----

    /** Signs the session out, then answers a page load with a redirect and an API call with a 401 JSON body. */
    private static void endSession(Req r, String htmlRedirect, String jsonBody) throws IOException {
        r.session().invalidate();
        if (r.isHtmlPage()) {
            r.response().sendRedirect(htmlRedirect);
        } else {
            sendJson(r.response(), HttpServletResponse.SC_UNAUTHORIZED, jsonBody);
        }
    }

    /** A sensitive admin call without a fresh step-up: GET goes to the step-up page, anything else gets a 403. */
    private static void challengeStepUp(Req r) throws IOException {
        if ("GET".equalsIgnoreCase(r.request().getMethod())) {
            String q = r.request().getQueryString();
            String next = r.path() + (q == null ? "" : "?" + q);
            r.response().sendRedirect("/admin-stepup.html?next="
                    + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8));
        } else {
            sendJson(r.response(), HttpServletResponse.SC_FORBIDDEN, "{\"success\":false,\"stepUpRequired\":true,\"message\":"
                    + "\"Re-enter your authenticator code to continue.\"}");
        }
    }

    private static void sendJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(body);
    }

    // ---- page rules (only reached for *.html requests that passed every session check) ----

    /** Where this page request must be sent instead, or null when it may be served. */
    private String pageRedirect(Req r) {
        if (r.isRoot()) {
            return r.loggedIn() ? homeFor(r) : "/login.html";
        }
        if (!r.loggedIn()) {
            return PUBLIC_PAGES.contains(r.path()) ? null : "/login.html";
        }
        return signedInPageRedirect(r);
    }

    private String signedInPageRedirect(Req r) {
        if (PUBLIC_PAGES.contains(r.path())) {
            return homeFor(r);
        }
        // The feedback form is for enrolled study participants only. Anyone else (students outside the study, and
        // admins) is sent away before the page is served, so typing the address does not get them in.
        if (r.isFeedbackPage() && (r.isAdmin() || participantCodes.findByEmailIgnoreCase(r.email()).isEmpty())) {
            return homeFor(r);
        }
        if (r.isAdmin() && STUDENT_ONLY_PAGES.contains(r.path()) && !isAdminQuizPreview(r)) {
            return ADMIN_LANDING;
        }
        if (!r.isAdmin() && ADMIN_ONLY_PAGES.contains(r.path())) {
            return "/dashboard.html";
        }
        return null;
    }

    /**
     * The admin AI-testing sandbox embeds the real quizpage.html in an iframe so an admin can see exactly what a
     * student sees. Scoped narrowly to that one page + an explicit query flag — every other student-only page still
     * redirects normally, and this grants no additional data access (the admin session already has full access to
     * everything quizpage.html would show).
     */
    private static boolean isAdminQuizPreview(Req r) {
        return "/quizpage.html".equals(r.path()) && "1".equals(r.request().getParameter("adminPreview"));
    }

    private static String homeFor(Req r) {
        return r.isAdmin() ? ADMIN_LANDING : "/dashboard.html";
    }
}
