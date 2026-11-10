package com.adaptivelearning.adaptivelearningbackend.ai;

/**
 * Every tunable number of the AI layer lives here and nowhere else.
 *
 * <p> Code and comments refer to these constants by name instead of repeating the literal.
 */
public final class AiPolicy {
    private AiPolicy() {}

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

    /**
     * Logit difficulty of demand levels L1..L5 (Remember, Understand, Apply, Analyze, Evaluate). These are priors:
     * the log-based check in {@link TierCalibration} is how they should be re-tuned once real responses exist.
     */
    public static final double[] DEMAND_LOGIT = {-1.6, -0.8, 0.0, 0.8, 1.6};
    /** Names of demand levels L1..L5, in the same order as {@link #DEMAND_LOGIT} (Revised Bloom's Taxonomy). */
    public static final String[] DEMAND_NAMES = {"Remember", "Understand", "Apply", "Analyze", "Evaluate"};
    /** Legacy 3-tier questions (no stored demand level) are replayed at these logits (Easy sits between L1 and L2). */
    public static final double LEGACY_EASY_LOGIT = -1.2, LEGACY_MEDIUM_LOGIT = 0.0, LEGACY_HARD_LOGIT = 1.2;
    /**
     * Success rate the planner aims for per slot (guessing ignored). About 0.75 in Math Garden (Klinkenberg et al.,
     * 2011); Wilson et al. (2019) argue for about 0.85. Weak concepts are rebuilt a little easier, confirmation
     * questions a little harder, so they still discriminate.
     */
    public static final double TARGET_P_NEW = 0.75, TARGET_P_REMEDIATE = 0.80, TARGET_P_VERIFY = 0.70;
    /** Offsets (logit) cycled over the slots of a quiz so one quiz mixes neighbouring levels instead of one flat level. */
    public static final double DEMAND_SPREAD_LOGIT = 0.4;
    /**
     * Elo step size K = max(floor, start / (1 + decay * evidence)): large while a student is new, small once known.
     * Tuned by simulation (15-question quizzes, MCQ guess rate): smaller values left a student of true ability 1.0 on
     * Easy for eight quizzes; these track within about 0.3 logit by the fourth quiz with little wobble afterwards.
     */
    public static final double ABILITY_K_HANDOUT_START = 1.00, ABILITY_K_HANDOUT_FLOOR = 0.15, ABILITY_K_HANDOUT_DECAY = 0.10;
    public static final double ABILITY_K_CONCEPT_START = 0.50, ABILITY_K_CONCEPT_FLOOR = 0.12, ABILITY_K_CONCEPT_DECAY = 0.30;
    /**
     * Students see their handout ability as an ELO number: {@code ELO_BASE + theta * ELO_PER_LOGIT}. The scale is the
     * standard Elo one (a 400-point gap means 10:1 odds), so a new student sits at {@code ELO_BASE}. Display only; the
     * model itself works in logits.
     */
    public static final double ELO_BASE = 1500.0;
    public static final double ELO_PER_LOGIT = 400.0 / Math.log(10.0);
    /** Evidence ages with this half-life, so a long break makes the estimate move fast again (uncertainty grows). */
    public static final double ABILITY_EVIDENCE_HALF_LIFE_DAYS = 21.0;
    public static final double ABILITY_THETA_LIMIT = 4.0, ABILITY_OFFSET_LIMIT = 3.0;
    /** ConfigurationService key; "ability" (default) or "threshold" (the original score-threshold rule, for comparison). */
    public static final String ADAPTATION_MODEL_KEY = "ADAPTATION_MODEL";

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

    public static final double SHARE_REMEDIATE = 0.50;
    public static final double SHARE_NEW = 0.30;
    public static final double SHARE_VERIFY = 0.20;

    /** Handouts at or under this many words are sent whole; longer ones are sent as retrieved passages. */
    public static final int FULL_TEXT_WORD_LIMIT = 1800;
    public static final int PASSAGES_PER_CONCEPT = 2;
    public static final int PASSAGE_WORD_BUDGET = 2400;
    public static final int CHUNK_TARGET_WORDS = 160;

    public static final int SCHED_MAX_CONCURRENT = 10;
    public static final int SCHED_MIN_CONCURRENT = 2;
    /** Slots that only INTERACTIVE work may use, so a burst of quiz generation can never block lessons/grading. */
    public static final int SCHED_RESERVED_INTERACTIVE = 3;
    public static final int SCHED_PER_USER_MAX = 2;
    public static final long SCHED_MAX_WAIT_MS = 90_000L;
    public static final long SCHED_BULK_PROMOTE_MS = 20_000L;

    /** Placeholders — tune to the real plan. Amounts are USD per student per Philippine-time day. */
    public static final double BUDGET_SOFT_USD = 0.40;
    public static final double BUDGET_HARD_USD = 1.00;
    /** Output-token cap for question generation = base + perQuestion * slots (was a flat 12000). */
    public static final int GEN_TOKENS_BASE = 800;
    public static final int GEN_TOKENS_PER_QUESTION = 520;

    /** Expected facility (share answered correctly) per tier; the same numbers the prompts quote. */
    public static final double[] EASY_RANGE = {0.80, 0.90};
    public static final double[] MEDIUM_RANGE = {0.60, 0.75};
    public static final double[] HARD_RANGE = {0.40, 0.60};
    public static final int CALIBRATION_MIN_RESPONSES = 30;

    /** ConfigurationService key; "units" (default) or "classic" (the original single-summary lesson, for comparison). */
    public static final String LESSON_MODE_KEY = "LESSON_MODE";
    /** Concepts taught per lesson part, by tier. Starting values to tune, not evidence-based figures. */
    public static final int LESSON_PART_EASY = 3, LESSON_PART_MEDIUM = 4, LESSON_PART_HARD = 5;
    /** At most this many one-off slips / stale refreshers ride along in a part that still has room. */
    public static final int LESSON_MAX_SLIPS = 1, LESSON_MAX_REFRESHERS = 1;
    /** A unit's example must share at least this share of its content words with the cited passage. */
    public static final double LESSON_EXAMPLE_MIN_OVERLAP = 0.5;
    /** Characters of the cited passage shown to the student after they attempt a check question. */
    public static final int LESSON_SOURCE_SHOW_CHARS = 700;

    /** Bump when a prompt changes meaning; stored with lessons/responses so results stay comparable. */
    public static final String PROMPT_VERSION = "2026-11-v4";
}
