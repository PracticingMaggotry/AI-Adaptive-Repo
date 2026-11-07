package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.ConceptInsights.Item;
import com.adaptivelearning.adaptivelearningbackend.ai.ConceptInsights.Status;
import com.adaptivelearning.adaptivelearningbackend.ai.ConceptInsights.View;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt.ConceptInfo;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt.Example;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConceptInsightsTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

    private static Event ev(String key, double credit, int daysAgo) {
        return new Event(key, credit, "MCQ", "Medium", 20, NOW.minus(daysAgo, ChronoUnit.DAYS));
    }

    /** A handout with one concept per scenario, in this order. */
    private static final List<ConceptInfo> CONCEPTS = List.of(
            new ConceptInfo("unseen", "Primary key", "A column that identifies each row."),
            new ConceptInfo("weak", "Foreign key", "A column that points at another table's key."),
            new ConceptInfo("slip", "NULL", "A missing value."),
            new ConceptInfo("fixed", "Inner join", "Rows that match in both tables."),
            new ConceptInfo("weak2", "Left join", "All rows of the left table."),
            new ConceptInfo("weak3", "Right join", "All rows of the right table."),
            new ConceptInfo("weak4", "Full join", "All rows of both tables."),
            new ConceptInfo("old", "UPDATE", "Changes existing rows."));

    private static List<Event> events() {
        List<Event> e = new ArrayList<>();
        // weak: missed three times, never right
        e.add(ev("weak", 0, 5)); e.add(ev("weak", 0, 3)); e.add(ev("weak", 0, 1));
        // weak2 / weak3 / weak4: one miss each fewer, so priorities differ
        e.add(ev("weak2", 0, 4)); e.add(ev("weak2", 0, 2));
        e.add(ev("weak3", 0, 1)); e.add(ev("weak3", 0, 1));
        e.add(ev("weak4", 0, 6)); e.add(ev("weak4", 0, 5)); e.add(ev("weak4", 0, 4)); e.add(ev("weak4", 0, 2));
        // slip: three right, then one wrong
        e.add(ev("slip", 1, 10)); e.add(ev("slip", 1, 9)); e.add(ev("slip", 1, 8)); e.add(ev("slip", 0, 1));
        // fixed: a miss, then two right in a row
        e.add(ev("fixed", 0, 9)); e.add(ev("fixed", 1, 5)); e.add(ev("fixed", 1, 2));
        // old: learned three months ago, never revisited
        e.add(ev("old", 1, 92)); e.add(ev("old", 1, 91)); e.add(ev("old", 1, 90));
        return e;
    }

    private static Map<String, List<Example>> examples() {
        Map<String, List<Example>> m = new HashMap<>();
        m.put("weak", List.of(new Example("Which key links two tables?", "Primary key", "Foreign key", "", "", "", NOW)));
        m.put("slip", List.of(new Example("What does NULL mean?", "", "A missing value", "", "", "", NOW)));
        m.put("fixed", List.of(new Example("Which join keeps only matches?", "Left join", "Inner join", "", "", "", NOW)));
        return m;
    }

    private static View view() {
        List<String> keys = CONCEPTS.stream().map(ConceptInfo::key).toList();
        Map<String, ConceptState> states = MasteryModel.evaluate(keys, events(), NOW);
        return ConceptInsights.build(CONCEPTS, states, examples(), NOW);
    }

    private static Item find(View v, String key) {
        return v.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void everyInternalStateMapsToExactlyOneOfFourStatuses() {
        View v = view();
        assertEquals(Status.NOT_TRIED, find(v, "unseen").status());
        assertEquals(Status.NEEDS_PRACTICE, find(v, "weak").status());
        assertEquals(Status.ALMOST_THERE, find(v, "slip").status());
        assertEquals(Status.GOT_IT, find(v, "fixed").status());
    }

    @Test
    void countsAddUpToTheNumberOfConcepts() {
        ConceptInsights.Counts c = view().counts();
        assertEquals(CONCEPTS.size(), c.total());
        assertEquals(c.total(), c.needsPractice() + c.almostThere() + c.gotIt() + c.notTried());
        assertEquals(CONCEPTS.size(), view().items().size());
    }

    @Test
    void aFixedMistakeIsGoodNewsNotAWeakness() {
        Item fixed = find(view(), "fixed");
        assertEquals(Status.GOT_IT, fixed.status());
        assertTrue(fixed.fixed());
        assertTrue(fixed.evidence().startsWith("Fixed"), fixed.evidence());
        assertNull(fixed.lastMistake(), "a fixed concept must not resurface its old mistake");
    }

    @Test
    void aOneOffSlipIsAlmostThereAndKeepsItsMistakeForTheDetailRow() {
        Item slip = find(view(), "slip");
        assertEquals(Status.ALMOST_THERE, slip.status());
        assertNotNull(slip.lastMistake());
        assertEquals("(no answer)", slip.lastMistake().yourAnswer());
        assertTrue(slip.evidence().contains("one miss"), slip.evidence());
    }

    @Test
    void focusIsCappedAndOrderedByUrgency() {
        View v = view();
        assertEquals(ConceptInsights.FOCUS_MAX, v.focus().size());
        assertTrue(v.focus().stream().allMatch(i -> i.status() == Status.NEEDS_PRACTICE));
        for (int i = 1; i < v.focus().size(); i++) {
            assertTrue(v.focus().get(i - 1).priority() >= v.focus().get(i).priority());
        }
        assertEquals(4, v.counts().needsPractice(), "all four open weaknesses are counted even though only 3 are focus");
    }

    @Test
    void itemsAreGroupedNeedsPracticeFirstNotTriedLast() {
        List<Item> items = view().items();
        for (int i = 1; i < items.size(); i++) {
            assertTrue(items.get(i - 1).status().ordinal() <= items.get(i).status().ordinal());
        }
        assertEquals(Status.NOT_TRIED, items.get(items.size() - 1).status());
    }

    @Test
    void aConceptNotRevisitedForAWhileIsTaggedReviewSoon() {
        Item old = find(view(), "old");
        assertTrue(old.reviewSoon());
        assertTrue(old.evidence().contains("last practised"), old.evidence());
        assertFalse(find(view(), "weak").reviewSoon(), "an open weakness is already being practised");
    }

    @Test
    void theStudentNeverSeesModelJargonOrProbabilities() {
        // Only the evidence sentence is shown to the student (status is turned into a label by the browser).
        for (Item i : view().items()) {
            String text = i.evidence().toLowerCase(Locale.ROOT);
            assertFalse(text.isBlank());
            for (String banned : List.of("weak", "slip", "shaky", "p(", "known", "priority", "probab")) {
                assertFalse(text.contains(banned), banned + " in: " + i.evidence());
            }
        }
    }

    @Test
    void unseenConceptsSayNotAskedYetAndCarryNoMistake() {
        Item u = find(view(), "unseen");
        assertEquals("Not asked yet", u.evidence());
        assertNull(u.lastMistake());
    }

    @Test
    void conceptsMissingFromTheStateMapAreTreatedAsNotTried() {
        View v = ConceptInsights.build(CONCEPTS, Map.of(), null, NOW);
        assertEquals(CONCEPTS.size(), v.counts().notTried());
        assertTrue(v.focus().isEmpty());
    }

    @Test
    void anEmptyHandoutGivesAnEmptyView() {
        View v = ConceptInsights.build(List.of(), Map.of(), Map.of(), NOW);
        assertEquals(0, v.counts().total());
        assertTrue(v.items().isEmpty());
    }
}
