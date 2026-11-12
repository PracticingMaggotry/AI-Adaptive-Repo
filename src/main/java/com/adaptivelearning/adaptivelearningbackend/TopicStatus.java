package com.adaptivelearning.adaptivelearningbackend;

/**
 * The one definition of when a topic counts as "needs practice" or "mastered" for learners.
 *
 * <p>Every learner page (Dashboard, Quiz Hub, Learning Hub, Profile, Reports) must use the same cut-offs, otherwise the
 * same topic reads "Needs Practice" on one screen and "On Track" on the next. The browser copy lives in
 * {@code static/api.js} ({@code TOPIC_WEAK_BELOW}, {@code TOPIC_MASTERED_AT}); keep the two in step.
 *
 * <p>These are display bands for a topic's average score. They do not choose the next quiz's difficulty (the ability
 * model does that). {@code MASTERED_AT} equals the 80% mastery target drawn on the learning curve, and the same bands
 * pick the results-page advice and the Target Problems fallback level.
 */
public final class TopicStatus {

    /** Topics averaging below this need practice. */
    public static final int WEAK_BELOW = 60;

    /** Topics averaging at or above this are mastered; between {@link #WEAK_BELOW} and this is "on track". */
    public static final int MASTERED_AT = 80;

    private TopicStatus() {}

    public static boolean needsPractice(double score) {
        return score < WEAK_BELOW;
    }

    public static boolean mastered(double score) {
        return score >= MASTERED_AT;
    }
}
