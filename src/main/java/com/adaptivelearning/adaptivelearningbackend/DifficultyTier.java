package com.adaptivelearning.adaptivelearningbackend;

/**
 * Single source of truth for converting a 0-100 score into a difficulty tier
 * label ("Easy" / "Medium" / "Hard"). Before this existed, five different
 * places in the app each had their own cutoffs for what counts as Hard vs
 * Medium vs Easy, and quizfinish.html even recomputed its own version
 * client-side instead of trusting the backend's actual decision. Every
 * numeric difficulty cutoff now goes through this class.
 */
public final class DifficultyTier {

    /** Scores at or above this are classified "Hard". */
    public static final double HARD_MIN = 80.0;

    /** Scores at or above this (but below HARD_MIN) are classified "Medium". */
    public static final double MEDIUM_MIN = 50.0;

    private DifficultyTier() {}

    /**
     * Classifies an absolute score (0-100) into "Easy", "Medium", or "Hard".
     * This is a one-shot classification, not a hysteresis/anti-oscillation
     * rule — the wide Medium band (50-79) already absorbs most borderline
     * fluctuation without needing a separate "stay the same" exception.
     */
    public static String fromScore(double score) {
        if (score >= HARD_MIN) return "Hard";
        if (score >= MEDIUM_MIN) return "Medium";
        return "Easy";
    }
}