package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.ItemDifficultyModel.Posterior;
import com.adaptivelearning.adaptivelearningbackend.ai.ItemDifficultyModel.Snapshot;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;

import java.util.Map;

/**
 * Selects the requested Bloom cognitive demand for a generated question.
 *
 * <p>BKT supplies the learner-specific baseline: lower concept-mastery probability selects a lower
 * cognitive-demand level, while higher probability permits a higher level. The Bayesian item model
 * may adjust that baseline by at most one Bloom level, but only when this learner has enough distinct
 * binary-response evidence at a level and its posterior uncertainty is acceptably small.
 *
 * <p>The 0.70 challenge target and adjustment limits are application policy choices, not universal
 * educational standards. The output is a generation target, not a claim that Bloom levels are an
 * interval scale or that the generated question has already been psychometrically calibrated.
 */
public final class AdaptiveQuestionSelector {
    private AdaptiveQuestionSelector() {}

    public enum EvidenceBasis { BKT_BASELINE, BKT_WITH_OBSERVED_DEMAND }

    public record Selection(int targetBloomLevel, int bktBaselineLevel, EvidenceBasis basis,
                            Double estimatedFacility, int observations, int distinctItems) {}

    /** Select a cognitive-demand target using BKT first and observed Bayesian facility only when supported. */
    public static Selection select(ConceptState concept, Snapshot snapshot) {
        return select(concept, snapshot, null);
    }

    /** Use a format-specific empirical facility estimate when the server has already fixed the question type. */
    public static Selection select(ConceptState concept, Snapshot snapshot, String requestedQuestionType) {
        int baseline = bktBaseline(concept);
        if (snapshot == null) {
            return new Selection(baseline, baseline, EvidenceBasis.BKT_BASELINE, null, 0, 0);
        }

        Candidate best = null;
        int low = Math.max(1, baseline - AiPolicy.ADAPTIVE_MAX_BLOOM_ADJUSTMENT);
        int high = Math.min(6, baseline + AiPolicy.ADAPTIVE_MAX_BLOOM_ADJUSTMENT);
        for (int level = low; level <= high; level++) {
            Candidate candidate = observedCandidate(level, snapshot, baseline, requestedQuestionType);
            if (candidate == null) continue;
            if (best == null || candidate.score < best.score
                    || (Math.abs(candidate.score - best.score) < 1e-12 && candidate.level < best.level)) {
                best = candidate;
            }
        }

        if (best == null) {
            return new Selection(baseline, baseline, EvidenceBasis.BKT_BASELINE, null, 0, 0);
        }
        return new Selection(best.level, baseline, EvidenceBasis.BKT_WITH_OBSERVED_DEMAND,
                best.posterior.facility(), best.observations, best.distinctItems);
    }

    /**
     * Maps the BKT posterior monotonically to Bloom's six ordered cognitive-process labels.
     * An unseen concept uses a conservative foundational diagnostic target rather than treating P(L0)
     * as if it were observed evidence.
     */
    static int bktBaseline(ConceptState concept) {
        if (concept == null || concept.state == State.UNSEEN) {
            return AiPolicy.ADAPTIVE_INITIAL_BLOOM_LEVEL;
        }
        double probability = Double.isFinite(concept.pKnown)
                ? Math.max(0.0, Math.min(1.0, concept.pKnown)) : AiPolicy.BKT_P_INIT;
        return Math.max(1, Math.min(6, (int) Math.round(1.0 + 5.0 * probability)));
    }

    private static Candidate observedCandidate(int bloomLevel, Snapshot snapshot, int baseline,
                                               String requestedQuestionType) {
        int correct = 0;
        int incorrect = 0;
        int distinctItems = 0;

        for (Map.Entry<String, ItemDifficultyModel.ItemEstimate> entry : snapshot.items().entrySet()) {
            ItemDifficultyModel.ItemEstimate item = entry.getValue();
            if (item.group().bloomLevel() != bloomLevel) continue;
            if (requestedQuestionType != null && !requestedQuestionType.isBlank()
                    && !requestedQuestionType.equalsIgnoreCase(item.group().questionType())) continue;
            correct += item.correct();
            incorrect += item.incorrect();
            distinctItems++;
        }

        int observations = correct + incorrect;
        if (observations < AiPolicy.ADAPTIVE_MIN_DEMAND_OBSERVATIONS
                || distinctItems < AiPolicy.ADAPTIVE_MIN_DISTINCT_ITEMS) return null;

        // Rebuild one Beta posterior for this Bloom level from item counts, applying the group prior once.
        Posterior posterior = new Posterior(
                ItemDifficultyModel.GROUP_PRIOR_ALPHA + correct,
                ItemDifficultyModel.GROUP_PRIOR_BETA + incorrect,
                correct, incorrect);
        if (posterior.standardDeviation() > AiPolicy.ADAPTIVE_MAX_FACILITY_SD) return null;

        double score = Math.abs(posterior.facility() - AiPolicy.ADAPTIVE_TARGET_FACILITY)
                + AiPolicy.ADAPTIVE_BASELINE_DISTANCE_PENALTY * Math.abs(bloomLevel - baseline);
        return new Candidate(bloomLevel, posterior, observations, distinctItems, score);
    }

    private record Candidate(int level, Posterior posterior, int observations, int distinctItems, double score) {}
}
