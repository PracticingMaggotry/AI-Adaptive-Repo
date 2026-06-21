package com.adaptivelearning.adaptivelearningbackend;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real, server-side enforcement of the admin panel's IP Blocking feature.
 *
 * Previously, "blocked" IPs only ever lived in the admin's own browser
 * localStorage — nothing on the server ever checked them, so the feature
 * was purely cosmetic record-keeping. This filter is what actually makes a
 * ban work: it runs in front of EVERY request (not just *.html pages like
 * AuthInterceptor — this also covers /api/**, /login, /register, static
 * assets, everything), and rejects anything coming from a banned IP with
 * 403 before it reaches any controller.
 *
 * Registered automatically as a Spring-managed Filter via @Component —
 * Spring Boot's embedded servlet container auto-detects any Filter bean
 * and wires it in, so no manual registration in WebConfig is needed
 * (unlike AuthInterceptor, which is a HandlerInterceptor and must be
 * registered by hand).
 *
 * The blocked-IP set is cached in memory rather than queried from the
 * database on every request — IP blocking sits in front of literally every
 * request the app receives, so it has to be cheap. The cache is refreshed
 * immediately whenever an admin blocks or unblocks an IP (see
 * AdminController.blockIp / unblockIp, which call refresh() right after
 * writing to the database) and once at startup, so changes take effect on
 * the very next request — no restart, no polling delay.
 */
@Component
@Order(1)
public class IpBlockFilter implements Filter {

    @Autowired
    private BlockedIpRepository blockedIpRepository;

    private volatile Set<String> blockedIps = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * Re-reads the full blocked-IP list from the database into the
     * in-memory cache used by doFilter().
     */
    public void refresh() {
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        blockedIpRepository.findAll().forEach(b -> fresh.add(b.getIp()));
        blockedIps = fresh;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String ip = extractClientIp(httpRequest);

        if (blockedIps.contains(ip)) {
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                    "{\"success\":false,\"message\":\"Access denied. This IP address has been blocked by an administrator.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Resolves the real visitor IP. The app runs behind a reverse proxy in
     * production (see WebConfig's CORS allowance for the Railway domain),
     * so request.getRemoteAddr() alone would just return the proxy's own
     * internal address for every visitor — the actual client IP arrives in
     * the X-Forwarded-For header instead, with the original client as the
     * first entry in its comma-separated chain. Falls back to
     * getRemoteAddr() for local/direct connections (e.g. running the app
     * locally without a proxy in front of it).
     */
    public static String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}