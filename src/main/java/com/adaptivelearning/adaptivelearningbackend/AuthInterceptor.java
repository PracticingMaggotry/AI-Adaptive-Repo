package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Set;

/**
 * Server-side gatekeeper for every *.html page in the app.
 *
 * This app has no Spring Security, so without this interceptor any static
 * .html file (dashboard.html, Admin.html, quizpage.html, etc.) is wide open
 * to anyone who knows or guesses the URL, logged in or not. The client-side
 * redirects already sprinkled through the pages (checking /api/me and
 * bouncing as needed) are easy to defeat — disable JavaScript, or just look
 * at the page source — so this interceptor enforces the same rules from the
 * server before the HTML is ever returned:
 *
 *   1. Anyone without a valid session can ONLY reach login.html / Register.html.
 *      Every other *.html request gets redirected to /login.html — typing
 *      a direct link, bookmarking a page, or removing the nav bar doesn't
 *      help; the server itself refuses to serve the page.
 *   2. Once logged in, students are kept out of admin-only pages
 *      (Admin.html, admindashboard.html) and admins are kept out of every
 *      student-facing page (dashboard.html, quizhub.html, learninghub.html,
 *      quizfinish.html, reports.html, quizpage.html, profile.html,
 *      materials.html).
 *   3. An already-logged-in user hitting login.html / Register.html is sent
 *      straight to the dashboard for their role instead of seeing the
 *      login form again.
 *
 * Only requests for *.html resources (and the bare "/" root) are gated.
 * CSS/JS/image/upload assets and every /api/**, /login, /register, /logout
 * endpoint pass straight through — those are either public by design or
 * already enforce their own session checks inside the controllers.
 *
 * IMPORTANT: this only protects pages when Spring Boot itself serves them
 * (e.g. http://localhost:8080/dashboard.html). If the frontend is instead
 * opened through a separate static file server (the CORS config in this
 * project allows http://localhost:5500, suggesting a Live Server-style dev
 * setup), requests never reach this backend at all, so this interceptor
 * cannot protect those pages. For real enforcement, serve every .html file
 * through this Spring Boot app rather than a separate static server.
 */
public class AuthInterceptor implements HandlerInterceptor {

    // Reachable without being logged in.
    private static final Set<String> PUBLIC_PAGES = Set.of(
            "/login.html", "/Register.html", "/register.html"
    );

    // Only an admin account should ever reach these.
    private static final Set<String> ADMIN_ONLY_PAGES = Set.of(
            "/admin.html", "/admindashboard.html"
    );

    // A logged-in admin should never see any of these — admins do admin
    // work, not student work.
    private static final Set<String> STUDENT_ONLY_PAGES = Set.of(
            "/dashboard.html", "/quizhub.html", "/learninghub.html",
            "/quizfinish.html", "/reports.html", "/quizpage.html",
            "/profile.html", "/materials.html"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        boolean isRoot = path.equals("/");
        boolean isHtmlPage = isRoot || path.toLowerCase(Locale.ROOT).endsWith(".html");

        if (!isHtmlPage) {
            return true; // CSS/JS/images/uploads/API calls are untouched
        }

        HttpSession session = request.getSession(false);
        String email = session != null ? (String) session.getAttribute("loggedInUserEmail") : null;
        boolean loggedIn = email != null && !email.isBlank();
        boolean isAdmin = loggedIn && Boolean.TRUE.equals(session.getAttribute("isAdmin"));

        // The bare root has no real page behind it — just route it like
        // everything else instead of letting it 404 or expose a directory.
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

        // Logged in but still pointed at the login/register screen — send
        // them on to where they actually belong.
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