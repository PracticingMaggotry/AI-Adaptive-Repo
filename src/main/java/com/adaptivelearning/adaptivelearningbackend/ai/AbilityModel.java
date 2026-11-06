package com.adaptivelearning.adaptivelearningbackend.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Continuous difficulty adaptation. Replays a student's response log for one handout and estimates how hard the next
 * question should be, replacing "latest score &rarr; one of three tiers".
 *
 * <p>{@link MasteryModel} answers <i>does the student know this concept?</i>; this class answers <i>how demanding
 * should the next question on it be?</i>. The model is an Elo-style online Rasch model (Elo 1978; Klinkenberg,
 * Straatemeier &amp; van der Maas 2011; Pelánek 2016):
 * <ul>
 *   <li>expected credit = {@code g + (1-g)*sigmoid(theta - b)}, with {@code theta = handoutAbility + conceptOffset},
 *       {@code b} the logit difficulty of the question's demand level and {@code g} its guess rate;</li>
 *   <li>after each answer both the handout ability and the concept offset move by {@code K * (credit - expected)};</li>
 *   <li>{@code K} shrinks as evidence accumulates and grows back when evidence ages (21-day half-life), so a new or
 *       returning student is tracked quickly and a well-known one is not thrown by one bad answer;</li>
 *   <li>answers from Target Problems quizzes update only the concept offset: those quizzes are built from the
 *       student's weakest concepts, so counting them toward overall ability would bias it downward.</li>
 * </ul>
 * The planner then picks {@code b* = theta - logit(p*)} so the student is expected to succeed about {@code p*} of the
 * time, and maps it to the nearest of five demand levels. Pure Java, no framework types.
 */
public final class AbilityModel {
    private AbilityModel() {}

    /** One graded answer. {@code difficulty} is the logit difficulty of the question (see {@link #difficultyOf}). */
    public record Event(String conceptKey, double credit, String type, double difficulty, boolean targeted, Instant at) {}

    public static final class ConceptAbility {
        public final String key;
        /** Concept-specific shift from the handout ability (negative = weaker than the student's overall level). */
        public double offset;
        /** Evidence weight after ageing; 0 = nothing known, grows by 1 per answer, halves every 21 days. */
        public double evidence;
        public int answers;
        public Instant lastAt;

        ConceptAbility(String key) { this.key = key; }
    }

    public static final class Result {
        /** Handout-level ability (logit; 0 = typical start). */
        public final double theta;
        public final double handoutEvidence;
        public final int answers;
        public final Map<String, ConceptAbility> concepts;

        Result(double theta, double handoutEvidence, int answers, Map<String, ConceptAbility> concepts) {
            this.theta = theta;
            this.handoutEvidence = handoutEvidence;
            this.answers = answers;
            this.concepts = concepts;
        }

        /** Ability on one concept: handout ability plus that concept's offset (0 if never answered). */
        public double thetaFor(String conceptKey) {
            ConceptAbility c = conceptKey == null ? null : concepts.get(conceptKey);
            return theta + (c == null ? 0.0 : c.offset);
        }
    }

    // ── Probability model ──────────────────────────────────────────────────

    static double sigmoid(double x) { return 1.0 / (1.0 + Math.exp(-x)); }

    static double logit(double p) { return Math.log(p / (1.0 - p)); }

    /** Expected credit for ability {@code theta} on an item of logit difficulty {@code b} with guess rate {@code g}. */
    public static double expected(double theta, double b, double g) {
        return g + (1.0 - g) * sigmoid(theta - b);
    }

    /** Item difficulty whose success probability (guessing ignored) equals {@code pTarget} for ability {@code theta}. */
    public static double targetDifficulty(double theta, double pTarget) {
        double p = Math.max(0.05, Math.min(0.95, pTarget));
        return theta - logit(p);
    }

    // ── Demand levels ──────────────────────────────────────────────────────

    /** Nearest demand level (1..5) for a logit difficulty. */
    public static int levelFor(double b) {
        int best = 0;
        for (int i = 1; i < AiPolicy.DEMAND_LOGIT.length; i++) {
            if (Math.abs(AiPolicy.DEMAND_LOGIT[i] - b) < Math.abs(AiPolicy.DEMAND_LOGIT[best] - b)) best = i;
        }
        return best + 1;
    }

    /** Easy / Medium / Hard label for a level, kept so dashboards, quiz URLs and lessons still work. */
    public static String tierLabel(int level) {
        return level <= 2 ? "Easy" : level == 3 ? "Medium" : "Hard";
    }

    /** The tier label a student of handout ability {@code theta} is on (the level they are planned at for new material). */
    public static String tierFor(double theta) {
        return tierLabel(levelFor(targetDifficulty(theta, AiPolicy.TARGET_P_NEW)));
    }

    /** Success rate the planner aims for, by slot purpose. */
    public static double targetP(String purpose) {
        if (purpose == null) return AiPolicy.TARGET_P_NEW;
        return switch (purpose) {
            case "REMEDIATE" -> AiPolicy.TARGET_P_REMEDIATE;
            case "VERIFY" -> AiPolicy.TARGET_P_VERIFY;
            default -> AiPolicy.TARGET_P_NEW;
        };
    }

    /**
     * Demand level for one planned slot. {@code slotIndex} (1-based) cycles a small offset so a quiz mixes neighbouring
     * levels rather than asking everything at one.
     */
    public static int planLevel(Result ability, String conceptKey, String purpose, int slotIndex) {
        double spread = AiPolicy.DEMAND_SPREAD_LOGIT * (((slotIndex - 1) % 3 + 3) % 3 - 1);
        double b = targetDifficulty(ability.thetaFor(conceptKey), targetP(purpose)) + spread;
        return levelFor(b);
    }

    /** Logit difficulty of a stored question: its demand level if it has one, else its legacy tier. */
    public static double difficultyOf(Integer demandLevel, String tier) {
        if (demandLevel != null && demandLevel >= 1 && demandLevel <= AiPolicy.DEMAND_LOGIT.length) {
            return AiPolicy.DEMAND_LOGIT[demandLevel - 1];
        }
        if (tier == null) return AiPolicy.LEGACY_MEDIUM_LOGIT;
        return switch (tier.trim().toLowerCase(Locale.ROOT)) {
            case "easy" -> AiPolicy.LEGACY_EASY_LOGIT;
            case "hard" -> AiPolicy.LEGACY_HARD_LOGIT;
            default -> AiPolicy.LEGACY_MEDIUM_LOGIT; // "medium", "targeted" and unknown labels
        };
    }

    // ── Replay ─────────────────────────────────────────────────────────────

    private static double stepSize(double start, double floor, double decay, double evidence) {
        return Math.max(floor, start / (1.0 + decay * evidence));
    }

    private static double ageFactor(Instant from, Instant to) {
        if (from == null || to == null) return 1.0;
        double days = Duration.between(from, to).toSeconds() / 86400.0;
        return days <= 0 ? 1.0 : Math.pow(0.5, days / AiPolicy.ABILITY_EVIDENCE_HALF_LIFE_DAYS);
    }

    private static double clamp(double v, double limit) { return Math.max(-limit, Math.min(limit, v)); }

    /** Replays {@code events} (any order) up to {@code now}. Events with a null concept key move only the handout ability. */
    public static Result evaluate(Collection<Event> events, Instant now) {
        List<Event> sorted = new ArrayList<>();
        for (Event e : events) if (e != null && e.at() != null) sorted.add(e);
        sorted.sort(Comparator.comparing(Event::at));

        double theta = 0.0, handoutEvidence = 0.0;
        Instant lastHandout = null;
        int answers = 0;
        Map<String, ConceptAbility> concepts = new LinkedHashMap<>();

        for (Event e : sorted) {
            handoutEvidence *= ageFactor(lastHandout, e.at());
            ConceptAbility c = e.conceptKey() == null ? null
                    : concepts.computeIfAbsent(e.conceptKey(), ConceptAbility::new);
            if (c != null) c.evidence *= ageFactor(c.lastAt, e.at());

            double credit = Math.max(0.0, Math.min(1.0, e.credit()));
            double expected = expected(theta + (c == null ? 0.0 : c.offset), e.difficulty(), MasteryModel.guess(e.type()));
            double error = credit - expected;

            if (c != null) {
                c.offset = clamp(c.offset + stepSize(AiPolicy.ABILITY_K_CONCEPT_START, AiPolicy.ABILITY_K_CONCEPT_FLOOR,
                        AiPolicy.ABILITY_K_CONCEPT_DECAY, c.evidence) * error, AiPolicy.ABILITY_OFFSET_LIMIT);
                c.evidence += 1;
                c.answers++;
                c.lastAt = e.at();
            }
            if (!e.targeted()) {
                theta = clamp(theta + stepSize(AiPolicy.ABILITY_K_HANDOUT_START, AiPolicy.ABILITY_K_HANDOUT_FLOOR,
                        AiPolicy.ABILITY_K_HANDOUT_DECAY, handoutEvidence) * error, AiPolicy.ABILITY_THETA_LIMIT);
                handoutEvidence += 1;
                lastHandout = e.at();
            }
            answers++;
        }

        handoutEvidence *= ageFactor(lastHandout, now);
        for (ConceptAbility c : concepts.values()) c.evidence *= ageFactor(c.lastAt, now);
        return new Result(theta, handoutEvidence, answers, concepts);
    }
}
