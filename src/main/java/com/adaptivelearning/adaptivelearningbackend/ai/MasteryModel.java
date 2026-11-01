package com.adaptivelearning.adaptivelearningbackend.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Replays a student's full response history for one handout and decides, per concept, what the AI should know:
 * is it still a weakness, was it a one-off slip, has it been learned since, or has it simply not been asked yet.
 *
 * <p>This replaces "the last 8 attempts, newest 20 wrong answers": every response is kept forever, nothing falls
 * out of a window, and time matters because knowledge decays between sessions. The model is a Bayesian Knowledge
 * Tracing variant:
 * <ul>
 *   <li>a wrong answer on a concept the student has otherwise answered correctly only nudges the estimate down
 *       (slip probability, higher on Hard items and for very fast wrong answers);</li>
 *   <li>a concept that was missed and then answered correctly {@link AiPolicy#RECOVERY_RUN} times in a row is
 *       reported as <b>recovered / now learned</b>, so old mistakes stop being drilled;</li>
 *   <li>between responses the estimate decays toward {@link AiPolicy#FORGET_FLOOR} with a half-life, so a concept
 *       that was learned weeks ago becomes due for a spaced check instead of being assumed known forever.</li>
 * </ul>
 * Pure Java: no framework types, so it is unit-testable without Spring.
 */
public final class MasteryModel {
    private MasteryModel() {}

    public enum State { UNSEEN, WEAK, SLIP, SHAKY, LEARNED }

    /** One graded answer to a question that tests {@code conceptKey}. {@code credit} is 0..1 (partial credit allowed). */
    public record Event(String conceptKey, double credit, String type, String tier, int timeSeconds, Instant at) {}

    public static final class ConceptState {
        public final String key;
        public State state = State.UNSEEN;
        public double pKnown = AiPolicy.P_INIT;
        public int asked, misses, corrects, correctRun;
        public Instant lastAt, lastMissAt;
        /** Missed earlier but has since been answered correctly often enough to count as learned. */
        public boolean recovered;
        public int missesBeforeRecovery;
        /** Learned/clean earlier, but not revisited for so long that the estimate has decayed below "learned". */
        public boolean stale;

        ConceptState(String key) { this.key = key; }

        /** Higher = more urgent to practise. Only meaningful for WEAK/SHAKY/SLIP concepts. */
        public double priority(Instant now) {
            double base = (1.0 - pKnown) * (1.0 + 0.25 * Math.min(misses, 4));
            if (lastMissAt != null && Duration.between(lastMissAt, now).toDays() <= 7) base *= 1.2;
            return base;
        }
    }

    /** Guess probability by question type (chance of a correct answer without knowing the concept). */
    static double guess(String type) {
        if (type == null) return 0.25;
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "TRUEFALSE" -> 0.50;
            case "MCQ", "PRACTICAL" -> 0.25;
            case "SORTING" -> 0.30;
            case "MATCHING" -> 0.10;
            case "FILLBLANK" -> 0.15;
            case "CONCEPTID", "ESSAY" -> 0.05;
            default -> 0.25;
        };
    }

    static double slip(String tier) {
        if (tier == null) return AiPolicy.SLIP_MEDIUM;
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "easy" -> AiPolicy.SLIP_EASY;
            case "hard" -> AiPolicy.SLIP_HARD;
            default -> AiPolicy.SLIP_MEDIUM;
        };
    }

    private static double decay(double p, double days) {
        if (days <= 0 || p <= AiPolicy.FORGET_FLOOR) return p;
        return AiPolicy.FORGET_FLOOR + (p - AiPolicy.FORGET_FLOOR) * Math.pow(0.5, days / AiPolicy.FORGET_HALF_LIFE_DAYS);
    }

    private static double daysBetween(Instant a, Instant b) {
        return Duration.between(a, b).toSeconds() / 86400.0;
    }

    /**
     * Evaluates every concept in {@code conceptKeys} (handout order is preserved in the result) against all
     * {@code events}. Events for unknown keys are ignored; events need not be sorted.
     */
    public static LinkedHashMap<String, ConceptState> evaluate(Collection<String> conceptKeys,
                                                               Collection<Event> events, Instant now) {
        LinkedHashMap<String, ConceptState> out = new LinkedHashMap<>();
        for (String k : conceptKeys) out.put(k, new ConceptState(k));

        Map<String, List<Event>> byConcept = new HashMap<>();
        for (Event e : events) {
            if (e == null || e.conceptKey() == null || !out.containsKey(e.conceptKey()) || e.at() == null) continue;
            byConcept.computeIfAbsent(e.conceptKey(), k -> new ArrayList<>()).add(e);
        }

        for (ConceptState cs : out.values()) {
            List<Event> list = byConcept.get(cs.key);
            if (list == null || list.isEmpty()) continue; // stays UNSEEN
            list.sort(Comparator.comparing(Event::at));
            replay(cs, list, now);
        }
        return out;
    }

    private static void replay(ConceptState cs, List<Event> list, Instant now) {
        double p = AiPolicy.P_INIT;
        Instant prev = null;
        int correctsBeforeFirstMiss = 0;
        boolean firstMissWasFast = false;

        for (Event e : list) {
            if (prev != null) p = decay(p, daysBetween(prev, e.at()));
            prev = e.at();

            double c = Math.max(0.0, Math.min(1.0, e.credit()));
            boolean miss = c < AiPolicy.MISS_CREDIT;
            boolean correct = c >= AiPolicy.CORRECT_CREDIT;
            boolean fast = miss && e.timeSeconds() > 0 && e.timeSeconds() <= AiPolicy.FAST_WRONG_SECONDS;

            double s = slip(e.tier());
            if (fast) s = Math.min(AiPolicy.SLIP_CAP, s + AiPolicy.FAST_WRONG_SLIP_BONUS);
            double g = guess(e.type());

            double pCorrectPost = p * (1 - s) / (p * (1 - s) + (1 - p) * g);
            double pWrongPost = p * s / (p * s + (1 - p) * (1 - g));
            double post = c * pCorrectPost + (1 - c) * pWrongPost;
            p = post + (1 - post) * AiPolicy.P_LEARN;

            cs.asked++;
            cs.lastAt = e.at();
            if (miss) {
                if (cs.misses == 0) { correctsBeforeFirstMiss = cs.corrects; firstMissWasFast = fast; }
                cs.misses++;
                cs.lastMissAt = e.at();
                cs.correctRun = 0;
            } else if (correct) {
                cs.corrects++;
                cs.correctRun++;
            } else {
                cs.correctRun = 0; // partial credit: neither a miss nor proof of recovery
            }
        }

        // Evidence ends at the last response, but "now" may be weeks later.
        double pAtLastEvidence = p;
        double pNow = decay(p, daysBetween(prev, now));
        cs.pKnown = pNow;
        cs.stale = pNow < p - 0.05;

        if (cs.misses == 0) {
            cs.state = pNow >= AiPolicy.LEARNED_P ? State.LEARNED : State.SHAKY;
            return;
        }
        // Recovery is judged on the evidence at the time it was given. Time then only makes the concept due for a
        // spaced refresher (stale = true); it must not turn a mistake the student has fixed back into a weakness.
        if (cs.correctRun >= AiPolicy.RECOVERY_RUN && pAtLastEvidence >= AiPolicy.RECOVERED_P) {
            cs.state = State.LEARNED;
            cs.recovered = true;
            cs.missesBeforeRecovery = cs.misses;
            return;
        }
        // One miss is a slip when it breaks a solid record (>= SLIP_PRIOR_CORRECTS correct answers before it), or when
        // it follows some success and the estimate is still above "weak" (or was a very fast, likely careless answer).
        boolean isolated = cs.misses == 1 && (correctsBeforeFirstMiss >= AiPolicy.SLIP_PRIOR_CORRECTS
                || ((correctsBeforeFirstMiss >= 1 || firstMissWasFast) && pNow >= AiPolicy.WEAK_P));
        if (isolated) {
            cs.state = State.SLIP;
        } else if (pNow < AiPolicy.WEAK_P || cs.correctRun == 0) {
            cs.state = State.WEAK;
        } else {
            cs.state = State.SHAKY;
        }
    }

    /** Counts per state, for coverage lines and dashboards. */
    public static Map<State, Integer> summarize(Collection<ConceptState> states) {
        EnumMap<State, Integer> m = new EnumMap<>(State.class);
        for (State s : State.values()) m.put(s, 0);
        for (ConceptState cs : states) m.merge(cs.state, 1, Integer::sum);
        return m;
    }
}
