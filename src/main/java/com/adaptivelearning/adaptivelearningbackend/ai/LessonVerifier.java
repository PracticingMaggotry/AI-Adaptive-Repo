package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks each generated lesson unit against the handout passages it claims to come from, before a student sees it.
 * A unit passes only if it cites a passage that was actually sent, the concept's name appears in that passage, and
 * its example and check answer are made of words found there, and its check question is a new question rather than
 * a copy of the example. This does not prove a statement is true; it catches
 * the common failure of a model writing from memory instead of from the handout.
 *
 * <p>Two kinds of failure are told apart. If the explanation or example is not grounded in the handout, the unit is
 * rejected outright. If those are fine and only the check question is bad (missing, a copy of the example, gives its
 * answer away, or its answer is not from the passage), the result carries the same unit without the question so the
 * generated content can still be shown.
 *
 * <p>Pure Java, no framework types.
 */
public final class LessonVerifier {
    private LessonVerifier() {}

    /** One unit as the model wrote it. {@code source} is a passage id such as "P3". */
    public record Unit(String conceptKey, String source, String explain, String example, String refutation,
                       String question, String answer) {}

    /**
     * @param withoutQuestion when {@code ok} is false but the explanation and example are grounded: the unit with its
     *                        check question and answer blanked, safe to show without a "Check yourself" step.
     *                        Null when the content itself failed (or when the unit passed).
     */
    public record Result(boolean ok, Unit unit, String problem, Unit withoutQuestion) {
        public Result(boolean ok, Unit unit, String problem) { this(ok, unit, problem, null); }
    }

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

        Set<String> passageTokens = TextSimilarity.tokens(passage);
        Set<String> termTokens = TextSimilarity.tokens(term);
        if (!termTokens.isEmpty() && coverage(termTokens, passageTokens) < 0.5) {
            return new Result(false, u, "concept name not found in the cited passage");
        }
        if (!blank(u.example()) && coverage(TextSimilarity.tokens(u.example()), passageTokens) < AiPolicy.LESSON_EXAMPLE_MIN_OVERLAP) {
            return new Result(false, u, "example is not drawn from the cited passage");
        }

        // The explanation and example are grounded. A refutation is only legitimate against a misconception the
        // student actually showed.
        Unit cleaned = refutable || blank(u.refutation()) ? u
                : new Unit(u.conceptKey(), u.source(), u.explain(), u.example(), "", u.question(), u.answer());

        String questionProblem = questionProblem(u, passageTokens, term);
        if (questionProblem == null) return new Result(true, cleaned, null);

        // Only the check question is bad: keep what was generated and drop the question and its answer.
        Unit withoutQuestion = new Unit(cleaned.conceptKey(), cleaned.source(), cleaned.explain(), cleaned.example(),
                cleaned.refutation(), "", "");
        return new Result(false, u, questionProblem, withoutQuestion);
    }

    /** Why the unit's check question cannot be used, or null when it is fine. */
    private static String questionProblem(Unit u, Set<String> passageTokens, String term) {
        if (blank(u.question()) || blank(u.answer())) return "missing check question or answer";
        if (coverage(TextSimilarity.tokens(u.answer()), passageTokens) < AiPolicy.LESSON_EXAMPLE_MIN_OVERLAP) {
            return "check answer is not drawn from the cited passage";
        }
        // The check question must not just repeat the example printed right above it.
        if (!blank(u.example()) && repeatsExample(u.question(), u.example(), term)) {
            return "check question repeats the example";
        }
        // The answer must not be handed over in the question itself.
        Set<String> qTokens = TextSimilarity.tokens(u.question());
        Set<String> aTokens = TextSimilarity.tokens(u.answer());
        aTokens.removeAll(TextSimilarity.tokens(term));
        if (!aTokens.isEmpty() && coverage(aTokens, qTokens) >= 0.9) {
            return "question gives away its own answer";
        }
        return null;
    }

    /** A standalone number such as 17 or 3.4 (not the 3 in "3NF" or "step3"). */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}_.])\\d+(?:\\.\\d+)?(?![\\p{L}\\p{N}_])");

    /**
     * True when the question only restates the example. A question with numbers is a copy when every one of them
     * already appears in the example ("What does 17 / 5 return?" under "17 / 5 returns 3.4"); one new number makes
     * it a new case, even though it shares the concept's words. A question with no numbers is a copy when its
     * content words nearly match the example's.
     */
    static boolean repeatsExample(String question, String example, String term) {
        Set<String> qNums = numbers(question);
        if (!qNums.isEmpty()) return numbers(example).containsAll(qNums);
        return TextSimilarity.similarity(question, example, TextSimilarity.tokens(term))
                >= AiPolicy.LESSON_QUESTION_MAX_EXAMPLE_SIMILARITY;
    }

    private static Set<String> numbers(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        Matcher m = NUMBER.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
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
