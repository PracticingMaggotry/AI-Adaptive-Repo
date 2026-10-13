package com.adaptivelearning.adaptivelearningbackend;

/** Converts a 0-100 score into a difficulty tier label ("Easy" / "Medium" / "Hard"). */
public final class DifficultyTier {

    /** Scores at or above this are classified "Hard" (mastery criterion 80-90%, Bloom/Guskey). */
    public static final double HARD_MIN = 80.0;

    /** Scores at or above this (but below HARD_MIN) are classified "Medium" ("developing mastery", Renaissance 60-79%). */
    public static final double MEDIUM_MIN = 60.0;

    private DifficultyTier() {}

    /** Classifies an absolute score (0-100) into "Easy", "Medium", or "Hard". */
    public static String fromScore(double score) {
        if (score >= HARD_MIN) return "Hard";
        if (score >= MEDIUM_MIN) return "Medium";
        return "Easy";
    }
}