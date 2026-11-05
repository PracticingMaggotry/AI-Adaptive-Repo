package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.Event;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MasteryModelTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private static Event ev(String key, double credit, String type, int sequence) {
        return new Event(key, credit, type, T0.plusSeconds(sequence));
    }

    private static ConceptState one(List<Event> events) {
        return MasteryModel.evaluate(List.of("c"), events, T0.plusSeconds(10_000)).get("c");
    }

    @Test
    void unseenConceptRetainsConfiguredInitialKnowledgeProbability() {
        ConceptState state = one(List.of());
        assertEquals(State.UNSEEN, state.state);
        assertEquals(AiPolicy.BKT_P_INIT, state.pKnown, 1e-12);
        assertEquals(0, state.asked);
    }

    @Test
    void oneCorrectMcqUsesTheStandardBayesianPosteriorThenLearningTransition() {
        // P(L0)=.30, P(S)=.10, P(G)=.25, P(T)=.15.
        // P(L|correct)= (.30*.90)/((.30*.90)+(.70*.25)) = .606741573...
        // P(L next)= P(L|correct) + (1-P(L|correct))*.15 = .665730337...
        ConceptState state = one(List.of(ev("c", 1.0, "MCQ", 0)));
        assertEquals(0.6657303370786517, state.pKnown, 1e-12);
        assertEquals(1, state.asked);
        assertEquals(1, state.corrects);
        assertEquals(0, state.misses);
    }

    @Test
    void oneIncorrectMcqUsesTheStandardBayesianPosteriorThenLearningTransition() {
        // P(L|wrong)= (.30*.10)/((.30*.10)+(.70*.75)) = .054054054...
        // P(L next)= P(L|wrong) + (1-P(L|wrong))*.15 = .195945946...
        ConceptState state = one(List.of(ev("c", 0.0, "MCQ", 0)));
        assertEquals(0.19594594594594594, state.pKnown, 1e-12);
        assertEquals(State.WEAK, state.state);
        assertEquals(1, state.misses);
    }

    @Test
    void repeatedCorrectResponsesIncreaseKnowledgeProbability() {
        ConceptState oneCorrect = one(List.of(ev("c", 1.0, "MCQ", 0)));
        ConceptState threeCorrect = one(List.of(
                ev("c", 1.0, "MCQ", 0), ev("c", 1.0, "MCQ", 1), ev("c", 1.0, "MCQ", 2)));
        assertTrue(threeCorrect.pKnown > oneCorrect.pKnown);
        assertTrue(threeCorrect.pKnown > AiPolicy.BKT_LEARNED_PROBABILITY);
        assertEquals(State.LEARNED, threeCorrect.state);
    }

    @Test
    void repeatedIncorrectResponsesLowerKnowledgeProbability() {
        ConceptState oneWrong = one(List.of(ev("c", 0.0, "MCQ", 0)));
        ConceptState threeWrong = one(List.of(
                ev("c", 0.0, "MCQ", 0), ev("c", 0.0, "MCQ", 1), ev("c", 0.0, "MCQ", 2)));
        assertTrue(threeWrong.pKnown < oneWrong.pKnown);
        assertTrue(threeWrong.pKnown < AiPolicy.BKT_WEAK_PROBABILITY);
        assertEquals(State.WEAK, threeWrong.state);
    }

    @Test
    void questionFormatGuessProbabilityChangesEvidenceStrength() {
        ConceptState mcq = one(List.of(ev("c", 1.0, "MCQ", 0)));
        ConceptState trueFalse = one(List.of(ev("c", 1.0, "TRUEFALSE", 0)));
        assertTrue(mcq.pKnown > trueFalse.pKnown,
                "a correct answer to a 50%-guess format should provide less evidence than a 25%-guess MCQ");
    }

    @Test
    void partialCreditAndEssayResponsesAreNotCoercedIntoBinaryBktEvidence() {
        ConceptState baseline = one(List.of());
        ConceptState ignored = one(List.of(
                ev("c", 0.5, "MATCHING", 0),
                ev("c", 1.0, "ESSAY", 1),
                ev("c", 0.2, "FILLBLANK", 2)));
        assertEquals(baseline.pKnown, ignored.pKnown, 1e-12);
        assertEquals(0, ignored.asked);
        assertEquals(State.UNSEEN, ignored.state);
    }

    @Test
    void responseHistoryIsReplayedChronologicallyRegardlessOfInputOrder() {
        List<Event> chronological = List.of(
                ev("c", 0.0, "MCQ", 0), ev("c", 1.0, "MCQ", 1), ev("c", 1.0, "MCQ", 2));
        List<Event> reversed = new ArrayList<>(chronological);
        Collections.reverse(reversed);
        ConceptState a = one(chronological);
        ConceptState b = one(reversed);
        assertEquals(a.pKnown, b.pKnown, 1e-12);
        assertEquals(a.state, b.state);
        assertEquals(a.correctRun, b.correctRun);
    }

    @Test
    void tierAndResponseTimeDoNotAlterStandardBktLikelihoods() {
        List<Event> baseline = List.of(
                new Event("c", 1.0, "MCQ", T0), new Event("c", 0.0, "MCQ", T0.plusSeconds(1)));
        List<Event> legacyMetadata = List.of(
                new Event("c", 1.0, "MCQ", "Hard", 1, T0),
                new Event("c", 0.0, "MCQ", "Easy", 500, T0.plusSeconds(1)));
        assertEquals(one(baseline).pKnown, one(legacyMetadata).pKnown, 1e-12);
    }

    @Test
    void oneMissAfterRepeatedSuccessCanBeClassifiedAsAnIsolatedSlip() {
        ConceptState state = one(List.of(
                ev("c", 1.0, "MCQ", 0), ev("c", 1.0, "MCQ", 1), ev("c", 1.0, "MCQ", 2),
                ev("c", 0.0, "MCQ", 3)));
        assertEquals(State.SLIP, state.state);
        assertEquals(1, state.misses);
        assertTrue(state.pKnown > AiPolicy.BKT_WEAK_PROBABILITY);
    }

    @Test
    void twoCorrectResponsesAfterAMissCanResolveAWeaknessWhenPosteriorSupportsIt() {
        ConceptState state = one(List.of(
                ev("c", 0.0, "MCQ", 0), ev("c", 1.0, "MCQ", 1), ev("c", 1.0, "MCQ", 2)));
        assertEquals(State.LEARNED, state.state);
        assertTrue(state.recovered);
        assertEquals(1, state.missesBeforeRecovery);
    }

    @Test
    void oneCorrectResponseAfterAMissIsNotAutomaticallyConsideredRecovered() {
        ConceptState state = one(List.of(ev("c", 0.0, "MCQ", 0), ev("c", 1.0, "MCQ", 1)));
        assertNotEquals(State.LEARNED, state.state);
        assertFalse(state.recovered);
    }

    @Test
    void eachConceptIsUpdatedIndependentlyAndUnknownConceptEventsAreIgnored() {
        Map<String, ConceptState> states = MasteryModel.evaluate(List.of("c", "other"), List.of(
                ev("c", 0.0, "MCQ", 0), ev("not-in-catalog", 0.0, "MCQ", 1)), T0);
        assertEquals(2, states.size());
        assertEquals(State.WEAK, states.get("c").state);
        assertEquals(State.UNSEEN, states.get("other").state);
    }

    @Test
    void posteriorProbabilityAlwaysRemainsBetweenZeroAndOne() {
        List<Event> alternating = new ArrayList<>();
        for (int i = 0; i < 100; i++) alternating.add(ev("c", i % 2 == 0 ? 0.0 : 1.0, "MCQ", i));
        double pKnown = one(alternating).pKnown;
        assertTrue(pKnown >= 0.0 && pKnown <= 1.0);
    }
}
