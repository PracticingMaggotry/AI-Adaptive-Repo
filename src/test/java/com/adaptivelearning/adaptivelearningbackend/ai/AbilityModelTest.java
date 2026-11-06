package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.AbilityModel.Event;
import com.adaptivelearning.adaptivelearningbackend.ai.AbilityModel.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AbilityModelTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    private static Event ev(String key, double credit, double difficulty, boolean targeted, int minutesAgo) {
        return new Event(key, credit, "MCQ", difficulty, targeted, NOW.minus(minutesAgo, ChronoUnit.MINUTES));
    }

    private static Event ev(String key, double credit, int daysAgo) {
        return new Event(key, credit, "MCQ", 0.0, false, NOW.minus(daysAgo, ChronoUnit.DAYS));
    }

    @Test
    void newStudentStartsAtZeroAndGetsEasyLevelsForNewMaterial() {
        Result r = AbilityModel.evaluate(List.of(), NOW);
        assertEquals(0.0, r.theta, 1e-9);
        assertEquals("Easy", AbilityModel.tierFor(r.theta));
        int level = AbilityModel.planLevel(r, "c1", "COVER_NEW", 2);
        assertTrue(level <= 2, "level=" + level);
    }

    @Test
    void successOnItemsAboveCurrentAbilityRaisesAbilityAndFailureLowersIt() {
        List<Event> wins = new ArrayList<>(), losses = new ArrayList<>();
        for (int i = 10; i >= 1; i--) {
            wins.add(ev("c", 1, 0.0, false, i));
            losses.add(ev("c", 0, 0.0, false, i));
        }
        assertTrue(AbilityModel.evaluate(wins, NOW).theta > 0.5);
        assertTrue(AbilityModel.evaluate(losses, NOW).theta < -0.5);
    }

    @Test
    void aStrongStudentMovesUpTiersAndKeepsGettingHarderItems() {
        List<Event> events = new ArrayList<>();
        int minute = 600;
        for (int i = 0; i < 40; i++) events.add(ev("c" + (i % 5), 1, AiPolicy.DEMAND_LOGIT[2], false, minute--));
        Result r = AbilityModel.evaluate(events, NOW);
        assertEquals("Hard", AbilityModel.tierFor(r.theta), "theta=" + r.theta);
        assertTrue(AbilityModel.planLevel(r, "c0", "COVER_NEW", 2) >= 4);
    }

    @Test
    void aWeakConceptIsPlannedEasierThanTheRestOfAStrongHandout() {
        List<Event> events = new ArrayList<>();
        int minute = 1000;
        for (int i = 0; i < 30; i++) events.add(ev("strong", 1, 0.8, false, minute--));
        for (int i = 0; i < 6; i++) events.add(ev("weak", 0, 0.0, false, minute--));
        Result r = AbilityModel.evaluate(events, NOW);
        assertTrue(r.thetaFor("weak") < r.thetaFor("strong") - 0.5,
                "weak=" + r.thetaFor("weak") + " strong=" + r.thetaFor("strong"));
        assertTrue(AbilityModel.planLevel(r, "weak", "REMEDIATE", 2) < AbilityModel.planLevel(r, "strong", "VERIFY", 2));
    }

    @Test
    void targetedQuizAnswersChangeTheConceptButNotTheOverallTier() {
        List<Event> events = new ArrayList<>();
        for (int i = 10; i >= 1; i--) events.add(ev("c1", 0, 0.0, true, i));
        Result r = AbilityModel.evaluate(events, NOW);
        assertEquals(0.0, r.theta, 1e-9, "targeted answers must not move handout ability");
        assertTrue(r.concepts.get("c1").offset < -0.5);
    }

    @Test
    void evidenceAgesSoARetuningStudentIsTrackedFastAgain() {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) events.add(new Event("c", 1, "MCQ", 0.0, false, NOW.minus(42, ChronoUnit.DAYS).plusSeconds(i)));
        Result fresh = AbilityModel.evaluate(events, NOW.minus(42, ChronoUnit.DAYS).plusSeconds(20));
        Result later = AbilityModel.evaluate(events, NOW);
        assertEquals(10.0, fresh.handoutEvidence, 0.01);
        assertTrue(later.handoutEvidence < 3.0, "after two half-lives about a quarter remains: " + later.handoutEvidence);
        assertEquals(fresh.theta, later.theta, 1e-9, "ageing changes the step size, not the estimate");
    }

    @Test
    void guessingIsDiscounted() {
        // A correct true/false (50% guess) moves ability less than a correct matching question (10% guess).
        Result tf = AbilityModel.evaluate(List.of(new Event("c", 1, "TRUEFALSE", 0.0, false, NOW.minusSeconds(5))), NOW);
        Result match = AbilityModel.evaluate(List.of(new Event("c", 1, "MATCHING", 0.0, false, NOW.minusSeconds(5))), NOW);
        assertTrue(tf.theta < match.theta);
    }

    @Test
    void creditIsClampedAndUnknownEventsAreIgnored() {
        List<Event> events = new ArrayList<>();
        events.add(new Event("c", 7.0, "MCQ", 0.0, false, NOW.minusSeconds(10)));
        events.add(null);
        events.add(new Event("c", 1, "MCQ", 0.0, false, null));
        Result r = AbilityModel.evaluate(events, NOW);
        assertEquals(1, r.answers);
        assertTrue(r.theta > 0 && r.theta <= AiPolicy.ABILITY_THETA_LIMIT);
    }

    @Test
    void legacyTiersAndLevelsMapToLogitDifficulties() {
        assertEquals(AiPolicy.DEMAND_LOGIT[3], AbilityModel.difficultyOf(4, "Easy"), 1e-9, "stored level wins over tier");
        assertEquals(AiPolicy.LEGACY_EASY_LOGIT, AbilityModel.difficultyOf(null, "Easy"), 1e-9);
        assertEquals(AiPolicy.LEGACY_HARD_LOGIT, AbilityModel.difficultyOf(null, "hard"), 1e-9);
        assertEquals(AiPolicy.LEGACY_MEDIUM_LOGIT, AbilityModel.difficultyOf(null, "Targeted"), 1e-9);
        assertEquals(AiPolicy.LEGACY_MEDIUM_LOGIT, AbilityModel.difficultyOf(9, null), 1e-9);
    }

    @Test
    void levelsAndLabelsLineUp() {
        assertEquals(1, AbilityModel.levelFor(-5));
        assertEquals(5, AbilityModel.levelFor(5));
        assertEquals(3, AbilityModel.levelFor(0.1));
        assertEquals("Easy", AbilityModel.tierLabel(2));
        assertEquals("Medium", AbilityModel.tierLabel(3));
        assertEquals("Hard", AbilityModel.tierLabel(4));
    }

    @Test
    void targetDifficultyGivesTheRequestedSuccessRate() {
        for (double p : new double[]{0.6, 0.75, 0.85}) {
            double b = AbilityModel.targetDifficulty(1.3, p);
            assertEquals(p, AbilityModel.expected(1.3, b, 0.0), 1e-9);
        }
    }

    @Test
    void oneQuizMixesNeighbouringLevelsRatherThanOneFlatLevel() {
        Result r = AbilityModel.evaluate(List.of(), NOW);
        Set<Integer> levels = new HashSet<>();
        for (int slot = 1; slot <= 6; slot++) levels.add(AbilityModel.planLevel(r, "c", "COVER_NEW", slot));
        assertTrue(levels.size() >= 2, "levels=" + levels);
    }
}
