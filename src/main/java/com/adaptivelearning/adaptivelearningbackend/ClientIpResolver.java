package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client IP for rate limiting and "last known IP" bookkeeping.
 *
 * X-Forwarded-For spoofing defence: any client can prepend a fake IP to X-Forwarded-For before it
 * reaches the first real proxy, so trusting XFF[0] blindly would let an attacker impersonate any
 * address. Instead, XFF is only consulted when the direct TCP peer is a private/loopback address
 * (i.e. a real reverse proxy); the chain is then walked right-to-left and the first non-private
 * entry is returned. Falls back to remoteAddr if the whole chain is private.
 *
 * NOTE: IPs are no longer used for banning (see BannedEmail) — this is only for rate limiters and audit info.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

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