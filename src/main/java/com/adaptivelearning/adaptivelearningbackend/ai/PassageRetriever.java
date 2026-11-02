package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.*;

/**
 * Splits a handout into passages and returns only the ones a quiz blueprint needs. For long handouts this cuts the
 * input of every generation call from "the whole handout" to a bounded budget, which is the main cost driver for
 * heavy users. Short handouts are returned whole (retrieval would save little and could hide context).
 *
 * <p>Passages are verbatim slices of the original text, so the existing verbatim-excerpt validation of FILLBLANK
 * questions still passes against the full handout.
 */
public final class PassageRetriever {
    public record Passage(int id, String text, int words) {}

    public record Concept(String key, String term, String definition) {}

    public record Result(String text, boolean wholeHandout, int words, Map<String, List<Integer>> passagesByConcept) {}

    private final List<Passage> passages;
    private final List<Map<String, Integer>> termFreq = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final double avgLen;

    public PassageRetriever(String handoutText) {
        this.passages = split(handoutText == null ? "" : handoutText);
        double total = 0;
        for (Passage p : passages) {
            Map<String, Integer> tf = new HashMap<>();
            for (String t : ConceptKey.tokens(p.text())) tf.merge(t, 1, Integer::sum);
            termFreq.add(tf);
            for (String t : tf.keySet()) docFreq.merge(t, 1, Integer::sum);
            total += tf.values().stream().mapToInt(Integer::intValue).sum();
        }
        this.avgLen = passages.isEmpty() ? 1 : Math.max(1, total / passages.size());
    }

    public static int wordCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    public List<Passage> passages() { return passages; }

    /**
     * @param wantedConcepts concepts that need source material (order = priority)
     * @param fullText       the complete handout, returned as-is when it is short
     */
    public Result retrieve(List<Concept> wantedConcepts, String fullText, int fullTextWordLimit,
                           int perConcept, int wordBudget) {
        int words = wordCount(fullText);
        if (words <= fullTextWordLimit || passages.isEmpty()) {
            return new Result(fullText == null ? "" : fullText, true, words, Map.of());
        }
        Map<String, List<Integer>> byConcept = new LinkedHashMap<>();
        TreeSet<Integer> chosen = new TreeSet<>();
        int used = 0;
        // Round-robin across concepts so a long budget never goes entirely to the first concept.
        Map<String, List<Integer>> ranked = new LinkedHashMap<>();
        for (Concept c : wantedConcepts) ranked.put(c.key(), rank(c));
        for (int round = 0; round < perConcept; round++) {
            for (Concept c : wantedConcepts) {
                List<Integer> r = ranked.get(c.key());
                if (round >= r.size()) continue;
                int id = r.get(round);
                byConcept.computeIfAbsent(c.key(), k -> new ArrayList<>()).add(id);
                if (chosen.add(id)) {
                    used += passages.get(id).words();
                }
                if (used >= wordBudget) { round = perConcept; break; }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int id : chosen) {
            sb.append("[P").append(id + 1).append("] ").append(passages.get(id).text()).append("\n\n");
        }
        return new Result(sb.toString().trim(), false, used, byConcept);
    }

    /** Passage ids for one concept, best first (BM25-style score; ties broken by document order). */
    List<Integer> rank(Concept c) {
        Map<String, Double> query = new HashMap<>();
        for (String t : ConceptKey.tokens(c.term())) query.merge(t, 3.0, Double::sum);
        for (String t : ConceptKey.tokens(c.definition())) query.merge(t, 1.0, Double::sum);
        int n = passages.size();
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFreq.get(i);
            int len = tf.values().stream().mapToInt(Integer::intValue).sum();
            double s = 0;
            for (Map.Entry<String, Double> q : query.entrySet()) {
                Integer f = tf.get(q.getKey());
                if (f == null) continue;
                int df = docFreq.getOrDefault(q.getKey(), 1);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                double norm = f * 2.2 / (f + 1.2 * (0.25 + 0.75 * len / avgLen));
                s += q.getValue() * idf * norm;
            }
            score[i] = s;
        }
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) if (score[i] > 0) ids.add(i);
        ids.sort((x, y) -> {
            int c2 = Double.compare(score[y], score[x]);
            return c2 != 0 ? c2 : Integer.compare(x, y);
        });
        return ids;
    }

    /** Paragraph-based chunking: merge short paragraphs up to the target size, split very long ones by sentence. */
    static List<Passage> split(String text) {
        List<Passage> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curWords = 0;
        for (String para : text.split("\\R\\s*\\R+")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            int w = wordCount(p);
            if (w > AiPolicy.CHUNK_TARGET_WORDS * 2) {
                if (curWords > 0) { out.add(new Passage(out.size(), cur.toString().trim(), curWords)); cur.setLength(0); curWords = 0; }
                StringBuilder piece = new StringBuilder();
                int pw = 0;
                for (String sentence : p.split("(?<=[.!?])\\s+")) {
                    int sw = wordCount(sentence);
                    if (pw + sw > AiPolicy.CHUNK_TARGET_WORDS && pw > 0) {
                        out.add(new Passage(out.size(), piece.toString().trim(), pw));
                        piece.setLength(0); pw = 0;
                    }
                    piece.append(sentence).append(' ');
                    pw += sw;
                }
                if (pw > 0) out.add(new Passage(out.size(), piece.toString().trim(), pw));
                continue;
            }
            if (curWords + w > AiPolicy.CHUNK_TARGET_WORDS && curWords > 0) {
                out.add(new Passage(out.size(), cur.toString().trim(), curWords));
                cur.setLength(0); curWords = 0;
            }
            cur.append(p).append("\n\n");
            curWords += w;
        }
        if (curWords > 0) out.add(new Passage(out.size(), cur.toString().trim(), curWords));
        return out;
    }
}
