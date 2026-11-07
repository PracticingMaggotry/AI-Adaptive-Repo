package com.adaptivelearning.adaptivelearningbackend.ai;

import java.text.Normalizer;
import java.util.*;

/**
 * Decides when two sub-category labels are the same sub-category, so the AI cannot fill the data with
 * near-identical labels ("Data Structures", "data structure", "Intro to Data Structures"...).
 *
 * <p>Two layers cooperate. This class is the deterministic layer: it only ever calls two labels the same when they
 * differ by case, punctuation, spacing, word order, singular/plural, filler words ("of", "the", "and") or a
 * level word ("intro", "basics", "fundamentals", "overview"). It never guesses at meaning. Anything that needs
 * judgement ("Photosynthesis" vs "Plant Biology") is left to the AI layer in {@code SubCategoryService}, whose
 * proposals are checked by {@link #validateMerges} before anything is written.
 *
 * <p>Deliberately NOT treated as the same: labels that differ by a digit or numeral ("Algebra 1" / "Algebra 2",
 * "Calculus I" / "Calculus II"), by "Advanced", or by any other content word. A wrong merge corrupts data and is
 * hard to see; a missed merge is only a leftover for the AI pass.
 *
 * <p>Pure Java, no framework types: unit-testable without Spring.
 */
public final class SubCategoryMatcher {
    private SubCategoryMatcher() {}

    /** Length of the {@code sub_category} column. */
    public static final int MAX_LABEL_LENGTH = 120;

    /** Words that carry no subject. */
    private static final Set<String> FILLER = Set.of("a", "an", "the", "of", "to", "in", "on", "for", "and", "or", "with");

    /** Words that only say how deep a handout goes, not what it is about. "Advanced" is deliberately absent. */
    private static final Set<String> LEVEL_STEMS = stemAll(
            "intro", "introduction", "introductory", "basic", "basics", "fundamental", "fundamentals",
            "overview", "essentials");

    private static Set<String> stemAll(String... words) {
        Set<String> out = new HashSet<>();
        for (String w : words) out.add(TextSimilarity.stem(w));
        return Set.copyOf(out);
    }

    // ── Label hygiene ─────────────────────────────────────────────────────

    /**
     * A label safe to store and to put back into a prompt: control characters and angle brackets removed (labels
     * come from AI output over student-supplied text, so they are untrusted), whitespace collapsed, wrapping quotes
     * and trailing punctuation stripped, capped to the column length. {@code null} when nothing is left.
     */
    public static String clean(String raw) {
        if (raw == null) return null;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}<>]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        s = s.replaceAll("^[\"'`\\u201C\\u201D\\u2018\\u2019\\s]+|[\"'`\\u201C\\u201D\\u2018\\u2019\\s.,;:]+$", "");
        if (s.length() > MAX_LABEL_LENGTH) s = s.substring(0, MAX_LABEL_LENGTH).trim();
        return s.isEmpty() ? null : s;
    }

    // ── Identity ──────────────────────────────────────────────────────────

    /** Lower-cased, crudely stemmed words of a label, in order, without filler. Keeps digits, numerals, "c++", "c#". */
    static List<String> tokens(String label) {
        List<String> out = new ArrayList<>();
        if (label == null) return out;
        String s = label.toLowerCase(Locale.ROOT).replace("&", " and ");
        for (String raw : s.split("[^\\p{L}\\p{N}+#]+")) {
            if (raw.isEmpty() || FILLER.contains(raw)) continue;
            out.add(TextSimilarity.stem(raw));
        }
        return out;
    }

    /** The subject words of a label: its tokens minus level words (unless nothing else is left). */
    private static List<String> core(String label) {
        List<String> all = tokens(label);
        List<String> core = new ArrayList<>(all);
        core.removeIf(LEVEL_STEMS::contains);
        return core.isEmpty() ? all : core;
    }

    /** Order-insensitive identity: same subject words, whatever the order, case, plural or filler. */
    public static String key(String label) {
        return String.join(" ", new TreeSet<>(core(label)));
    }

    /** Spacing-insensitive identity: "Datastructures" / "Data Structures" / "Data-Structures". */
    static String compactKey(String label) {
        return String.join("", core(label));
    }

    /** True when the two labels name the same sub-category as far as pure string analysis can tell. */
    public static boolean sameLabel(String a, String b) {
        if (a == null || b == null) return false;
        String ka = key(a), kb = key(b);
        if (ka.isEmpty() || kb.isEmpty()) return a.trim().equalsIgnoreCase(b.trim());
        return ka.equals(kb) || compactKey(a).equals(compactKey(b));
    }

    // ── Upload-time resolution ────────────────────────────────────────────

    /**
     * What to store for a label the AI just proposed.
     *
     * @param label   the label to store, or {@code null} for "no sub-category"
     * @param reused  true when {@code label} is an existing sub-category that the proposal was folded into
     * @param original the proposal after cleaning (null if blank), for logging
     */
    public record Resolution(String label, boolean reused, String original) {}

    /**
     * Folds an AI-proposed label into an existing one when they are the same sub-category, drops it when it only
     * repeats the main category, and otherwise accepts it as new.
     *
     * @param existingMostUsedFirst existing labels under the same main category, most-used first (the first match wins,
     *                              so the spelling the data already uses most is the one kept)
     */
    public static Resolution resolve(String raw, String primaryCategory, List<String> existingMostUsedFirst) {
        String cleaned = clean(raw);
        if (cleaned == null) return new Resolution(null, false, null);
        if (primaryCategory != null && sameLabel(cleaned, primaryCategory)) return new Resolution(null, false, cleaned);
        if (existingMostUsedFirst != null) {
            for (String existing : existingMostUsedFirst) {
                if (sameLabel(cleaned, existing)) return new Resolution(existing, !existing.equals(cleaned), cleaned);
            }
        }
        return new Resolution(cleaned, false, cleaned);
    }

    // ── Cleanup of data that is already stored ────────────────────────────

    /**
     * Deterministic merge map for one main category: every label that is the same sub-category as a more used one
     * points at it. Only labels that need to change appear in the result.
     *
     * @param counts label -> how many handouts use it
     * @return alias -> canonical
     */
    public static Map<String, String> clusterIdentical(Map<String, Integer> counts) {
        List<String> labels = new ArrayList<>(counts.keySet());
        int n = labels.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (sameLabel(labels.get(i), labels.get(j))) union(parent, i, j);

        Map<Integer, List<String>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(labels.get(i));

        Map<String, String> out = new LinkedHashMap<>();
        for (List<String> group : groups.values()) {
            if (group.size() < 2) continue;
            String canonical = pickCanonical(group, counts);
            for (String l : group) if (!l.equals(canonical)) out.put(l, canonical);
        }
        return out;
    }

    /** Most used; ties go to the shorter, then the alphabetically first, so the choice is stable between runs. */
    static String pickCanonical(List<String> group, Map<String, Integer> counts) {
        return group.stream().min(
                Comparator.<String>comparingInt(l -> -counts.getOrDefault(l, 0))
                        .thenComparingInt(String::length)
                        .thenComparing(Comparator.naturalOrder())).orElseThrow();
    }

    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }
    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    // ── Checking merge proposals (from the AI, or posted back by the admin UI) ──

    /** One proposed rename of {@code from} into the existing label {@code to}. */
    public record Merge(String from, String to) {}

    /** Merges that passed, and a reason for each that did not. */
    public record Validated(Map<String, String> accepted, List<String> rejected) {}

    /**
     * Keeps only merges that are safe to apply. A merge is rejected when either label does not exist under this main
     * category (so an invented or misspelled label can never be written), when it merges a label into itself, when
     * the label already has another target, or when it would create a cycle. Chains (A into B, B into C) are
     * flattened so every alias points directly at its final label. Casing the AI drifted on is repaired against the
     * stored spelling.
     *
     * @param existing the labels that exist under the main category
     */
    public static Validated validateMerges(Collection<String> existing, List<Merge> proposed) {
        Set<String> exact = new HashSet<>(existing);
        Map<String, String> byLower = new HashMap<>();
        for (String e : existing) byLower.putIfAbsent(e.toLowerCase(Locale.ROOT).trim(), e);

        Map<String, String> accepted = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();

        for (Merge m : proposed == null ? List.<Merge>of() : proposed) {
            String from = m == null ? null : lookup(exact, byLower, m.from());
            String to = m == null ? null : lookup(exact, byLower, m.to());
            String shown = m == null ? "null" : m.from() + " -> " + m.to();
            if (from == null || to == null) { rejected.add(shown + ": label does not exist"); continue; }
            if (from.equals(to)) continue;
            if (accepted.containsKey(from)) { rejected.add(shown + ": " + from + " already has a target"); continue; }
            if (root(accepted, to).equals(from)) { rejected.add(shown + ": would create a cycle"); continue; }
            accepted.put(from, to);
        }

        Map<String, String> flat = new LinkedHashMap<>();
        for (String alias : accepted.keySet()) flat.put(alias, root(accepted, alias));
        return new Validated(flat, rejected);
    }

    /** The stored label for a name: an exact match wins (so "data structures" and "Data Structures" stay two labels), else a case-insensitive one. */
    private static String lookup(Set<String> exact, Map<String, String> byLower, String name) {
        if (name == null) return null;
        if (exact.contains(name)) return name;
        return byLower.get(name.toLowerCase(Locale.ROOT).trim());
    }

    private static String root(Map<String, String> map, String label) {
        String cur = label;
        for (int i = 0; i <= map.size() && map.containsKey(cur); i++) cur = map.get(cur);
        return cur;
    }
}
