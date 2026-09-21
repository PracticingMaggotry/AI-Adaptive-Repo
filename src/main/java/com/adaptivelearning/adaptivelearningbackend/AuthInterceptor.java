package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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
            "/login.html", "/Register.html", "/register.html"
    );

    private static final Set<String> ADMIN_ONLY_PAGES = Set.of(
            "/admin.html", "/admindashboard.html"
    );

    private static final Set<String> STUDENT_ONLY_PAGES = Set.of(
            "/dashboard.html", "/quizhub.html", "/learninghub.html",
            "/quizfinish.html", "/reports.html", "/quizpage.html",
            "/profile.html"
    );

    /** Public auth endpoints — never subject to the live ban/suspension session check. */
    private static final Set<String> AUTH_ENDPOINTS = Set.of(
            "/login", "/register", "/verify-email", "/logout"
    );

    @Autowired private AccountAccessService accountAccessService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        boolean isRoot = path.equals("/");
        boolean isHtmlPage = isRoot || path.toLowerCase(Locale.ROOT).endsWith(".html");
        boolean isApiCall = path.startsWith("/api/");

        if (!isHtmlPage && !isApiCall) {
            return true;
        }

        HttpSession session = request.getSession(false);
        String email = session != null ? (String) session.getAttribute("loggedInUserEmail") : null;
        boolean loggedIn = email != null && !email.isBlank();
        boolean isAdmin = loggedIn && Boolean.TRUE.equals(session.getAttribute("isAdmin"));

        // Ban/suspension takes effect immediately, not just on next login: an active session gets torn
        // down mid-use. Admin accounts can't be banned or suspended, so they skip the DB lookup.
        if (loggedIn && !isAdmin && !AUTH_ENDPOINTS.contains(path)
                && accountAccessService.isCurrentlyBlocked(email)) {
            session.invalidate();
            if (isHtmlPage) {
                response.sendRedirect("/login.html?suspended=1");
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"success\":false,\"message\":\"Your session was ended — this account has been suspended or banned.\"}");
            }
            return false;
        }

        // API calls are otherwise authorised by each controller's own session check.
        if (!isHtmlPage) {
            return true;
        }

        if (isRoot) {
            response.sendRedirect(!loggedIn ? "/login.html" : (isAdmin ? "/admindashboard.html" : "/dashboard.html"));
            return false;
        }

        boolean isPublicPage = PUBLIC_PAGES.contains(path);

        if (!loggedIn) {
            if (isPublicPage) return true;
            response.sendRedirect("/login.html");
            return false;
        }

        if (isPublicPage) {
            response.sendRedirect(isAdmin ? "/admindashboard.html" : "/dashboard.html");
            return false;
        }

        if (isAdmin && STUDENT_ONLY_PAGES.contains(path)) {
            // Exception: the admin AI-testing sandbox embeds the real quizpage.html in an
            // iframe so an admin can see exactly what a student sees. Scoped narrowly to
            // that one page + an explicit query flag — every other student-only page still
            // redirects normally, and this grants no additional data access (the admin
            // session already has full access to everything quizpage.html would show).
            boolean isAdminQuizPreview = "/quizpage.html".equals(path)
                    && "1".equals(request.getParameter("adminPreview"));
            if (!isAdminQuizPreview) {
                response.sendRedirect("/admindashboard.html");
                return false;
            }
        }

        if (!isAdmin && ADMIN_ONLY_PAGES.contains(path)) {
            response.sendRedirect("/dashboard.html");
            return false;
        }

        return true;
    }
}