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
 * Server-side enforcement of the admin panel's IP block list. Runs in front
 * of every request (not just *.html pages like AuthInterceptor) and rejects
 * banned IPs with 403 before any controller sees them.
 *
 * The blocked-IP set is cached in memory (checked on every request, so it
 * has to be cheap) and refreshed on startup and whenever an admin
 * blocks/unblocks an IP (AdminController calls refresh() right after the
 * DB write) — changes take effect on the next request, no restart needed.
 *
 * X-Forwarded-For spoofing defence: any client can prepend a fake IP to
 * X-Forwarded-For before it reaches the first real proxy, so trusting
 * XFF[0] blindly would let an attacker impersonate any address. Instead:
 * only consult XFF when the direct TCP peer is a private/loopback address
 * (i.e. a real reverse proxy), then walk the chain right-to-left and return
 * the first non-private entry — that one was written by our proxy, not the
 * client. Falls back to remoteAddr if the whole chain is private. Same
 * algorithm as Spring's ForwardedHeaderFilter / OWASP's XFF guidance.
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

    /** Re-reads the blocked-IP list from the DB into the in-memory cache. */
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
     * Returns the real client IP, resisting X-Forwarded-For spoofing (see class javadoc).
     */
    public static String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (!isTrustedProxyAddress(remoteAddr)) {
            return remoteAddr;
        }

        String xffHeader = request.getHeader("X-Forwarded-For");
        if (xffHeader == null || xffHeader.isBlank()) {
            return remoteAddr;
        }

        String[] hops = xffHeader.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrustedProxyAddress(hop)) {
                return hop;
            }
        }

        return remoteAddr;
    }

    /**
     * True for loopback and RFC-1918/RFC-4193 private addresses — the only
     * addresses that can legitimately appear as infrastructure-written hops.
     */
    static boolean isTrustedProxyAddress(String ip) {
        if (ip == null || ip.isBlank()) return false;

        int zoneIdx = ip.indexOf('%');
        String addr = zoneIdx >= 0 ? ip.substring(0, zoneIdx) : ip;

        if ("::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr)) return true;

        String lower = addr.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("fc") || lower.startsWith("fd")) return true; // IPv6 unique-local

        String[] parts = addr.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 127) return true;                          // 127.0.0.0/8
            if (a == 10) return true;                            // 10.0.0.0/8
            if (a == 172 && b >= 16 && b <= 31) return true;      // 172.16.0.0/12
            if (a == 192 && b == 168) return true;                // 192.168.0.0/16
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }
}