package com.adaptivelearning.adaptivelearningbackend.ai;

import java.time.Instant;
import java.util.*;

/**
 * Standard Bayesian Knowledge Tracing (BKT) replay for one handout.
 *
 * <p>For each concept the model maintains P(L), the probability the learner knows the skill.
 * Each eligible binary response updates that probability using the conventional BKT parameters:
 * initial knowledge (P(L0)), learning (P(T)), slip (P(S)), and guess (P(G)). The posterior
 * after an observation is advanced through the learning transition before the next observation.
 * Mastery updates are local and deterministic; no language-model call is involved.
 *
 * <p>Only exact zero-credit and full-credit non-essay answers are eligible binary observations.
 * Partial-credit responses and essays remain in score/report analytics but are not coerced into
 * binary evidence. Guess probabilities by question format are explicit assumptions, not fitted
 * parameters. They are centralized in {@link #guess(String)}.
 *
 * <p>References: Corbett & Anderson (1995), "Knowledge tracing: Modeling the acquisition of
 * procedural knowledge", User Modeling and User-Adapted Interaction 4, 253-278.
 */
public final class MasteryModel {
    private MasteryModel() {}

    /**
     * Application-facing labels derived from the BKT posterior and response sequence. These labels are
     * not separate latent states in the standard BKT equations; {@link ConceptState#pKnown} is the model output.
     */
    public enum State { UNSEEN, WEAK, SLIP, SHAKY, LEARNED }

    /** One eligible response to a question mapped to {@code conceptKey}. */
    public record Event(String conceptKey, double credit, String type, Instant at) {
        /** Backward-compatible constructor; tier and response time are no longer BKT inputs. */
        public Event(String conceptKey, double credit, String type, String ignoredTier, int ignoredSeconds, Instant at) {
            this(conceptKey, credit, type, at);
        }
    }

    public static final class ConceptState {
        public final String key;
        public State state = State.UNSEEN;
        public double pKnown = AiPolicy.BKT_P_INIT;
        public int asked, misses, corrects, correctRun;
        public Instant lastAt, lastMissAt;
        public boolean recovered;
        public int missesBeforeRecovery;

        ConceptState(String key) { this.key = key; }

        /** Priority is derived from BKT posterior and repeated observed misses. */
        public double priority(Instant ignoredNow) {
            return (1.0 - pKnown) * (1.0 + 0.25 * Math.min(misses, 4));
        }
    }

    /** Fixed guess assumptions by response format; these are not empirically calibrated values. */
    static double guess(String type) {
        if (type == null) return AiPolicy.BKT_GUESS_DEFAULT;
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "TRUEFALSE" -> 0.50;
            case "MCQ", "PRACTICAL" -> 0.25;
            case "MATCHING", "SORTING" -> 0.10;
            case "FILLBLANK", "CONCEPTID" -> 0.05;
            default -> AiPolicy.BKT_GUESS_DEFAULT;
        };
    }

    /**
     * Replay all eligible observations; concept order in the result matches the handout catalog.
     * The {@code now} argument is retained for call-site compatibility. Basic BKT has no forgetting
     * transition, so elapsed wall-clock time does not change the posterior in this implementation.
     */
    public static LinkedHashMap<String, ConceptState> evaluate(Collection<String> conceptKeys,
                                                               Collection<Event> events, Instant ignoredNow) {
        LinkedHashMap<String, ConceptState> result = new LinkedHashMap<>();
        for (String key : conceptKeys) result.put(key, new ConceptState(key));

        Map<String, List<Event>> byConcept = new HashMap<>();
        for (Event event : events) {
            if (event == null || event.conceptKey() == null || event.at() == null
                    || !result.containsKey(event.conceptKey())) continue;
            if ("ESSAY".equalsIgnoreCase(event.type())) continue;
            if (event.credit() != AiPolicy.BKT_INCORRECT_CREDIT
                    && event.credit() != AiPolicy.BKT_CORRECT_CREDIT) continue;
            byConcept.computeIfAbsent(event.conceptKey(), ignored -> new ArrayList<>()).add(event);
        }

        for (ConceptState state : result.values()) {
            List<Event> history = byConcept.get(state.key);
            if (history == null || history.isEmpty()) continue;
            history.sort(Comparator.comparing(Event::at));
            replay(state, history);
        }
        return result;
    }

    private static void replay(ConceptState state, List<Event> history) {
        double pKnown = AiPolicy.BKT_P_INIT;
        int correctBeforeFirstMiss = 0;

        for (Event event : history) {
            double guess = guess(event.type());
            double slip = AiPolicy.BKT_P_SLIP;
            double posteriorGivenObservation;
            if (event.credit() == AiPolicy.BKT_CORRECT_CREDIT) {
                double numerator = pKnown * (1.0 - slip);
                double denominator = numerator + (1.0 - pKnown) * guess;
                posteriorGivenObservation = denominator > 0.0 ? numerator / denominator : pKnown;
                state.corrects++;
                state.correctRun++;
            } else {
                double numerator = pKnown * slip;
                double denominator = numerator + (1.0 - pKnown) * (1.0 - guess);
                posteriorGivenObservation = denominator > 0.0 ? numerator / denominator : pKnown;
                if (state.misses == 0) correctBeforeFirstMiss = state.corrects;
                state.misses++;
                state.lastMissAt = event.at();
                state.correctRun = 0;
            }

            // P(L_t+1) = P(L_t | observation) + (1-P(L_t | observation))*P(T).
            pKnown = posteriorGivenObservation
                    + (1.0 - posteriorGivenObservation) * AiPolicy.BKT_P_LEARN;
            state.asked++;
            state.lastAt = event.at();
        }

        state.pKnown = clamp(pKnown);
        if (state.misses == 0) {
            state.state = state.pKnown >= AiPolicy.BKT_LEARNED_PROBABILITY ? State.LEARNED : State.SHAKY;
        } else if (state.correctRun >= AiPolicy.BKT_RECOVERY_RUN
                && state.pKnown >= AiPolicy.BKT_LEARNED_PROBABILITY) {
            state.state = State.LEARNED;
            state.recovered = true;
            state.missesBeforeRecovery = state.misses;
        } else if (state.misses == 1 && correctBeforeFirstMiss >= AiPolicy.BKT_SLIP_PRIOR_CORRECTS
                && state.pKnown >= AiPolicy.BKT_WEAK_PROBABILITY) {
            state.state = State.SLIP;
        } else if (state.pKnown < AiPolicy.BKT_WEAK_PROBABILITY || state.correctRun == 0) {
            state.state = State.WEAK;
        } else {
            state.state = State.SHAKY;
        }
    }

    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    public static Map<State, Integer> summarize(Collection<ConceptState> states) {
        EnumMap<State, Integer> summary = new EnumMap<>(State.class);
        for (State state : State.values()) summary.put(state, 0);
        for (ConceptState concept : states) summary.merge(concept.state, 1, Integer::sum);
        return summary;
    }
}
