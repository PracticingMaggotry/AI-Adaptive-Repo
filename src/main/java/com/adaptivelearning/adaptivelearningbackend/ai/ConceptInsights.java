package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt.ConceptInfo;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt.Example;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;

import java.time.Instant;
import java.util.*;

/**
 * Student-facing view of the mastery model.
 *
 * <p>{@link MasteryModel} keeps five internal states plus flags, counts and probabilities. That is the right level of
 * detail for the AI, and the wrong one for a person: too many categories, numbers that look precise but are not, and
 * words such as "weak" or "slip" that read as a verdict. This class is the only place that translates the model into
 * what a student sees, so every page shows the same four statuses in the same words.
 *
 * <table>
 *   <caption>Internal state to student status</caption>
 *   <tr><td>WEAK, SHAKY with a miss</td><td>{@link Status#NEEDS_PRACTICE}</td><td>the same concepts the AI is told to drill</td></tr>
 *   <tr><td>SLIP, SHAKY without a miss</td><td>{@link Status#ALMOST_THERE}</td><td>mostly right, one more check</td></tr>
 *   <tr><td>LEARNED (recovered = "fixed")</td><td>{@link Status#GOT_IT}</td><td>never shown as a weakness</td></tr>
 *   <tr><td>UNSEEN</td><td>{@link Status#NOT_TRIED}</td><td>not asked yet</td></tr>
 * </table>
 *
 * <p>"Review soon" is a tag, not a fifth status: a concept the student knew but has not touched for a while. The
 * student never sees p(known), priority scores, or the words weak/slip/shaky. Pure Java, no framework types.
 */
public final class ConceptInsights {
    private ConceptInsights() {}

    public enum Status { NEEDS_PRACTICE, ALMOST_THERE, GOT_IT, NOT_TRIED }

    /** Most concepts shown as "focus" on a summary surface (results page, dashboard). Keeps the list scannable. */
    public static final int FOCUS_MAX = 3;
    /** Longest piece of handout or answer text sent to the browser. */
    static final int TEXT_CHARS = 200;

    /** The newest wrong answer on a concept, shown only on request (expanded row). */
    public record Mistake(String question, String yourAnswer, String correctAnswer) {}

    /**
     * @param priority only for ordering across handouts; not sent to the browser
     */
    public record Item(String key, String term, String definition, Status status, boolean fixed, boolean reviewSoon,
                       String evidence, Mistake lastMistake, double priority) {}

    public record Counts(int total, int needsPractice, int almostThere, int gotIt, int notTried, int fixed, int reviewSoon) {}

    /** @param focus the most urgent NEEDS_PRACTICE items (at most {@link #FOCUS_MAX}); @param items every concept, grouped and ordered */
    public record View(Counts counts, List<Item> focus, List<Item> items) {}

    public static Status statusOf(ConceptState s) {
        return switch (s.state) {
            case WEAK -> Status.NEEDS_PRACTICE;
            case SHAKY -> s.misses > 0 ? Status.NEEDS_PRACTICE : Status.ALMOST_THERE;
            case SLIP -> Status.ALMOST_THERE;
            case LEARNED -> Status.GOT_IT;
            case UNSEEN -> Status.NOT_TRIED;
        };
    }

    /**
     * @param concepts           the handout's concepts in handout order
     * @param states             mastery per concept key (a missing key is treated as not tried)
     * @param examplesByConcept  newest-first wrong answers per concept key
     */
    public static View build(List<ConceptInfo> concepts, Map<String, ConceptState> states,
                             Map<String, List<Example>> examplesByConcept, Instant now) {
        List<Item> all = new ArrayList<>();
        for (ConceptInfo c : concepts) {
            ConceptState s = states.get(c.key());
            all.add(item(c, s, examplesByConcept == null ? null : examplesByConcept.get(c.key()), now));
        }

        int[] n = new int[Status.values().length];
        int fixed = 0, review = 0;
        for (Item it : all) {
            n[it.status().ordinal()]++;
            if (it.fixed()) fixed++;
            if (it.reviewSoon()) review++;
        }
        Counts counts = new Counts(all.size(), n[Status.NEEDS_PRACTICE.ordinal()], n[Status.ALMOST_THERE.ordinal()],
                n[Status.GOT_IT.ordinal()], n[Status.NOT_TRIED.ordinal()], fixed, review);

        // Stable sort: status group first, then what matters most inside the group; handout order breaks ties.
        List<Item> ordered = new ArrayList<>(all);
        ordered.sort(Comparator
                .comparingInt((Item it) -> it.status().ordinal())
                .thenComparing(ConceptInsights::withinGroup));
        List<Item> focus = new ArrayList<>();
        for (Item it : ordered) {
            if (it.status() != Status.NEEDS_PRACTICE || focus.size() >= FOCUS_MAX) continue;
            focus.add(it);
        }
        return new View(counts, List.copyOf(focus), List.copyOf(ordered));
    }

    /** Inside a group: most urgent first; fixed concepts lead the "got it" group (the good news); reviews due next. */
    private static int withinGroup(Item a, Item b) {
        return switch (a.status()) {
            case NEEDS_PRACTICE -> Double.compare(b.priority(), a.priority());
            case ALMOST_THERE -> Boolean.compare(b.reviewSoon(), a.reviewSoon());
            case GOT_IT -> a.fixed() == b.fixed() ? Boolean.compare(b.reviewSoon(), a.reviewSoon())
                    : Boolean.compare(b.fixed(), a.fixed());
            case NOT_TRIED -> 0;
        };
    }

    private static Item item(ConceptInfo c, ConceptState s, List<Example> examples, Instant now) {
        if (s == null) {
            return new Item(c.key(), c.term(), clip(c.definition()), Status.NOT_TRIED, false, false,
                    evidenceNotTried(), null, 0.0);
        }
        Status status = statusOf(s);
        boolean fixed = status == Status.GOT_IT && s.recovered;
        boolean reviewSoon = s.stale && (status == Status.ALMOST_THERE || status == Status.GOT_IT);
        Mistake mistake = null;
        boolean showMistake = status == Status.NEEDS_PRACTICE || (status == Status.ALMOST_THERE && s.misses > 0);
        if (showMistake && examples != null && !examples.isEmpty()) {
            Example e = examples.get(0);
            String mine = LearnerPrompt.clip(e.studentAnswer(), TEXT_CHARS);
            mistake = new Mistake(LearnerPrompt.clip(e.question(), TEXT_CHARS),
                    mine.isBlank() ? "(no answer)" : mine,
                    LearnerPrompt.clip(e.correctAnswer(), TEXT_CHARS));
        }
        double priority = status == Status.NEEDS_PRACTICE ? s.priority(now) : 0.0;
        return new Item(c.key(), c.term(), clip(c.definition()), status, fixed, reviewSoon,
                evidence(s, status, fixed, reviewSoon, now), mistake, priority);
    }

    private static String evidenceNotTried() { return "Not asked yet"; }

    /** One plain sentence about the record, built from counts the student can verify against their own quizzes. */
    static String evidence(ConceptState s, Status status, boolean fixed, boolean reviewSoon, Instant now) {
        String base = switch (status) {
            case NOT_TRIED -> evidenceNotTried();
            case NEEDS_PRACTICE -> {
                String t = "Missed " + s.misses + " of " + s.asked + (s.asked == 1 ? " question" : " questions");
                yield s.lastMissAt == null ? t : t + " \u00b7 last miss " + LearnerPrompt.ago(s.lastMissAt, now);
            }
            case ALMOST_THERE -> {
                if (s.misses > 0) yield "Right " + s.corrects + " of " + s.asked + " \u2014 one miss to recheck";
                yield s.corrects == 0 ? "Partly right so far" : "Right " + s.corrects + " of " + s.asked + " so far";
            }
            case GOT_IT -> fixed
                    ? "Fixed \u2014 missed " + s.missesBeforeRecovery + (s.missesBeforeRecovery == 1 ? " time" : " times")
                      + " earlier, now right " + s.correctRun + " in a row"
                    : "Right " + s.corrects + " of " + s.asked;
        };
        if (reviewSoon && s.lastAt != null) return base + " \u00b7 last practised " + LearnerPrompt.ago(s.lastAt, now);
        return base;
    }

    private static String clip(String v) { return LearnerPrompt.clip(v, TEXT_CHARS); }
}
