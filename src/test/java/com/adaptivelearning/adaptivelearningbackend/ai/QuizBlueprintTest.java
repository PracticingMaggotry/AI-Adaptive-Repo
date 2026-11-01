package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.Event;
import com.adaptivelearning.adaptivelearningbackend.ai.QuizBlueprint.Mode;
import com.adaptivelearning.adaptivelearningbackend.ai.QuizBlueprint.Purpose;
import com.adaptivelearning.adaptivelearningbackend.ai.QuizBlueprint.Slot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class QuizBlueprintTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    private static List<String> keys(int n) {
        List<String> k = new ArrayList<>();
        for (int i = 1; i <= n; i++) k.add("c" + i);
        return k;
    }

    private static Event ev(String key, double credit, int daysAgo) {
        return new Event(key, credit, "MCQ", "Easy", 20, NOW.minus(daysAgo, ChronoUnit.DAYS));
    }

    private static List<ConceptState> states(int n, List<Event> events) {
        return new ArrayList<>(MasteryModel.evaluate(keys(n), events, NOW).values());
    }

    private static Map<String, Long> countByConcept(List<Slot> slots) {
        return slots.stream().collect(Collectors.groupingBy(Slot::conceptKey, Collectors.counting()));
    }

    @Test
    void initialQuizCoversTheWholeHandoutNotJustTheStart() {
        List<Slot> slots = QuizBlueprint.build(Mode.INITIAL, states(30, List.of()), 15, 3, NOW);
        assertEquals(15, slots.size());
        Set<String> used = slots.stream().map(Slot::conceptKey).collect(Collectors.toSet());
        assertEquals(15, used.size(), "each slot should test a different concept when concepts outnumber slots");
        assertTrue(used.contains("c1"));
        assertTrue(used.stream().anyMatch(k -> Integer.parseInt(k.substring(1)) > 25), "late concepts must be covered");
        assertTrue(slots.stream().allMatch(s -> s.purpose() == Purpose.INITIAL_COVERAGE));
    }

    @Test
    void initialQuizWithFewConceptsReusesThemUpToTheCapThenRelaxes() {
        List<Slot> slots = QuizBlueprint.build(Mode.INITIAL, states(4, List.of()), 15, 3, NOW);
        assertEquals(12, slots.size(), "4 concepts x cap 3");
    }

    @Test
    void adaptedQuizMixesRemediationNewCoverageAndVerification() {
        List<Event> events = new ArrayList<>();
        // c1: weak (3 misses)   c2: learned (3 corrects)   c3: slip (3 corrects then 1 miss)   c4..c10: unseen
        for (int d = 5; d >= 3; d--) events.add(ev("c1", 0, d));
        for (int d = 9; d >= 7; d--) events.add(ev("c2", 1, d));
        for (int d = 9; d >= 7; d--) events.add(ev("c3", 1, d));
        events.add(ev("c3", 0, 1));
        List<ConceptState> st = states(10, events);
        assertEquals(MasteryModel.State.WEAK, st.get(0).state);
        assertEquals(MasteryModel.State.SLIP, st.get(2).state);

        List<Slot> slots = QuizBlueprint.build(Mode.ADAPTED, st, 20, 3, NOW);
        assertEquals(20, slots.size());
        Map<Purpose, Long> byPurpose = slots.stream().collect(Collectors.groupingBy(Slot::purpose, Collectors.counting()));
        assertTrue(byPurpose.getOrDefault(Purpose.REMEDIATE, 0L) >= 1);
        assertTrue(byPurpose.getOrDefault(Purpose.COVER_NEW, 0L) >= 1);
        assertTrue(byPurpose.getOrDefault(Purpose.VERIFY, 0L) >= 1);
        // Remediation only ever targets the weak concept; the slip and the learned concept are verified, never drilled.
        for (Slot s : slots) if (s.purpose() == Purpose.REMEDIATE) assertEquals("c1", s.conceptKey());
        assertTrue(countByConcept(slots).get("c3") <= 3);
    }

    @Test
    void adaptedQuizNeverGivesOneConceptMoreThanTheCapWhenThereIsRoomElsewhere() {
        List<Event> events = new ArrayList<>();
        for (int d = 5; d >= 3; d--) events.add(ev("c1", 0, d));
        List<Slot> slots = QuizBlueprint.build(Mode.ADAPTED, states(12, events), 15, 3, NOW);
        assertEquals(15, slots.size());
        countByConcept(slots).values().forEach(c -> assertTrue(c <= 3));
    }

    @Test
    void recoveredConceptIsNotRemediatedAgain() {
        List<Event> events = List.of(ev("c1", 0, 9), ev("c1", 1, 5), ev("c1", 1, 2));
        List<Slot> slots = QuizBlueprint.build(Mode.ADAPTED, states(6, events), 15, 3, NOW);
        assertTrue(slots.stream().noneMatch(s -> s.purpose() == Purpose.REMEDIATE),
                "nothing is weak, so nothing may be remediated");
    }

    @Test
    void staleRecoveryIsVerifiedNotRemediated() {
        List<Event> events = List.of(ev("c1", 0, 40), ev("c1", 1, 30), ev("c1", 1, 12));
        List<Slot> slots = QuizBlueprint.build(Mode.ADAPTED, states(6, events), 15, 3, NOW);
        assertTrue(slots.stream().noneMatch(s -> s.purpose() == Purpose.REMEDIATE));
        assertTrue(slots.stream().anyMatch(s -> s.conceptKey().equals("c1") && s.purpose() == Purpose.VERIFY));
    }

    @Test
    void targetedQuizConcentratesOnTheDocumentedWeakness() {
        List<Event> events = new ArrayList<>();
        for (int d = 6; d >= 4; d--) events.add(ev("c2", 0, d));
        List<Slot> slots = QuizBlueprint.build(Mode.TARGETED, states(10, events), 8, 3, NOW);
        assertEquals(8, slots.size());
        assertTrue(slots.stream().allMatch(s -> s.conceptKey().equals("c2") && s.purpose() == Purpose.REMEDIATE),
                "one narrow weakness produces a narrow quiz");
    }

    @Test
    void targetedQuizWithTwoWeaknessesWeightsTheMoreUrgentOne() {
        List<Event> events = new ArrayList<>();
        for (int d = 8; d >= 5; d--) events.add(ev("c1", 0, d));  // 4 misses
        for (int d = 8; d >= 7; d--) events.add(ev("c2", 0, d));  // 2 misses
        List<Slot> slots = QuizBlueprint.build(Mode.TARGETED, states(6, events), 9, 3, NOW);
        Map<String, Long> by = countByConcept(slots);
        assertEquals(9, slots.size());
        assertTrue(by.get("c1") > by.get("c2"));
        assertTrue(by.get("c2") >= 1);
    }

    @Test
    void targetedQuizWithNoMistakesFallsBackToDiagnosticQuestions() {
        List<Slot> slots = QuizBlueprint.build(Mode.TARGETED, states(6, List.of()), 5, 3, NOW);
        assertEquals(5, slots.size());
        assertTrue(slots.stream().allMatch(s -> s.purpose() == Purpose.DIAGNOSTIC));
    }

    @Test
    void sharesAlwaysSumToTheRequestedCount() {
        for (int n = 1; n < 40; n++) {
            int[] s = QuizBlueprint.shares(n, new double[]{0.5, 0.3, 0.2});
            assertEquals(n, s[0] + s[1] + s[2]);
        }
    }

    @Test
    void slotsAreNumberedFromOne() {
        List<Slot> slots = QuizBlueprint.build(Mode.INITIAL, states(5, List.of()), 5, 3, NOW);
        for (int i = 0; i < slots.size(); i++) assertEquals(i + 1, slots.get(i).index());
    }

    @Test
    void emptyInputsGiveNoSlots() {
        assertTrue(QuizBlueprint.build(Mode.ADAPTED, List.of(), 10, 3, NOW).isEmpty());
        assertTrue(QuizBlueprint.build(Mode.ADAPTED, states(3, List.of()), 0, 3, NOW).isEmpty());
    }
}
