package com.adaptivelearning.adaptivelearningbackend;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RFC 6238 time-based one-time passwords (HMAC-SHA1, 6 digits, 30 s step), which is what Google
 * Authenticator, Microsoft Authenticator, Authy, 1Password, Bitwarden etc. implement.
 *
 * Pure JDK, no Spring: unit-testable against the RFC 6238 Appendix B vectors.
 *
 * Replay protection (RFC 6238 section 5.2: a verifier MUST NOT accept the second attempt of an OTP
 * after it was successfully validated): {@link #verify} only accepts a time step strictly greater
 * than {@code lastUsedStep}; the caller persists the returned step.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    /** Accept the previous, current and next step (+/- 30 s) to tolerate clock drift. */
    private static final int WINDOW = 1;
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RNG = new SecureRandom();

    private Totp() {}

    /** 160-bit random secret (RFC 4226 recommended length), Base32 encoded. */
    public static String generateSecret() {
        byte[] b = new byte[20];
        RNG.nextBytes(b);
        return base32Encode(b);
    }

    /** otpauth:// URI understood by authenticator apps / QR generators. */
    public static String otpauthUri(String issuer, String account, String secretBase32) {
        String i = enc(issuer), a = enc(account);
        return "otpauth://totp/" + i + ":" + a + "?secret=" + secretBase32 + "&issuer=" + i
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /**
     * @return the matched time step (to be stored as the new lastUsedStep), or -1 if the code is
     *         wrong, malformed, or its step is not newer than {@code lastUsedStep}.
     */
    public static long verify(String secretBase32, String submitted, long lastUsedStep, long nowMillis) {
        if (submitted == null) return -1;
        String s = submitted.replace(" ", "").trim();
        if (s.length() != DIGITS) return -1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;

        byte[] key = base32Decode(secretBase32);
        long current = Math.floorDiv(nowMillis / 1000, (long) STEP_SECONDS);
        long matched = -1;
        // Evaluate every candidate step without early exit so timing does not reveal which matched.
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            String expected = code(key, step, DIGITS);
            boolean eq = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    s.getBytes(StandardCharsets.UTF_8));
            if (eq && step > lastUsedStep && step > matched) matched = step;
        }
        return matched;
    }

    /** HOTP (RFC 4226) value for a counter, zero-padded to {@code digits}. */
    static String code(byte[] key, long counter, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16)
                    | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            int mod = 1;
            for (int i = 0; i < digits; i++) mod *= 10;
            return String.format("%0" + digits + "d", bin % mod);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Base32 (RFC 4648, no padding) ──

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : data) {
            buffer = (buffer << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        String in = s.replace("=", "").replace(" ", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < in.length(); i++) {
            int v = B32.indexOf(in.charAt(i));
            if (v < 0) throw new IllegalArgumentException("Invalid Base32 character");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
