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
 *
 * ── X-Forwarded-For spoofing defence ──────────────────────────────────────
 * X-Forwarded-For is appended by every hop in the proxy chain, so its value
 * looks like:  "client, proxy1, proxy2"  (leftmost = original client).
 * The problem: any client can send their own X-Forwarded-For header before
 * the request reaches the first real proxy, prepending an arbitrary IP to
 * the chain.  If we blindly trust [0] we get the attacker's chosen value,
 * not their real address.
 *
 * Defence strategy (safe without configuring a static proxy IP list):
 *   1. Only consult X-Forwarded-For when the direct TCP connection comes
 *      from a trusted source — i.e. a loopback or RFC-1918 private address,
 *      which is where a legitimate reverse proxy (Railway, nginx, etc.)
 *      always lives relative to this app.  A request whose remoteAddr is a
 *      public IP is a direct connection; there is no trustworthy proxy chain,
 *      so we use remoteAddr as-is and ignore the header entirely.
 *   2. When the direct connection IS from a private/loopback address we walk
 *      the XFF chain RIGHT-TO-LEFT, skipping any entry that is itself a
 *      private or loopback address (those are internal proxy hops we
 *      control), and return the first non-private address we find.  That
 *      address was added by a proxy we trust, not by the client.
 *   3. If the entire chain is private (unusual but possible in an all-
 *      internal network), fall back to remoteAddr — still a trusted hop.
 *
 * This is the same algorithm used by Spring's ForwardedHeaderFilter and by
 * OWASP's recommended XFF handling guidance.
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
     * Returns the real client IP, resisting X-Forwarded-For spoofing.
     *
     * Algorithm:
     *  - If the direct TCP peer (remoteAddr) is a PUBLIC address, no trusted
     *    proxy is in front of us — use remoteAddr and ignore XFF entirely.
     *  - If remoteAddr is private/loopback (a trusted proxy), walk the XFF
     *    chain from right to left and return the rightmost NON-private entry.
     *    That entry was written by a proxy we control, not by the client.
     *  - If the whole XFF chain is private (all internal hops), fall back to
     *    remoteAddr — still a known, trusted address.
     */
    public static String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // No trusted proxy in front of us — the TCP peer IS the client.
        if (!isTrustedProxyAddress(remoteAddr)) {
            return remoteAddr;
        }

        // The direct connection came from a local/private proxy.
        // Examine XFF, but only trust entries appended by our own infrastructure.
        String xffHeader = request.getHeader("X-Forwarded-For");
        if (xffHeader == null || xffHeader.isBlank()) {
            return remoteAddr;
        }

        // XFF format: "client, proxy1, proxy2" — split and trim each token.
        String[] hops = xffHeader.split(",");

        // Walk right-to-left: skip internal proxy hops, return the first
        // public (non-trusted) address — that one was added by a real proxy,
        // not forged by the client.
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrustedProxyAddress(hop)) {
                return hop;
            }
        }

        // Every hop in the chain was a private address (all-internal network).
        // remoteAddr is still a trusted hop — use it.
        return remoteAddr;
    }

    /**
     * Returns true for loopback and RFC-1918/RFC-4193 private addresses that
     * can only appear in a chain written by infrastructure we control.
     * A client on the public internet cannot inject one of these addresses
     * as the rightmost non-private entry in the XFF chain without already
     * sitting inside our private network — at which point they are not a
     * threat model we can address at the IP-filter layer anyway.
     *
     * Covers:
     *   127.x.x.x / ::1           — loopback
     *   10.x.x.x                  — RFC-1918 class A
     *   172.16.x.x – 172.31.x.x   — RFC-1918 class B
     *   192.168.x.x               — RFC-1918 class C
     *   fc00::/7 (fc… / fd…)      — IPv6 unique-local (RFC-4193)
     */
    static boolean isTrustedProxyAddress(String ip) {
        if (ip == null || ip.isBlank()) return false;

        // Strip IPv6 zone ID (e.g. "fe80::1%eth0") before matching.
        int zoneIdx = ip.indexOf('%');
        String addr = zoneIdx >= 0 ? ip.substring(0, zoneIdx) : ip;

        // IPv6 loopback
        if ("::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr)) return true;

        // IPv6 unique-local (fc00::/7 — starts with fc or fd)
        String lower = addr.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("fc") || lower.startsWith("fd")) return true;

        // IPv4 checks
        String[] parts = addr.split("\\.");
        if (parts.length != 4) return false; // not an IPv4 address
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            // 127.0.0.0/8
            if (a == 127) return true;
            // 10.0.0.0/8
            if (a == 10) return true;
            // 172.16.0.0/12
            if (a == 172 && b >= 16 && b <= 31) return true;
            // 192.168.0.0/16
            if (a == 192 && b == 168) return true;
        } catch (NumberFormatException e) {
            // Not a parseable IPv4 address — treat as untrusted.
            return false;
        }
        return false;
    }
}