package com.adaptivelearning.adaptivelearningbackend.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure helpers that decide when already-processed handout content can be reused instead of paying the AI again.
 * No Spring, no I/O.
 */
public final class ContentReuse {
    private ContentReuse() {}

    /** Prefix of the current fingerprint; older rows carry a bare hex hash (see {@link #legacyFingerprint}). */
    public static final String PREFIX = "n1:";
    /** Below this many characters a text is too short to fingerprint reliably. */
    public static final int MIN_CHARS = 200;

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\"'(A-Z0-9])");

    /** Letters and digits only, lower case, single spaces: survives re-wrapping, smart quotes, bullets, dashes. */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean gap = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (gap && sb.length() > 0) sb.append(' ');
                gap = false;
                sb.appendCodePoint(Character.toLowerCase(cp));
            } else {
                gap = true;
            }
        }
        return sb.toString();
    }

    /** Fingerprint of the normalised text, or null when the text is too short or hashing is unavailable. */
    public static String fingerprint(String text) {
        String n = normalize(text);
        if (n.length() < MIN_CHARS) return null;
        String hex = sha256(n);
        return hex == null ? null : PREFIX + hex;
    }

    /** The hash used before normalisation existed (trim + lower case). Kept so older uploads still match. */
    public static String legacyFingerprint(String text) {
        if (text == null || text.trim().length() < MIN_CHARS) return null;
        return sha256(text.trim().toLowerCase(Locale.ROOT));
    }

    /** First {@code maxSentences} sentences of {@code summary}, at most {@code maxChars} long; "" if there is none. */
    public static String blurb(String summary, int maxSentences, int maxChars) {
        if (summary == null) return "";
        String flat = summary.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) return "";
        String[] parts = SENTENCE_END.split(flat);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < maxSentences; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        String out = sb.toString();
        if (out.length() <= maxChars) return out;
        int cut = out.lastIndexOf(' ', maxChars - 3);
        return out.substring(0, cut > 0 ? cut : maxChars - 3) + "...";
    }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null; // fail safe into "never reuse"
        }
    }
}
