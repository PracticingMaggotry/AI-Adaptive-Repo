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
        // most urgent weakness first, then the one slip, then not-yet-asked concepts fill the remaining seats
        assertEquals(List.of("w2", "w1", "slip", "new1", "new2"), p.keys());
    }

    @Test
    void noWeaknessesTeachesNotYetAskedConceptsInHandoutOrder() {
        List<ConceptState> states = List.of(of("a", State.LEARNED), of("b", State.UNSEEN), of("c", State.UNSEEN),
                of("d", State.UNSEEN), of("e", State.UNSEEN), of("f", State.UNSEEN), of("g", State.UNSEEN));
        LessonPlanner.Plan p = LessonPlanner.next(states, Map.of(), "Easy", NOW);
        assertEquals(List.of("b", "c", "d", "e", "f"), p.keys()); // five concepts; "g" waits for the next part
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
            assertTrue(p.keys().size() <= 5, "a part never exceeds the part size");
            covered.addAll(p.keys());
            markTaught(taught, p.keys());
            parts++;
        }
        assertEquals(2, parts); // ceil(10 / 5): every concept is reached, nothing is skipped
        assertEquals(10, covered.size());
    }

    @Test
    void aConceptTaughtOnceWaitsForTheRestOfTheRound() {
        List<ConceptState> states = allWeak(8);
        Map<String, Integer> taught = new HashMap<>(Map.of("c0", 1, "c1", 1, "c2", 1, "c3", 1, "c4", 1)); // first part already done
        LessonPlanner.Plan p = LessonPlanner.next(states, taught, "Easy", NOW);
        assertEquals(List.of("c5", "c6", "c7", "c0", "c1"), p.keys()); // untaught first, then round two begins with c0
    }

    @Test
    void progressCountsPartsOverTheWeakConcepts() {
        List<ConceptState> states = allWeak(8);
        Map<String, Integer> taught = new HashMap<>();
        LessonPlanner.Plan first = LessonPlanner.next(states, taught, "Easy", NOW);
        assertEquals(8, first.weakTotal());
        assertEquals(2, first.partsInRound());
        assertEquals(1, first.partNumber());
        markTaught(taught, first.keys());
        LessonPlanner.Plan second = LessonPlanner.next(states, taught, "Easy", NOW);
        assertEquals(2, second.partNumber());
        assertEquals(3, second.weakLeftInRound());
    }

    @Test
    void everyTierTeachesAtLeastFiveConceptsPerPart() {
        assertEquals(5, LessonPlanner.partSize("Easy"));
        assertEquals(5, LessonPlanner.partSize("Medium"));
        assertEquals(5, LessonPlanner.partSize("Hard"));
        assertTrue(LessonPlanner.partSize(null) >= 5);
    }


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

    private static final Map<String, String> DIVISION = Map.of(
            "P1", "True division (/) always returns a float, even when both numbers divide evenly. 17 / 5 returns 3.4, not 3.");

    private static Unit divisionUnit(String question, String answer) {
        return new Unit("true-division", "P1", "True division always gives a float.",
                "17 / 5 returns 3.4, not 3.", "", question, answer);
    }

    @Test
    void questionThatReusesTheExamplesValuesFails() {
        Unit u = divisionUnit("What does 17 / 5 return in Python, and why is it a float?",
                "It returns 3.4, because true division always returns a float.");
        LessonVerifier.Result r = LessonVerifier.check(u, DIVISION, "True division", false);
        assertFalse(r.ok());
        assertEquals("check question repeats the example", r.problem());
    }

    @Test
    void questionWithNewValuesPasses() {
        Unit u = divisionUnit("What does 9 / 2 return, and why is it a float?",
                "It returns 4.5, because true division always returns a float.");
        assertTrue(LessonVerifier.check(u, DIVISION, "True division", false).ok());
    }

    @Test
    void conceptualQuestionWithoutNumbersPasses() {
        Unit u = divisionUnit("Why does true division always return a float?",
                "True division always returns a float, even when both numbers divide evenly.");
        assertTrue(LessonVerifier.check(u, DIVISION, "True division", false).ok());
    }

    @Test
    void questionWithoutNumbersThatCopiesTheExampleFails() {
        Map<String, String> p = Map.of("P1", "A table can have only one primary key. A primary key uniquely identifies each row.");
        Unit u = new Unit("pk", "P1", "A primary key identifies each row.", "A table can have only one primary key.", "",
                "A table can have only one primary key, true or false?", "Only one primary key is allowed per table.");
        assertFalse(LessonVerifier.check(u, p, "Primary key", false).ok());
    }

    @Test
    void numbersInsideWordsDoNotCountAsValues() {
        assertFalse(LessonVerifier.repeatsExample("Why does 3NF need 2NF first?", "1NF comes before 2NF.", "Normal form"));
        assertTrue(LessonVerifier.repeatsExample("What does range(1, 4) produce?",
                "range(1, 4) produces 1, 2, 3 and stops before 4.", "range(start, stop)"));
    }

    @Test
    void questionThatRepeatsTheExampleIsDroppedButTheContentIsKept() {
        Unit u = divisionUnit("What does 17 / 5 return in Python, and why is it a float?",
                "It returns 3.4, because true division always returns a float.");
        LessonVerifier.Result r = LessonVerifier.check(u, DIVISION, "True division", false);
        assertFalse(r.ok());
        assertEquals("check question repeats the example", r.problem());
        Unit kept = r.withoutQuestion();
        assertNotNull(kept);
        assertEquals(u.explain(), kept.explain());
        assertEquals(u.example(), kept.example());
        assertEquals("", kept.question());
        assertEquals("", kept.answer());
    }

    @Test
    void everyCheckQuestionProblemKeepsTheContent() {
        // The answer is handed over in the question.
        Unit giveaway = unit("P1", "", "", "Is it true that a primary key uniquely identifies each row in a table?",
                "A primary key uniquely identifies each row in a table.");
        // No check question at all.
        Unit noQuestion = unit("P1", "", "", "", "");
        // The model answer is not made of words from the passage.
        Unit offPassage = unit("P1", "", "", "Why do airports use passport numbers?",
                "Because customs officers scan every traveller at the airport counter.");
        for (Unit u : List.of(giveaway, noQuestion, offPassage)) {
            LessonVerifier.Result r = LessonVerifier.check(u, PASSAGES, "Primary key", false);
            assertFalse(r.ok(), r.problem());
            assertNotNull(r.withoutQuestion(), r.problem());
            assertEquals("A primary key is the column that identifies each row.", r.withoutQuestion().explain());
            assertEquals("", r.withoutQuestion().question());
        }
    }

    @Test
    void contentThatIsNotFromTheHandoutIsNeverKept() {
        Unit unsent = unit("P9", "", "", "What is a primary key?", "It uniquely identifies each row in a table.");
        Unit badExample = unit("P1", "Think of a passport number at an airport counter in Paris.", "",
                "What does a primary key do?", "It uniquely identifies each row in a table.");
        Unit wrongConcept = unit("P2", "", "", "What is a foreign key?", "A column that refers to the primary key of another table.");
        assertNull(LessonVerifier.check(unsent, PASSAGES, "Primary key", false).withoutQuestion());
        assertNull(LessonVerifier.check(badExample, PASSAGES, "Primary key", false).withoutQuestion());
        assertNull(LessonVerifier.check(wrongConcept, PASSAGES, "Normalization", false).withoutQuestion());
    }

    @Test
    void aPassingUnitHasNothingDropped() {
        Unit u = unit("P1", "A table can have only one primary key.", "", "How many primary keys can a table have?",
                "A table can have only one primary key.");
        LessonVerifier.Result r = LessonVerifier.check(u, PASSAGES, "Primary key", false);
        assertTrue(r.ok());
        assertNull(r.withoutQuestion());
    }

    @Test
    void keptContentStillLosesARefutationTheStudentNeverEarned() {
        Unit u = unit("P1", "", "Many learners think a table can have two primary keys. Actually only one is allowed.",
                "", "");
        assertEquals("", LessonVerifier.check(u, PASSAGES, "Primary key", false).withoutQuestion().refutation());
        assertFalse(LessonVerifier.check(u, PASSAGES, "Primary key", true).withoutQuestion().refutation().isEmpty());
    }

    @Test
    void passageIdsAreNormalized() {
        assertEquals("P3", LessonVerifier.normalizeId("[p3]"));
        assertEquals("P3", LessonVerifier.normalizeId("3"));
        assertEquals(null, LessonVerifier.normalizeId("page three"));
    }
}
