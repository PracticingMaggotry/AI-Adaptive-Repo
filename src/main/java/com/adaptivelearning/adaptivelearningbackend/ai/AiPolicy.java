package com.adaptivelearning.adaptivelearningbackend.ai;

/**
 * Every tunable number of the AI layer lives here and nowhere else.
 *
 * <p>Before this class existed the same limits were repeated in javadoc, prompts and code and drifted apart
 * (javadoc said "5 attempts / 15 mistakes", code used 8 attempts / 20 mistakes). Code and comments must now
 * refer to these constants by name instead of repeating the literal.
 */
public final class AiPolicy {
    private AiPolicy() {}

    // ── Question counts ────────────────────────────────────────────────────
    public static final int QUIZ_MIN_QUESTIONS = 15;
    public static final int QUIZ_MAX_QUESTIONS = 30;
    /** Targeted quiz: size follows the number of weak concepts, within this range, hard-capped server-side. */
    public static final int TARGETED_MIN_QUESTIONS = 5;
    public static final int TARGETED_DEFAULT_MAX_QUESTIONS = 10;
    public static final int TARGETED_HARD_CAP = 15;
    /** One concept may take at most this many slots of an adapted/initial quiz (relaxed only if concepts run out). */
    public static final int MAX_QUESTIONS_PER_CONCEPT = 3;

    /** Quiz size by handout length (words): short 15, medium 20, long 25. */
    public static int quizSizeForWords(int words) {
        if (words < 1500) return 15;
        if (words <= 4000) return 20;
        return 25;
    }

    // ── What the model is told about the learner ───────────────────────────
    /** Concepts described in a quiz-generation prompt (most urgent first). */
    public static final int PROMPT_MAX_CONCEPT_ENTRIES = 15;
    /** Concepts described in a lesson prompt (lessons are short). */
    public static final int LESSON_MAX_CONCEPT_ENTRIES = 5;
    /** Mistake examples shown per concept. */
    public static final int EXAMPLES_PER_CONCEPT = 2;
    public static final int EXAMPLE_FIELD_CHARS = 200;
    /** Raw question payload is only attached to the single newest example of the top concepts. */
    public static final int PAYLOAD_EXAMPLE_CONCEPTS = 5;
    public static final int PAYLOAD_CHARS = 600;

    // ── Mastery model (see MasteryModel) ───────────────────────────────────
    public static final double P_INIT = 0.30;
    public static final double P_LEARN = 0.15;
    /** Probability that a student who knows the concept still answers wrong, by tier. */
    public static final double SLIP_EASY = 0.08, SLIP_MEDIUM = 0.12, SLIP_HARD = 0.20;
    /** A wrong answer given faster than this is treated as likely careless (higher slip probability). */
    public static final int FAST_WRONG_SECONDS = 4;
    public static final double FAST_WRONG_SLIP_BONUS = 0.25;
    public static final double SLIP_CAP = 0.45;
    /** Knowledge drifts toward this floor with the half-life below when a concept is not revisited. */
    public static final double FORGET_FLOOR = 0.35;
    public static final double FORGET_HALF_LIFE_DAYS = 21.0;
    public static final double LEARNED_P = 0.85;
    /** After an earlier miss, this probability plus two correct answers in a row counts as "now learned". */
    public static final double RECOVERED_P = 0.75;
    /** A single miss after at least this many correct answers is a slip, however far the estimate fell. */
    public static final int SLIP_PRIOR_CORRECTS = 2;
    public static final double WEAK_P = 0.50;
    public static final double CORRECT_CREDIT = 0.75;
    public static final double MISS_CREDIT = 0.50;
    public static final int RECOVERY_RUN = 2;

    // ── Remediation quality (see TextSimilarity, QuizBlueprint.planRemediation) ──
    /**
     * A REMEDIATE question whose content signature is at least this similar to a mistake the student already made, or
     * to another question of the same concept in the same quiz, is a near-copy and is regenerated. Measured on real
     * handout questions: copies and rewordings score 0.50-1.00, genuinely new framings 0.00-0.17.
     */
    public static final double NEAR_COPY_THRESHOLD = 0.45;
    /** Same, for question types whose signature includes the stored payload (FILLBLANK, CONCEPTID, ...), which echo handout wording by nature. */
    public static final double NEAR_COPY_THRESHOLD_PAYLOAD_TYPES = 0.80;
    /** Regenerate the REMEDIATE slots that were rejected, once per quiz; if the retry also fails the original is kept. */
    public static final boolean REMEDIATION_RETRY = true;

    // ── Blueprint shares for an adapted quiz ───────────────────────────────
    public static final double SHARE_REMEDIATE = 0.50;
    public static final double SHARE_NEW = 0.30;
    public static final double SHARE_VERIFY = 0.20;

    // ── Retrieval ──────────────────────────────────────────────────────────
    /** Handouts at or under this many words are sent whole; longer ones are sent as retrieved passages. */
    public static final int FULL_TEXT_WORD_LIMIT = 1800;
    public static final int PASSAGES_PER_CONCEPT = 2;
    public static final int PASSAGE_WORD_BUDGET = 2400;
    public static final int CHUNK_TARGET_WORDS = 160;

    // ── Concurrency (see AiScheduler) ──────────────────────────────────────
    public static final int SCHED_MAX_CONCURRENT = 10;
    public static final int SCHED_MIN_CONCURRENT = 2;
    /** Slots that only INTERACTIVE work may use, so a burst of quiz generation can never block lessons/grading. */
    public static final int SCHED_RESERVED_INTERACTIVE = 3;
    public static final int SCHED_PER_USER_MAX = 2;
    public static final long SCHED_MAX_WAIT_MS = 90_000L;
    public static final long SCHED_BULK_PROMOTE_MS = 20_000L;

    // ── Cost control ───────────────────────────────────────────────────────
    /** Placeholders — tune to the real plan. Amounts are USD per student per UTC day. */
    public static final double BUDGET_SOFT_USD = 0.40;
    public static final double BUDGET_HARD_USD = 1.00;
    /** Output-token cap for question generation = base + perQuestion * slots (was a flat 12000). */
    public static final int GEN_TOKENS_BASE = 800;
    public static final int GEN_TOKENS_PER_QUESTION = 520;

    // ── Calibration ────────────────────────────────────────────────────────
    /** Expected facility (share answered correctly) per tier; the same numbers the prompts quote. */
    public static final double[] EASY_RANGE = {0.80, 0.90};
    public static final double[] MEDIUM_RANGE = {0.60, 0.75};
    public static final double[] HARD_RANGE = {0.40, 0.60};
    public static final int CALIBRATION_MIN_RESPONSES = 30;

    /** Bump when a prompt changes meaning; stored with lessons/responses so results stay comparable. */
    public static final String PROMPT_VERSION = "2026-10-v2";
}
