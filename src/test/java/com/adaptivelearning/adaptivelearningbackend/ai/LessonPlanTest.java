package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.LessonVerifier.Unit;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LessonPlanTest {

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    private static ConceptState weak(String key, double pKnown) {
        ConceptState s = new ConceptState(key);
        s.state = State.WEAK; s.pKnown = pKnown; s.misses = 2; s.asked = 2;
        return s;
    }

    private static ConceptState of(String key, State state) {
        ConceptState s = new ConceptState(key);
        s.state = state;
        if (state == State.SLIP) { s.misses = 1; s.corrects = 3; s.asked = 4; s.pKnown = 0.6; }
        if (state == State.LEARNED) { s.pKnown = 0.9; s.asked = 4; s.corrects = 4; }
        return s;
    }

    private static List<ConceptState> allWeak(int n) {
        List<ConceptState> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(weak("c" + i, 0.10 + i * 0.01)); // c0 is the most urgent
        return out;
    }

    private static void markTaught(Map<String, Integer> taught, List<String> keys) {
        for (String k : keys) taught.merge(k, 1, Integer::sum);
    }

    @Test
    void weakConceptsFillThePartBeforeAnythingElse() {
        List<ConceptState> states = new ArrayList<>(List.of(
                of("slip", State.SLIP), of("new1", State.UNSEEN), weak("w1", 0.2), weak("w2", 0.1), of("new2", State.UNSEEN)));
        LessonPlanner.Plan p = LessonPlanner.next(states, Map.of(), "Easy", NOW);
        assertEquals(List.of("w2", "w1", "slip"), p.keys()); // most urgent weakness first, one slip fills the last seat
    }

    @Test
    void noWeaknessesTeachesNotYetAskedConceptsInHandoutOrder() {
        List<ConceptState> states = List.of(of("a", State.LEARNED), of("b", State.UNSEEN), of("c", State.UNSEEN),
                of("d", State.UNSEEN), of("e", State.UNSEEN));
        LessonPlanner.Plan p = LessonPlanner.next(states, Map.of(), "Easy", NOW);
        assertEquals(List.of("b", "c", "d"), p.keys());
    }

    @Test
    void learnedConceptsAreNotTaughtUnlessStale() {
        ConceptState stale = of("old", State.LEARNED);
        stale.stale = true;
        List<ConceptState> states = List.of(of("fine", State.LEARNED), stale);
        assertEquals(List.of("old"), LessonPlanner.next(states, Map.of(), "Easy", NOW).keys());
        assertEquals(List.of(), LessonPlanner.next(List.of(of("fine", State.LEARNED)), Map.of(), "Easy", NOW).keys());
    }

    @Test
    void weakOnEverythingStillCoversEverythingStepByStep() {
        List<ConceptState> states = allWeak(10);
        Map<String, Integer> taught = new HashMap<>();
        Set<String> covered = new LinkedHashSet<>();
        int parts = 0;
        while (covered.size() < 10 && parts < 20) {
            LessonPlanner.Plan p = LessonPlanner.next(states, taught, "Easy", NOW);
            assertTrue(p.keys().size() <= 3, "a part never exceeds the part size");
            covered.addAll(p.keys());
            markTaught(taught, p.keys());
            parts++;
        }
        assertEquals(4, parts); // ceil(10 / 3): every concept is reached, nothing is skipped
        assertEquals(10, covered.size());
    }

    @Test
    void aConceptTaughtOnceWaitsForTheRestOfTheRound() {
        List<ConceptState> states = allWeak(5);
        Map<String, Integer> taught = new HashMap<>(Map.of("c0", 1, "c1", 1, "c2", 1)); // first part already done
        LessonPlanner.Plan p = LessonPlanner.next(states, taught, "Easy", NOW);
        assertEquals(List.of("c3", "c4", "c0"), p.keys()); // untaught first, then round two begins with c0
    }

    @Test
    void progressCountsPartsOverTheWeakConcepts() {
        List<ConceptState> states = allWeak(8);
        Map<String, Integer> taught = new HashMap<>();
        LessonPlanner.Plan first = LessonPlanner.next(states, taught, "Easy", NOW);
        assertEquals(8, first.weakTotal());
        assertEquals(3, first.partsInRound());
        assertEquals(1, first.partNumber());
        markTaught(taught, first.keys());
        LessonPlanner.Plan second = LessonPlanner.next(states, taught, "Easy", NOW);
        assertEquals(2, second.partNumber());
        assertEquals(5, second.weakLeftInRound());
    }

    @Test
    void partSizeGrowsWithTier() {
        assertEquals(3, LessonPlanner.partSize("Easy"));
        assertEquals(4, LessonPlanner.partSize("Medium"));
        assertEquals(5, LessonPlanner.partSize("Hard"));
    }

    // ── Verifier ───────────────────────────────────────────────────────────

    private static final Map<String, String> PASSAGES = Map.of(
            "P1", "A primary key uniquely identifies each row in a table. A table can have only one primary key.",
            "P2", "A foreign key is a column that refers to the primary key of another table.");

    private static Unit unit(String source, String example, String refutation, String question, String answer) {
        return new Unit("primary-key", source, "A primary key is the column that identifies each row.", example,
                refutation, question, answer);
    }

    @Test
    void groundedUnitPasses() {
        Unit u = unit("P1", "A table can have only one primary key.", "", "How many primary keys can a table have?",
                "A table can have only one primary key.");
        assertTrue(LessonVerifier.check(u, PASSAGES, "Primary key", false).ok());
    }

    @Test
    void citingAPassageThatWasNotSentFails() {
        Unit u = unit("P9", "", "", "What is a primary key?", "It uniquely identifies each row in a table.");
        assertFalse(LessonVerifier.check(u, PASSAGES, "Primary key", false).ok());
    }

    @Test
    void conceptNameMustAppearInTheCitedPassage() {
        Unit u = unit("P2", "", "", "What is a foreign key?", "A column that refers to the primary key of another table.");
        assertFalse(LessonVerifier.check(u, PASSAGES, "Normalization", false).ok());
    }

    @Test
    void exampleFromOutsideThePassageFails() {
        Unit u = unit("P1", "Think of a passport number at an airport counter in Paris.", "", "What does a primary key do?",
                "It uniquely identifies each row in a table.");
        assertFalse(LessonVerifier.check(u, PASSAGES, "Primary key", false).ok());
    }

    @Test
    void answerGivenAwayInTheQuestionFails() {
        Unit u = unit("P1", "", "", "Is it true that a primary key uniquely identifies each row in a table?",
                "A primary key uniquely identifies each row in a table.");
        assertFalse(LessonVerifier.check(u, PASSAGES, "Primary key", false).ok());
    }

    @Test
    void refutationIsRemovedUnlessTheStudentShowedThatMistake() {
        Unit u = unit("P1", "", "Many learners think a table can have two primary keys. Actually only one is allowed.",
                "How many primary keys can a table have?", "A table can have only one primary key.");
        assertEquals("", LessonVerifier.check(u, PASSAGES, "Primary key", false).unit().refutation());
        assertTrue(!LessonVerifier.check(u, PASSAGES, "Primary key", true).unit().refutation().isEmpty());
    }

    @Test
    void passageIdsAreNormalized() {
        assertEquals("P3", LessonVerifier.normalizeId("[p3]"));
        assertEquals("P3", LessonVerifier.normalizeId("3"));
        assertEquals(null, LessonVerifier.normalizeId("page three"));
    }
}
