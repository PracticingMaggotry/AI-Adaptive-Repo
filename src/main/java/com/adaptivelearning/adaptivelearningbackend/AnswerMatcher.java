package com.adaptivelearning.adaptivelearningbackend;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one place that decides whether a typed answer matches the key. Used by every type where the student types
 * the answer (fill-in-the-blank, "four ideas, one name"), so they all behave the same way: Unicode is normalised,
 * case is ignored, leading/trailing whitespace is ignored and repeated whitespace is collapsed.
 *
 * <p>Punctuation is deliberately NOT stripped or fuzzily matched: that could turn genuinely different answers into
 * the same one. A punctuation or wording variant that is really acceptable (a plural, an abbreviation) has to be in
 * the question's accepted answers.
 */
public final class AnswerMatcher {
    private AnswerMatcher() {}

    /** Comparison form of an answer: NFKC, trimmed, lower-cased, whitespace collapsed. Never null. */
    public static String normalize(String answer) {
        if (answer == null) return "";
        return Normalizer.normalize(answer, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    /** True when {@code typed} equals {@code canonical} or one of {@code accepted}, ignoring case and spacing. */
    public static boolean matches(String typed, String canonical, Collection<String> accepted) {
        String want = normalize(typed);
        if (want.isEmpty()) return false;
        if (want.equals(normalize(canonical)) && !normalize(canonical).isEmpty()) return true;
        if (accepted == null) return false;
        for (String candidate : accepted) {
            String c = normalize(candidate);
            if (!c.isEmpty() && want.equals(c)) return true;
        }
        return false;
    }

    /**
     * The accepted answers worth keeping: every entry that is not blank, is not the canonical answer and is not an
     * earlier entry again, where "the same" means the same after {@link #normalize}. This is what removes a variant
     * that only differs by capital letters or spacing (canonical "Perspectives", accepted "perspectives"), which
     * the checker already accepts and which tells the student and the reviewer nothing. The first spelling of each
     * distinct variant is kept, in order.
     */
    public static List<String> distinctVariants(String canonical, Collection<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        Set<String> seen = new HashSet<>();
        seen.add(normalize(canonical));
        for (String variant : raw) {
            if (variant == null) continue;
            String key = normalize(variant);
            if (key.isEmpty() || !seen.add(key)) continue;
            out.add(variant.trim());
        }
        return out;
    }
}
