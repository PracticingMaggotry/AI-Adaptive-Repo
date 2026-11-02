package com.adaptivelearning.adaptivelearningbackend.ai;

import java.text.Normalizer;
import java.util.*;

/** Stable identity for a concept inside one handout, plus the small text helpers the AI layer shares. */
public final class ConceptKey {
    private ConceptKey() {}

    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "by",
            "with", "as", "at", "that", "this", "it", "its", "from", "which", "can", "has", "have", "had", "not",
            "but", "if", "then", "than", "into", "their", "they", "them", "also", "such", "may", "will", "each");

    /** "Chief Information Officer (CIO)" -> "chief-information-officer-cio". */
    public static String of(String term) {
        if (term == null) return "concept";
        String n = Normalizer.normalize(term, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (n.isEmpty()) return "concept";
        return n.length() > 60 ? n.substring(0, 60).replaceAll("-+$", "") : n;
    }

    /** Keys for a list of terms in order; duplicates get -2, -3 ... so every key in the handout is unique. */
    public static List<String> uniqueKeys(List<String> terms) {
        List<String> out = new ArrayList<>(terms.size());
        Map<String, Integer> seen = new HashMap<>();
        for (String t : terms) {
            String base = of(t);
            int n = seen.merge(base, 1, Integer::sum);
            out.add(n == 1 ? base : base + "-" + n);
        }
        return out;
    }

    /** Share of concept keys the two handouts have in common (0..1), relative to the smaller handout. */
    public static double overlap(Collection<String> a, Collection<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> sa = new HashSet<>(a);
        int common = 0;
        for (String k : new HashSet<>(b)) if (sa.contains(k)) common++;
        return (double) common / Math.min(sa.size(), new HashSet<>(b).size());
    }

    /** Lower-cased content words (stop words and 1-letter tokens removed). */
    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() < 2 || STOP.contains(raw)) continue;
            out.add(raw);
        }
        return out;
    }
}
