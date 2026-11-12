package com.adaptivelearning.adaptivelearningbackend;

/**
 * The Easy / Medium / Hard label. It is an internal key only: it names the question bank a quiz is stored under, is
 * carried in quiz URLs and attempt records, and is the coarse label of the ability estimate (see
 * {@code ai.AbilityModel}). It does not decide difficulty: that is planned per question from the demand level, and
 * learner-facing pages do not show the label.
 */
public final class DifficultyTier {

    private static final String[] TIERS = {"Easy", "Medium", "Hard"};

    private DifficultyTier() {}

    /** Normalises any casing of "easy"/"medium"/"hard" to its canonical label; null/unknown returns null. */
    public static String normalize(String tier) {
        if (tier == null) return null;
        for (String t : TIERS) {
            if (t.equalsIgnoreCase(tier.trim())) return t;
        }
        return null;
    }
}
