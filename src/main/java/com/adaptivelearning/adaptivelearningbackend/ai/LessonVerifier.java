package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.*;

/**
 * Checks each generated lesson unit against the handout passages it claims to come from, before a student sees it.
 * A unit passes only if it cites a passage that was actually sent, the concept's name appears in that passage, and
 * its example and check answer are made of words found there. This does not prove a statement is true; it catches
 * the common failure of a model writing from memory instead of from the handout.
 *
 * <p>Pure Java, no framework types.
 */
public final class LessonVerifier {
    private LessonVerifier() {}

    /** One unit as the model wrote it. {@code source} is a passage id such as "P3". */
    public record Unit(String conceptKey, String source, String explain, String example, String refutation,
                       String question, String answer) {}

    public record Result(boolean ok, Unit unit, String problem) {}

    /**
     * @param passages  passage id ("P3") to passage text, only the ones sent to the model
     * @param term      the concept's name in the handout
     * @param refutable true only when the student has an open weakness on this concept with a recorded wrong answer
     */
    public static Result check(Unit u, Map<String, String> passages, String term, boolean refutable) {
        String id = normalizeId(u.source());
        String passage = id == null ? null : passages.get(id);
        if (passage == null) return new Result(false, u, "cites a passage that was not sent: " + u.source());
        if (blank(u.explain())) return new Result(false, u, "empty explanation");
        if (blank(u.question()) || blank(u.answer())) return new Result(false, u, "missing check question or answer");

        Set<String> passageTokens = TextSimilarity.tokens(passage);
        Set<String> termTokens = TextSimilarity.tokens(term);
        if (!termTokens.isEmpty() && coverage(termTokens, passageTokens) < 0.5) {
            return new Result(false, u, "concept name not found in the cited passage");
        }
        if (!blank(u.example()) && coverage(TextSimilarity.tokens(u.example()), passageTokens) < AiPolicy.LESSON_EXAMPLE_MIN_OVERLAP) {
            return new Result(false, u, "example is not drawn from the cited passage");
        }
        if (coverage(TextSimilarity.tokens(u.answer()), passageTokens) < AiPolicy.LESSON_EXAMPLE_MIN_OVERLAP) {
            return new Result(false, u, "check answer is not drawn from the cited passage");
        }
        // The answer must not be handed over in the question itself.
        Set<String> qTokens = TextSimilarity.tokens(u.question());
        Set<String> aTokens = TextSimilarity.tokens(u.answer());
        aTokens.removeAll(TextSimilarity.tokens(term));
        if (!aTokens.isEmpty() && coverage(aTokens, qTokens) >= 0.9) {
            return new Result(false, u, "question gives away its own answer");
        }
        // A refutation is only legitimate against a misconception the student actually showed.
        Unit cleaned = refutable || blank(u.refutation()) ? u
                : new Unit(u.conceptKey(), u.source(), u.explain(), u.example(), "", u.question(), u.answer());
        return new Result(true, cleaned, null);
    }

    /** "P3", "p3", "[P3]" and "3" all mean passage 3. Returns null when it is not an id. */
    public static String normalizeId(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("[", "").replace("]", "").toUpperCase(Locale.ROOT);
        if (s.matches("\\d+")) s = "P" + s;
        return s.matches("P\\d+") ? s : null;
    }

    /** Share of {@code words} that also appear in {@code reference} (1.0 for an empty list). */
    static double coverage(Set<String> words, Set<String> reference) {
        if (words.isEmpty()) return 1.0;
        int hit = 0;
        for (String w : words) if (reference.contains(w)) hit++;
        return (double) hit / words.size();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
