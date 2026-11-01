package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.Event;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MasteryModelTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    private static Event ev(String key, double credit, String tier, int secondsAgoDays, int timeSpent) {
        return new Event(key, credit, "MCQ", tier, timeSpent, NOW.minus(secondsAgoDays, ChronoUnit.DAYS));
    }

    private static ConceptState one(List<Event> events) {
        return MasteryModel.evaluate(List.of("c"), events, NOW).get("c");
    }

    @Test
    void conceptWithNoAnswersIsUnseen() {
        assertEquals(State.UNSEEN, one(List.of()).state);
    }

    @Test
    void singleWrongAfterSolidKnowledgeIsASlipNotAWeakness() {
        ConceptState s = one(List.of(
                ev("c", 1, "Medium", 10, 20), ev("c", 1, "Medium", 9, 20), ev("c", 1, "Medium", 8, 20),
                ev("c", 0, "Medium", 1, 25)));
        assertEquals(State.SLIP, s.state, "p=" + s.pKnown);
        assertEquals(1, s.misses);
    }

    @Test
    void repeatedWrongAnswersAreAWeakness() {
        ConceptState s = one(List.of(ev("c", 0, "Easy", 5, 20), ev("c", 0, "Easy", 3, 20), ev("c", 0, "Easy", 1, 20)));
        assertEquals(State.WEAK, s.state);
        assertEquals(3, s.misses);
        assertTrue(s.pKnown < AiPolicy.WEAK_P);
    }

    @Test
    void wrongThenCorrectTwiceInARowIsNowLearnedAndMarkedRecovered() {
        ConceptState s = one(List.of(ev("c", 0, "Easy", 9, 20), ev("c", 1, "Easy", 5, 20), ev("c", 1, "Easy", 2, 20)));
        assertEquals(State.LEARNED, s.state, "p=" + s.pKnown);
        assertTrue(s.recovered);
        assertEquals(1, s.missesBeforeRecovery);
    }

    @Test
    void oneCorrectAnswerAfterAMissIsNotYetProofOfLearning() {
        ConceptState s = one(List.of(ev("c", 0, "Easy", 9, 20), ev("c", 1, "Easy", 2, 20)));
        assertNotEquals(State.LEARNED, s.state);
    }

    @Test
    void aMistakeStaysDocumentedHoweverOldItIs() {
        // 9 later correct answers would have pushed it out of an "8 attempts" window; the model still sees the miss.
        List<Event> list = new ArrayList<>();
        list.add(ev("c", 0, "Easy", 200, 20));
        for (int i = 0; i < 9; i++) list.add(ev("c", 1, "Easy", 12 - i, 20));
        ConceptState s = one(list);
        assertEquals(1, s.misses);
        assertTrue(s.recovered);
    }

    @Test
    void aFixedMistakeStaysLearnedAsTimePassesButBecomesDueForARefresher() {
        // miss 40 days ago, correct 30 and 12 days ago: fixed long before "now", then not revisited.
        ConceptState s = one(List.of(ev("c", 0, "Easy", 40, 20), ev("c", 1, "Easy", 30, 20), ev("c", 1, "Medium", 12, 20)));
        assertEquals(State.LEARNED, s.state, "p=" + s.pKnown);
        assertTrue(s.recovered);
        assertTrue(s.stale, "should be flagged for a spaced check");
        assertEquals(1, s.missesBeforeRecovery);
    }

    @Test
    void learnedKnowledgeDecaysSoAnOldConceptBecomesDueForReview() {
        ConceptState fresh = one(List.of(ev("c", 1, "Easy", 2, 20), ev("c", 1, "Easy", 1, 20), ev("c", 1, "Easy", 0, 20)));
        ConceptState old = one(List.of(ev("c", 1, "Easy", 92, 20), ev("c", 1, "Easy", 91, 20), ev("c", 1, "Easy", 90, 20)));
        assertEquals(State.LEARNED, fresh.state);
        assertNotEquals(State.LEARNED, old.state);
        assertTrue(old.stale);
        assertTrue(old.pKnown < fresh.pKnown);
        assertEquals(0, old.misses, "decay must never invent a mistake");
    }

    @Test
    void aWrongAnswerOnAHardItemCostsLessThanOnAnEasyItem() {
        List<Event> base = List.of(ev("c", 1, "Medium", 4, 20), ev("c", 1, "Medium", 3, 20));
        List<Event> easyMiss = new ArrayList<>(base); easyMiss.add(ev("c", 0, "Easy", 1, 20));
        List<Event> hardMiss = new ArrayList<>(base); hardMiss.add(ev("c", 0, "Hard", 1, 20));
        assertTrue(one(hardMiss).pKnown > one(easyMiss).pKnown);
    }

    @Test
    void aVeryFastWrongAnswerIsTreatedAsMoreLikelyCareless() {
        List<Event> base = List.of(ev("c", 1, "Medium", 4, 20), ev("c", 1, "Medium", 3, 20));
        List<Event> slow = new ArrayList<>(base); slow.add(ev("c", 0, "Medium", 1, 30));
        List<Event> fast = new ArrayList<>(base); fast.add(ev("c", 0, "Medium", 1, 2));
        assertTrue(one(fast).pKnown > one(slow).pKnown);
    }

    @Test
    void resultDoesNotDependOnTheOrderEventsAreSupplied() {
        List<Event> a = List.of(ev("c", 0, "Easy", 9, 20), ev("c", 1, "Easy", 5, 20), ev("c", 1, "Easy", 2, 20));
        List<Event> b = new ArrayList<>(a);
        Collections.reverse(b);
        assertEquals(one(a).pKnown, one(b).pKnown, 1e-12);
        assertEquals(one(a).state, one(b).state);
    }

    @Test
    void partialCreditBreaksARecoveryRunWithoutCountingAsAMiss() {
        ConceptState s = one(List.of(ev("c", 0, "Easy", 9, 20), ev("c", 1, "Easy", 5, 20), ev("c", 0.6, "Easy", 2, 20)));
        assertEquals(1, s.misses);
        assertEquals(0, s.correctRun);
        assertNotEquals(State.LEARNED, s.state);
    }

    @Test
    void eventsForUnknownConceptsAreIgnored() {
        Map<String, ConceptState> m = MasteryModel.evaluate(List.of("c"),
                List.of(new Event("other", 0, "MCQ", "Easy", 5, NOW)), NOW);
        assertEquals(State.UNSEEN, m.get("c").state);
        assertEquals(1, m.size());
    }
}
