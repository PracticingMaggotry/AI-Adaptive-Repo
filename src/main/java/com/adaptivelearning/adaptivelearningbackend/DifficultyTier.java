package com.adaptivelearning.adaptivelearningbackend;

/**
 * Rule-based adaptive difficulty (thesis, "Difficulty Adjustment Formula"):
 * <ul>
 *   <li>score &ge; {@link #HARD_MIN} (80)  &rarr; increase difficulty one tier</li>
 *   <li>score {@link #MEDIUM_MIN}&ndash;79 (50&ndash;79) &rarr; maintain the current tier</li>
 *   <li>score &lt; {@link #MEDIUM_MIN} (50) &rarr; decrease difficulty one tier</li>
 * </ul>
 * The thresholds are runtime-configurable (HARD_DIFFICULTY_THRESHOLD / MEDIUM_DIFFICULTY_THRESHOLD);
 * the constants below are the defaults and match the seeded configuration values.
 */
public final class DifficultyTier {

    /** Default: scores at or above this move the student up a tier (mastery criterion 80-90%, Bloom/Guskey). */
    public static final double HARD_MIN = 80.0;

    /** Default: scores below this move the student down a tier; scores from here up to HARD_MIN keep the tier. */
    public static final double MEDIUM_MIN = 50.0;

    private static final String[] TIERS = {"Easy", "Medium", "Hard"};

    private DifficultyTier() {}

    /** Classifies an absolute score (0-100) into "Easy", "Medium", or "Hard" using the default thresholds. */
    public static String fromScore(double score) {
        return fromScore(score, HARD_MIN, MEDIUM_MIN);
    }

    /** Classifies an absolute score (0-100) into "Easy", "Medium", or "Hard" using the given thresholds. */
    public static String fromScore(double score, double hardMin, double mediumMin) {
        if (score >= hardMin) return "Hard";
        if (score >= mediumMin) return "Medium";
        return "Easy";
    }

    /** Normalises any casing of "easy"/"medium"/"hard" to its canonical label; null/unknown returns null. */
    public static String normalize(String tier) {
        if (tier == null) return null;
        for (String t : TIERS) {
            if (t.equalsIgnoreCase(tier.trim())) return t;
        }
        return null;
    }

    /**
     * Applies the adjustment rule to the tier the student just played: &ge; hardMin steps up (capped at Hard),
     * &lt; mediumMin steps down (floored at Easy), anything in between keeps the tier. An unrecognised current
     * tier falls back to classifying the score directly.
     */
    public static String next(String currentTier, double score, double hardMin, double mediumMin) {
        String current = normalize(currentTier);
        if (current == null) return fromScore(score, hardMin, mediumMin);
        int idx = 0;
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(current)) idx = i;
        if (score >= hardMin) idx = Math.min(idx + 1, TIERS.length - 1);
        else if (score < mediumMin) idx = Math.max(idx - 1, 0);
        return TIERS[idx];
    }

    /** {@link #next(String, double, double, double)} using the default thresholds. */
    public static String next(String currentTier, double score) {
        return next(currentTier, score, HARD_MIN, MEDIUM_MIN);
    }
}
