package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Set;

/**
 * Server-side gatekeeper for every *.html page. This app has no Spring
 * Security, so without this any static page is reachable by URL regardless
 * of login state — client-side redirects alone are trivially bypassed
 * (disable JS, view source).
 *
 * Rules:
 *   1. Logged-out users can only reach login.html / Register.html; every
 *      other *.html request is redirected to /login.html.
 *   2. Logged-in students can't reach admin-only pages and vice versa.
 *   3. An already-logged-in user hitting login/register is sent to their
 *      role's dashboard instead.
 *
 * Only *.html requests (and "/") are gated — CSS/JS/images/uploads/API
 * calls pass straight through, since those are either public by design or
 * enforce their own session checks.
 */
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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        boolean isRoot = path.equals("/");
        boolean isHtmlPage = isRoot || path.toLowerCase(Locale.ROOT).endsWith(".html");

        if (!isHtmlPage) {
            return true;
        }

        HttpSession session = request.getSession(false);
        String email = session != null ? (String) session.getAttribute("loggedInUserEmail") : null;
        boolean loggedIn = email != null && !email.isBlank();
        boolean isAdmin = loggedIn && Boolean.TRUE.equals(session.getAttribute("isAdmin"));

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
            response.sendRedirect("/admindashboard.html");
            return false;
        }

        if (!isAdmin && ADMIN_ONLY_PAGES.contains(path)) {
            response.sendRedirect("/dashboard.html");
            return false;
        }

        return true;
    }
}