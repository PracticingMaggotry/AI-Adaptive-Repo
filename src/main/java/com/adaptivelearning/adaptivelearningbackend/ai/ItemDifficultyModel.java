package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.*;

/**
 * Bayesian estimation of observed question facility, kept separate from concept mastery (BKT).
 *
 * <p>Facility is the probability of a correct response; estimated difficulty is {@code 1 - facility}.
 * Evidence is pooled first by Bloom cognitive level and normalized response format. A question-specific
 * estimate is then shrunk toward the corresponding demand-group estimate with a four-observation prior.
 * This partial-pooling approach avoids treating one or two responses to a newly generated question as a
 * reliable item calibration.
 *
 * <p>The likelihood is Bernoulli: only exact 0.0/1.0 objective credits are admitted. Essays and partial-credit
 * outcomes are excluded rather than silently converted into binary observations. Historical tier labels are
 * deliberately not used. The model therefore estimates observed facility by cognitive-demand/format group and,
 * where repeat evidence exists, by item. It is not IRT and does not claim psychometric item calibration.
 */
public final class ItemDifficultyModel {
    private ItemDifficultyModel() {}

    /** Weakly informative Beta(2,2) prior for each demand group (mean facility = 0.5). */
    public static final double GROUP_PRIOR_ALPHA = 2.0;
    public static final double GROUP_PRIOR_BETA = 2.0;

    /** Strength of the group-derived prior used to regularize an individual question estimate. */
    public static final double ITEM_PRIOR_STRENGTH = 4.0;

    public record Observation(String itemKey, Integer bloomLevel, String questionType, double credit) {}

    public record DemandGroup(int bloomLevel, String questionType) {}

    public record Posterior(double alpha, double beta, int correct, int incorrect) {
        public double facility() {
            return alpha / (alpha + beta);
        }

        /** Higher means responses to this item/group are more difficult on average. */
        public double difficulty() {
            return 1.0 - facility();
        }

        public double variance() {
            double total = alpha + beta;
            return (alpha * beta) / (total * total * (total + 1.0));
        }

        public double standardDeviation() {
            return Math.sqrt(variance());
        }

        public int observations() {
            return correct + incorrect;
        }
    }

    public record ItemEstimate(String itemKey, DemandGroup group, int attempts, int correct, int incorrect,
                               double priorFacility, Posterior posterior, double empiricalWeight) {}

    public record DemandEstimate(DemandGroup group, Posterior posterior) {}

    public record Snapshot(Map<DemandGroup, DemandEstimate> demandGroups,
                           Map<String, ItemEstimate> items,
                           int acceptedObservations,
                           int ignoredObservations) {}

    private static final class Counts {
        int correct;
        int incorrect;

        void add(boolean wasCorrect) {
            if (wasCorrect) correct++;
            else incorrect++;
        }

        int total() { return correct + incorrect; }
    }

    private static final class ItemBucket {
        final String key;
        final DemandGroup group;
        final Counts counts = new Counts();

        ItemBucket(String key, DemandGroup group) {
            this.key = key;
            this.group = group;
        }
    }

    /**
     * Build all group and item estimates from the supplied response history.
     * Invalid metadata, essays, and fractional credits are counted as ignored evidence.
     */
    public static Snapshot estimate(Collection<Observation> observations) {
        Map<DemandGroup, Counts> groupCounts = new LinkedHashMap<>();
        Map<String, ItemBucket> itemCounts = new LinkedHashMap<>();
        int accepted = 0;
        int ignored = 0;

        if (observations != null) {
            for (Observation observation : observations) {
                if (!eligible(observation)) {
                    ignored++;
                    continue;
                }
                DemandGroup group = new DemandGroup(observation.bloomLevel(), normalizeType(observation.questionType()));
                boolean correct = observation.credit() == 1.0;
                ItemBucket item = itemCounts.get(observation.itemKey());
                if (item != null && !item.group.equals(group)) {
                    // A question ID must not silently change its demand metadata across response rows.
                    ignored++;
                    continue;
                }
                if (item == null) {
                    item = new ItemBucket(observation.itemKey(), group);
                    itemCounts.put(observation.itemKey(), item);
                }
                groupCounts.computeIfAbsent(group, ignoredKey -> new Counts()).add(correct);
                item.counts.add(correct);
                accepted++;
            }
        }

        Map<DemandGroup, DemandEstimate> demandEstimates = new LinkedHashMap<>();
        for (Map.Entry<DemandGroup, Counts> entry : groupCounts.entrySet()) {
            Counts counts = entry.getValue();
            Posterior posterior = new Posterior(
                    GROUP_PRIOR_ALPHA + counts.correct,
                    GROUP_PRIOR_BETA + counts.incorrect,
                    counts.correct,
                    counts.incorrect);
            demandEstimates.put(entry.getKey(), new DemandEstimate(entry.getKey(), posterior));
        }

        Map<String, ItemEstimate> itemEstimates = new LinkedHashMap<>();
        for (ItemBucket item : itemCounts.values()) {
            Counts totalGroup = groupCounts.get(item.group);
            int otherCorrect = totalGroup.correct - item.counts.correct;
            int otherIncorrect = totalGroup.incorrect - item.counts.incorrect;

            // Leave-one-item-out group prior prevents an item's own responses from being counted twice.
            double priorFacility = (GROUP_PRIOR_ALPHA + otherCorrect)
                    / (GROUP_PRIOR_ALPHA + GROUP_PRIOR_BETA + otherCorrect + otherIncorrect);
            double priorAlpha = priorFacility * ITEM_PRIOR_STRENGTH;
            double priorBeta = (1.0 - priorFacility) * ITEM_PRIOR_STRENGTH;
            Posterior posterior = new Posterior(
                    priorAlpha + item.counts.correct,
                    priorBeta + item.counts.incorrect,
                    item.counts.correct,
                    item.counts.incorrect);
            double empiricalWeight = item.counts.total()
                    / (item.counts.total() + ITEM_PRIOR_STRENGTH);
            itemEstimates.put(item.key, new ItemEstimate(item.key, item.group, item.counts.total(),
                    item.counts.correct, item.counts.incorrect, priorFacility, posterior, empiricalWeight));
        }

        return new Snapshot(Collections.unmodifiableMap(demandEstimates),
                Collections.unmodifiableMap(itemEstimates), accepted, ignored);
    }

    private static boolean eligible(Observation observation) {
        if (observation == null || observation.itemKey() == null || observation.itemKey().isBlank()) return false;
        if (observation.bloomLevel() == null || observation.bloomLevel() < 1 || observation.bloomLevel() > 6) return false;
        if (observation.questionType() == null || observation.questionType().isBlank()) return false;
        String type = normalizeType(observation.questionType());
        if (!Set.of("MCQ", "TRUEFALSE", "PRACTICAL", "MATCHING", "SORTING", "FILLBLANK", "CONCEPTID").contains(type)) {
            return false;
        }
        return observation.credit() == 0.0 || observation.credit() == 1.0;
    }

    private static String normalizeType(String type) {
        return type.trim().toUpperCase(Locale.ROOT);
    }
}
