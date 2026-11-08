package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Cheap, low-risk response headers that blunt XSS/clickjacking fallout. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    // Third-party code the pages load, pinned to exact versions (see the <script>/<link> tags, which also carry
    // SRI hashes). Allowed by path prefix rather than by whole host, so the policy does not trust everything
    // jsDelivr or cdnjs can serve. Changing a library version in a page means changing it here too.
    private static final String CHARTJS  = "https://cdn.jsdelivr.net/npm/chart.js@4.5.1/";
    private static final String KATEX    = "https://cdn.jsdelivr.net/npm/katex@0.16.9/";
    private static final String HLJS_NPM = "https://cdn.jsdelivr.net/npm/highlight.js@11.9.0/";
    private static final String HLJS_CDNJS = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/";

    /**
     * Content-Security-Policy.
     *
     * Known gap: script-src and style-src still allow 'unsafe-inline', because every page has inline
     * &lt;script&gt; blocks and many onclick="..." handlers (some built in JS template strings). That means this
     * policy does NOT stop an injected inline script from running. What it does enforce: no scripts or styles from
     * any other host, no framing by other sites, no plugins from other origins, a fixed &lt;base&gt;, forms that post
     * only to this site, and connect-src 'self', so injected script cannot send data to another server with
     * fetch/XHR/EventSource/sendBeacon. Removing 'unsafe-inline' needs the inline blocks moved to files and the
     * handlers replaced with addEventListener; then switch to nonces or drop it.
     *
     * object-src allows 'self' and blob: rather than 'none' because the admin content review shows a PDF from a
     * blob: URL in an iframe, and object-src 'none' can stop Chrome's built-in PDF viewer from rendering it
     * (not tested here; tighten to 'none' if that screen still works with it).
     */
    static final String POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline' " + CHARTJS + " " + KATEX + " " + HLJS_CDNJS,
            "style-src 'self' 'unsafe-inline' " + KATEX + " " + HLJS_NPM,
            "font-src 'self' " + KATEX,
            "img-src 'self' data: blob:",
            "connect-src 'self'",
            "frame-src 'self' blob:",
            "object-src 'self' blob:",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'self'");

    /** Set app.csp.report-only=true to log violations in the browser console without blocking anything. */
    @Value("${app.csp.report-only:false}")
    private boolean cspReportOnly;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader(cspReportOnly ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy", POLICY);

        // HSTS (RFC 6797): browsers refuse plain-HTTP for a year. Only sent over HTTPS (incl. behind a TLS proxy).
        boolean https = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        if (https) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }

        // Admin pages and API responses carry sensitive data: forbid caching by browsers and proxies.
        String path = request.getRequestURI();
        if (path.startsWith("/api/admin") || path.startsWith("/admin") || path.startsWith("/login/admin-mfa")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
