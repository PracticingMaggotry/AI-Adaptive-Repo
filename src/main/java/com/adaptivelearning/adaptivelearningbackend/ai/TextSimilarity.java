package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.*;

/**
 * Cheap, deterministic "is this the same question again?" check. A regenerated Target Problems question that only
 * rewords a mistake the student already made (or rewords another question in the same quiz) re-tests recall of the
 * wording, not the concept, so the server compares a content signature of each new question against the old ones.
 *
 * <p>Pure Java, no framework types: unit-testable without Spring. The comparison is word-based on purpose: stop
 * words, question scaffolding and the concept's own name are removed first, because every question about
 * "the UPDATE statement" shares those words without being a copy of another one.
 */
public final class TextSimilarity {
    private TextSimilarity() {}

    /** Function words and quiz scaffolding that carry no content (also the JSON keys of stored payloads). */
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "of", "to", "in", "on", "for", "and", "or", "is", "are", "was", "were", "be", "been",
            "it", "its", "this", "that", "these", "those", "which", "what", "who", "whom", "whose", "how", "do",
            "does", "did", "by", "as", "at", "with", "from", "into", "about", "than", "then", "so", "not", "no",
            "if", "can", "will", "would", "should", "could", "may", "might", "must", "any", "all", "each", "both",
            "you", "your", "their", "there", "when", "where", "while", "only", "also", "following", "true", "false",
            "correct", "answer", "question", "complete", "sentence", "handout", "identify", "single", "described",
            "clue", "clues", "option", "options", "excerpt", "blank", "blanks", "id", "mode", "typed", "dragdrop",
            "acceptedanswers", "correctanswer", "use", "used", "using");

    /** Lower-cased, crudely stemmed content words of {@code text}, in a set. */
    public static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) continue;
            String w = stem(raw);
            if (w.length() >= 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Strips common English suffixes and a trailing "e" so update/updates/updated/updating all meet. */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) w = w.substring(0, w.length() - 3);
        else if (w.length() > 4 && w.endsWith("ed")) w = w.substring(0, w.length() - 2);
        else if (w.length() > 4 && w.endsWith("es")) w = w.substring(0, w.length() - 2);
        else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
        if (w.length() > 3 && w.endsWith("e")) w = w.substring(0, w.length() - 1);
        return w;
    }

    /**
     * What a question is "about", as one string: its stem plus whatever carries the tested fact. For TRUEFALSE the
     * stem is the claim (the True/False key is noise); for MCQ it is stem + correct option only (the distractors
     * would dilute the comparison); for FILLBLANK, CONCEPTID and the rest the stored payload (excerpt and blanks,
     * or clues and answer) is what actually distinguishes one question from another.
     */
    public static String signature(String type, String questionText, String correctAnswer, String payloadJson) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(questionText == null ? "" : questionText);
        switch (t) {
            case "TRUEFALSE" -> { /* the claim is the question text */ }
            case "MCQ", "PRACTICAL", "" -> {
                if (correctAnswer != null) sb.append(' ').append(correctAnswer);
            }
            default -> {
                if (correctAnswer != null) sb.append(' ').append(correctAnswer);
                if (payloadJson != null) sb.append(' ').append(payloadJson);
            }
        }
        return sb.toString();
    }

    /**
     * Similarity in [0,1] of two signatures after removing {@code ignore} (the concept's own words). Mean of Jaccard
     * and containment: Jaccard alone under-reports a short question that sits inside a longer one, containment
     * alone over-reports a one-word question.
     */
    public static double similarity(String a, String b, Set<String> ignore) {
        Set<String> x = tokens(a), y = tokens(b);
        if (ignore != null) { x.removeAll(ignore); y.removeAll(ignore); }
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        int inter = 0;
        for (String w : x) if (y.contains(w)) inter++;
        double jaccard = (double) inter / (x.size() + y.size() - inter);
        double containment = (double) inter / Math.min(x.size(), y.size());
        return 0.5 * (jaccard + containment);
    }

    /** Highest similarity of {@code candidate} to any of {@code others}; 0 when there are none. */
    public static double maxSimilarity(String candidate, Collection<String> others, Set<String> ignore) {
        double best = 0.0;
        if (others == null) return best;
        for (String o : others) best = Math.max(best, similarity(candidate, o, ignore));
        return best;
    }
}
