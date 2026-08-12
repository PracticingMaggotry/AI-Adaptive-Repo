package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies an incoming request as MOBILE, TABLET, DESKTOP, or BOT for
 * PRESENTATION purposes only — i.e. choosing which layout/navigation/JS
 * bundle to serve.
 *
 * ══════════════════════════════════════════════════════════════════════
 * SECURITY WARNING — READ BEFORE USING THIS CLASS ANYWHERE ELSE
 * ══════════════════════════════════════════════════════════════════════
 * The User-Agent header and Client Hints headers this class reads are
 * supplied entirely by the client and are trivially spoofable with a
 * single browser extension, `curl -A`, or a proxy rule. The result of
 * classify() MUST NEVER be used for:
 *   - authentication or authorization decisions
 *   - rate limiting or abuse-prevention logic (use IP/account based
 *     limiters like LoginRateLimiter / DailyActionLimiter instead)
 *   - anything where a wrong guess could leak data or bypass a check
 * It exists ONLY to pick which template/JS bundle to serve. Treat its
 * output the same way you'd treat a CSS media query: a UX hint, not a
 * fact about the world.
 * ══════════════════════════════════════════════════════════════════════
 *
 * Hardening applied (see method javadocs for detail):
 *   - Every public method is exception-safe: malformed/hostile input
 *     always degrades to DeviceType.DESKTOP (the safest default — an
 *     unnecessarily "full" desktop page is a much smaller problem than a
 *     broken, truncated mobile page served to a real desktop user).
 *   - Input length is capped BEFORE any regex runs, so no attacker can
 *     use an oversized User-Agent header to cause pathological regex
 *     backtracking (ReDoS) or excess memory use.
 *   - Only pre-compiled, linear-time patterns are used — no nested or
 *     overlapping quantifiers, so matching cost is always
 *     O(pattern_count × capped_input_length) and cannot blow up on
 *     adversarial input the way something like (a+)+ can.
 *   - All matching is case-insensitive via Locale.ROOT, avoiding
 *     locale-dependent casing bugs (e.g. the Turkish-i problem) being
 *     used to slip a token past the classifier.
 *   - Control characters, CR/LF, and other non-printable bytes are
 *     stripped before the value is used anywhere — including before it
 *     is ever placed in a log line — closing off log/header injection
 *     ("log forging") via a crafted User-Agent.
 *   - Modern Client Hints (Sec-CH-UA-Mobile), when present and
 *     well-formed, are trusted over regex-guessing the legacy
 *     User-Agent string, since they're an explicit boolean the browser
 *     itself sets rather than free text to pattern-match.
 *   - applyVaryHeader() must be called on any response whose body
 *     differs by device class, so an upstream/CDN cache doesn't cache a
 *     mobile-rendered response under a URL and later serve it to a
 *     desktop client (or vice versa) — including a client deliberately
 *     warming the cache with a spoofed header first.
 *   - Classification is always recomputed fresh per request and never
 *     persisted to the session, so a spoofed value on one request can
 *     never "stick" and quietly influence a later, unrelated request.
 */
public final class DeviceDetector {

    private DeviceDetector() {}

    /** Presentation-only device classification. Never used for security decisions — see class javadoc. */
    public enum DeviceType {
        MOBILE, TABLET, DESKTOP, BOT
    }

    // ── Input hardening ──────────────────────────────────────────────

    /**
     * Hard cap on how much of the User-Agent header we will ever inspect.
     * Real browser UA strings are well under 512 bytes; anything beyond
     * that is either a broken client or a deliberate attempt to feed an
     * oversized string into pattern matching. The servlet container
     * (e.g. Tomcat's maxHttpHeaderSize) already bounds total header size,
     * but this class doesn't rely on container configuration — it
     * defends itself regardless of how or where it's deployed.
     */
    private static final int MAX_UA_LENGTH = 512;

    /** Strips ASCII control characters (including CR/LF) so a hostile header can never inject log lines or corrupt formatted output. */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    // ── Precompiled, linear-time classification patterns ─────────────
    // Every pattern below is a flat alternation of literal tokens with no
    // nested/overlapping quantifiers, so matching cost is always linear
    // in the (already capped) input length — it cannot be driven
    // superlinear by adversarial input.

    private static final Pattern BOT_PATTERN = Pattern.compile(
            "bot|crawl|spider|slurp|bingpreview|facebookexternalhit|" +
                    "whatsapp|telegrambot|discordbot|slackbot|curl|wget|" +
                    "python-requests|headlesschrome|phantomjs|scrapy",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLET_PATTERN = Pattern.compile(
            "ipad|tablet|nexus (7|9|10)|sm-t[0-9]|kindle|playbook|silk",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MOBILE_PATTERN = Pattern.compile(
            "iphone|ipod|windows phone|iemobile|blackberry|bb10|" +
                    "opera mini|opera mobi|fennec|mobile safari|android.*mobile",
            Pattern.CASE_INSENSITIVE);

    // Bare "Android" with no "Mobile" token is, by UA-string convention, Android-on-tablet.
    private static final Pattern ANDROID_NO_MOBILE_TOKEN = Pattern.compile(
            "android(?!.*mobile)", Pattern.CASE_INSENSITIVE);

    // ── Client Hints header name (modern, explicit, preferred when present) ──
    private static final String CH_MOBILE_HEADER = "Sec-CH-UA-Mobile"; // well-formed values are "?1" or "?0"
    private static final int MAX_CH_MOBILE_LENGTH = 4; // generous bound around "?1"/"?0"

    // ══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════

    /**
     * Classifies the request's device class. Never throws — any failure
     * to parse safely degrades to DESKTOP. Presentation hint only; see
     * the class-level SECURITY WARNING before using this result for
     * anything beyond choosing a template.
     */
    public static DeviceType classify(HttpServletRequest request) {
        if (request == null) return DeviceType.DESKTOP;

        try {
            String ua = safeUserAgent(request.getHeader("User-Agent"));

            // Bot check first: a crawler impersonating a phone UA (common for
            // mobile-friendliness checks) should still see full, complete
            // markup rather than a stripped-down mobile shell that would
            // under-represent the page to it.
            if (BOT_PATTERN.matcher(ua).find()) {
                return DeviceType.BOT;
            }

            Boolean clientHintMobile = readClientHintMobile(request);
            if (clientHintMobile != null) {
                boolean tabletShaped = TABLET_PATTERN.matcher(ua).find() || ANDROID_NO_MOBILE_TOKEN.matcher(ua).find();
                if (tabletShaped) return DeviceType.TABLET;
                return clientHintMobile ? DeviceType.MOBILE : DeviceType.DESKTOP;
            }

            // No Client Hints available (older browser, or the header was
            // stripped by an intermediate proxy) — fall back to legacy
            // User-Agent sniffing.
            if (TABLET_PATTERN.matcher(ua).find() || ANDROID_NO_MOBILE_TOKEN.matcher(ua).find()) {
                return DeviceType.TABLET;
            }
            if (MOBILE_PATTERN.matcher(ua).find()) {
                return DeviceType.MOBILE;
            }
            return DeviceType.DESKTOP;

        } catch (Exception e) {
            // Any unexpected failure (encoding weirdness, a future pattern
            // edit that behaves badly on some input, etc.) must never take a
            // request down. Fail safe to the least-surprising default.
            return DeviceType.DESKTOP;
        }
    }

    /** Convenience boolean for callers that only care about "small screen" vs not. */
    public static boolean isMobileOrTablet(HttpServletRequest request) {
        DeviceType type = classify(request);
        return type == DeviceType.MOBILE || type == DeviceType.TABLET;
    }

    /**
     * MUST be called on any response whose body is chosen based on
     * classify()'s result. Without this, a CDN or shared proxy cache
     * keyed purely on URL can serve a mobile-rendered page to a desktop
     * visitor (or vice versa) — including a client that deliberately
     * warms the cache first with a spoofed header to force this.
     * Uses addHeader (not setHeader) so it composes with any Vary value
     * already set elsewhere (e.g. CORS handling) instead of clobbering it.
     */
    public static void applyVaryHeader(HttpServletResponse response) {
        if (response == null) return;
        response.addHeader("Vary", "User-Agent, Sec-CH-UA-Mobile");
    }

    /**
     * Returns a version of the given string that is safe to write into a
     * log line: control characters (including CR/LF) are stripped and the
     * value is length-capped. Use this for ANY client-supplied header
     * value before logging it — not just User-Agent — to prevent log
     * forging (a crafted header containing "\r\n" plus a fake log line,
     * intended to plant misleading entries in your logs).
     */
    public static String sanitizeForLog(String value) {
        if (value == null) return "";
        String truncated = value.length() > MAX_UA_LENGTH ? value.substring(0, MAX_UA_LENGTH) : value;
        return CONTROL_CHARS.matcher(truncated).replaceAll("");
    }

    // ══════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Returns a length-capped, control-character-stripped, lowercased
     * User-Agent string, never null. This is the single choke point every
     * regex in this class runs against, so no code path can accidentally
     * feed a raw, unbounded header value into pattern matching.
     */
    private static String safeUserAgent(String rawUserAgent) {
        if (rawUserAgent == null) return "";
        String capped = rawUserAgent.length() > MAX_UA_LENGTH
                ? rawUserAgent.substring(0, MAX_UA_LENGTH)
                : rawUserAgent;
        String stripped = CONTROL_CHARS.matcher(capped).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT);
    }

    /**
     * Reads Sec-CH-UA-Mobile ("?1" / "?0"), the modern explicit signal.
     * Returns null if absent, oversized, or unparseable so the caller
     * falls back to User-Agent sniffing — this never throws and never
     * guesses at a malformed value.
     */
    private static Boolean readClientHintMobile(HttpServletRequest request) {
        try {
            String raw = request.getHeader(CH_MOBILE_HEADER);
            if (raw == null) return null;
            String value = raw.trim();
            if (value.isEmpty() || value.length() > MAX_CH_MOBILE_LENGTH) return null;
            if (value.equals("?1")) return Boolean.TRUE;
            if (value.equals("?0")) return Boolean.FALSE;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}