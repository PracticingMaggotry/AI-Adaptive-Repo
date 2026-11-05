package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Turns the mastery model into the compact text the AI reads, and into the fingerprint that decides whether a cached
 * lesson is still valid. Both come from the same classified state, so what the lesson was written for and what the
 * cache checks can never disagree.
 */
public final class LearnerPrompt {
    private LearnerPrompt() {}

    /** A stored wrong/partial answer, kept permanently (the source rows are never windowed away). */
    public record Example(String question, String studentAnswer, String correctAnswer, String missedPoints,
                          String feedback, String payload, Instant at, String type) {
        public Example(String question, String studentAnswer, String correctAnswer, String missedPoints,
                       String feedback, String payload, Instant at) {
            this(question, studentAnswer, correctAnswer, missedPoints, feedback, payload, at, null);
        }
    }

    public record ConceptInfo(String key, String term, String definition) {}

    public record Rendered(String text, String stateHash, int weak, int slips, int recovered, int unseen) {}

    /**
     * @param examplesByConcept newest-first examples per concept key
     * @param maxEntries        {@link AiPolicy#PROMPT_MAX_CONCEPT_ENTRIES} for quizzes, {@link AiPolicy#LESSON_MAX_CONCEPT_ENTRIES} for lessons
     */
    public static Rendered render(List<ConceptInfo> concepts, Map<String, ConceptState> states,
                                  Map<String, List<Example>> examplesByConcept, int maxEntries, Instant now) {
        Map<String, ConceptInfo> info = new HashMap<>();
        for (ConceptInfo c : concepts) info.put(c.key(), c);

        List<ConceptState> weak = new ArrayList<>(), slips = new ArrayList<>(), recovered = new ArrayList<>();
        List<ConceptState> unseen = new ArrayList<>();
        int asked = 0;
        for (ConceptInfo c : concepts) {
            ConceptState s = states.get(c.key());
            if (s == null) continue;
            if (s.state != State.UNSEEN) asked++;
            switch (s.state) {
                case WEAK -> weak.add(s);
                case SHAKY -> { if (s.misses > 0) weak.add(s); }
                case SLIP -> slips.add(s);
                case LEARNED -> { if (s.recovered) recovered.add(s); }
                case UNSEEN -> unseen.add(s);
            }
        }
        weak.sort(Comparator.comparingDouble((ConceptState s) -> s.priority(now)).reversed());
        slips.sort(Comparator.comparing((ConceptState s) -> s.lastMissAt == null ? Instant.EPOCH : s.lastMissAt).reversed());
        recovered.sort(Comparator.comparing((ConceptState s) -> s.lastAt == null ? Instant.EPOCH : s.lastAt).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append("LEARNER MODEL (built from every past answer on THIS handout; older answers are not dropped)\n");
        sb.append("Coverage: ").append(asked).append(" of ").append(concepts.size()).append(" concepts asked so far.\n\n");

        int budget = maxEntries;
        int payloadLeft = AiPolicy.PAYLOAD_EXAMPLE_CONCEPTS;

        sb.append("OPEN WEAKNESSES (still unresolved — practise these):\n");
        if (weak.isEmpty()) sb.append("  (none)\n");
        for (ConceptState s : weak) {
            if (budget-- <= 0) break;
            appendConcept(sb, info.get(s.key), s, examplesByConcept.get(s.key), now, payloadLeft-- > 0);
        }

        sb.append("\nONE-OFF SLIPS (answered wrong once but otherwise knows it — do NOT drill; at most one quick check):\n");
        if (slips.isEmpty()) sb.append("  (none)\n");
        for (ConceptState s : slips) {
            if (budget-- <= 0) break;
            appendConcept(sb, info.get(s.key), s, examplesByConcept.get(s.key), now, false);
        }

        sb.append("\nNOW LEARNED (was wrong earlier, since answered correctly — never describe these as weaknesses):\n");
        if (recovered.isEmpty()) sb.append("  (none)\n");
        for (ConceptState s : recovered) {
            if (budget-- <= 0) break;
            ConceptInfo ci = info.get(s.key);
            sb.append("  - ").append(ci == null ? s.key : ci.term()).append(": missed ").append(s.missesBeforeRecovery)
              .append("x earlier, now correct ").append(s.correctRun).append(" in a row")
              .append(s.lastAt == null ? "" : " (last " + ago(s.lastAt, now) + ")")
              .append('\n');
        }

        if (!unseen.isEmpty()) {
            sb.append("\nNOT YET ASKED: ");
            int shown = 0;
            for (ConceptState s : unseen) {
                if (shown++ >= 12) { sb.append(", ..."); break; }
                ConceptInfo ci = info.get(s.key);
                if (shown > 1) sb.append(", ");
                sb.append(ci == null ? s.key : ci.term());
            }
            sb.append('\n');
        }

        return new Rendered(sb.toString(), stateHash(states), weak.size(), slips.size(), recovered.size(), unseen.size());
    }

    private static void appendConcept(StringBuilder sb, ConceptInfo ci, ConceptState s, List<Example> examples,
                                      Instant now, boolean withPayload) {
        sb.append("  - ").append(ci == null ? s.key : ci.term())
          .append(" [").append(s.key).append("]: p(known)=").append(String.format(Locale.ROOT, "%.2f", s.pKnown))
          .append(", missed ").append(s.misses).append("x, correct ").append(s.corrects).append("x");
        if (s.lastMissAt != null) sb.append(", last miss ").append(ago(s.lastMissAt, now));
        sb.append('\n');
        if (ci != null && ci.definition() != null && !ci.definition().isBlank()) {
            sb.append("      handout says: ").append(clip(ci.definition(), AiPolicy.EXAMPLE_FIELD_CHARS)).append('\n');
        }
        if (examples == null) return;
        int shown = 0;
        for (Example e : examples) {
            if (shown >= AiPolicy.EXAMPLES_PER_CONCEPT) break;
            sb.append("      mistake: Q: ").append(clip(e.question(), AiPolicy.EXAMPLE_FIELD_CHARS))
              .append(" | student: ").append(blankTo(clip(e.studentAnswer(), AiPolicy.EXAMPLE_FIELD_CHARS), "(no answer)"));
            if (e.correctAnswer() != null && !e.correctAnswer().isBlank())
                sb.append(" | correct: ").append(clip(e.correctAnswer(), AiPolicy.EXAMPLE_FIELD_CHARS));
            if (e.missedPoints() != null && !e.missedPoints().isBlank())
                sb.append(" | missed: ").append(clip(e.missedPoints(), AiPolicy.EXAMPLE_FIELD_CHARS));
            sb.append('\n');
            if (withPayload && shown == 0 && e.payload() != null && !e.payload().isBlank()) {
                sb.append("      structure: ").append(clip(e.payload(), AiPolicy.PAYLOAD_CHARS)).append('\n');
            }
            shown++;
        }
    }

    /**
     * Fingerprint of the learner state a lesson depends on: which concepts are open weaknesses, slips or recovered.
     * Probabilities and counts are deliberately left out so a lesson is not regenerated after every answer — only when
     * the picture it describes actually changes.
     */
    public static String stateHash(Map<String, ConceptState> states) {
        List<String> parts = new ArrayList<>();
        for (ConceptState s : states.values()) {
            String tag = switch (s.state) {
                case WEAK -> "W";
                case SHAKY -> s.misses > 0 ? "W" : null;
                case SLIP -> "S";
                case LEARNED -> s.recovered ? "R" : null;
                case UNSEEN -> null;
            };
            if (tag != null) parts.add(s.key + ":" + tag);
        }
        Collections.sort(parts);
        return sha1(String.join(",", parts)).substring(0, 16);
    }

    static String ago(Instant then, Instant now) {
        long days = Duration.between(then, now).toDays();
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 14) return days + " days ago";
        if (days < 60) return (days / 7) + " weeks ago";
        return (days / 30) + " months ago";
    }

    static String clip(String v, int max) {
        if (v == null) return "";
        String flat = v.replaceAll("\\s+", " ").trim();
        return flat.length() > max ? flat.substring(0, max) + "..." : flat;
    }

    private static String blankTo(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }

    public static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + "0000000000000000";
        }
    }
}
