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

    // ── Standard Bayesian Knowledge Tracing (see MasteryModel) ──────────────
    /** Default BKT prior probability that the learner already knows a concept. */
    public static final double BKT_P_INIT = 0.30;
    /** Probability of learning the concept between two eligible practice opportunities. */
    public static final double BKT_P_LEARN = 0.15;
    /** Probability of an incorrect response despite knowing the concept. Fixed assumption, not locally calibrated. */
    public static final double BKT_P_SLIP = 0.10;
    /** Fallback guess probability for formats without a more specific documented assumption. */
    public static final double BKT_GUESS_DEFAULT = 0.25;
    /** BKT only accepts exact binary evidence; partial credit and essays are excluded from the likelihood. */
    public static final double BKT_CORRECT_CREDIT = 1.0;
    public static final double BKT_INCORRECT_CREDIT = 0.0;
    /** Presentation/selection cutoffs applied to the posterior; these are not fitted BKT parameters. */
    public static final double BKT_WEAK_PROBABILITY = 0.50;
    public static final double BKT_LEARNED_PROBABILITY = 0.80;
    /** Evidence-run requirements used only for descriptive state labels. */
    public static final int BKT_RECOVERY_RUN = 2;
    public static final int BKT_SLIP_PRIOR_CORRECTS = 2;

    // ── BKT + observed-demand question selection ──────────────────────────
    /** Starting Bloom demand for a concept with no eligible response evidence. */
    public static final int ADAPTIVE_INITIAL_BLOOM_LEVEL = 2;
    /** Minimum binary responses and distinct items before observed demand can adjust the BKT baseline. */
    public static final int ADAPTIVE_MIN_DEMAND_OBSERVATIONS = 5;
    public static final int ADAPTIVE_MIN_DISTINCT_ITEMS = 3;
    /** Maximum posterior standard deviation accepted for a Bloom-level facility estimate. */
    public static final double ADAPTIVE_MAX_FACILITY_SD = 0.18;
    /** Operational challenge target; a design choice, not a universal educational or psychometric standard. */
    public static final double ADAPTIVE_TARGET_FACILITY = 0.70;
    /** Observed-demand evidence may adjust the BKT-derived Bloom baseline by at most this many levels. */
    public static final int ADAPTIVE_MAX_BLOOM_ADJUSTMENT = 1;
    /** Penalizes moving away from the BKT baseline when two levels have similarly suitable observed facility. */
    public static final double ADAPTIVE_BASELINE_DISTANCE_PENALTY = 0.10;

    /** Legacy prompt-example cutoff; not used by the BKT update. */
    public static final double CORRECT_CREDIT = 0.75;

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

    // ── Legacy tier calibration (removed in the separate difficulty-selection migration) ──
    public static final double[] EASY_RANGE = {0.80, 0.90};
    public static final double[] MEDIUM_RANGE = {0.60, 0.75};
    public static final double[] HARD_RANGE = {0.40, 0.60};
    public static final int CALIBRATION_MIN_RESPONSES = 30;

    /** Bump when a prompt changes meaning; stored with lessons/responses so results stay comparable. */
    public static final String PROMPT_VERSION = "2026-11-bkt-v1";
}
