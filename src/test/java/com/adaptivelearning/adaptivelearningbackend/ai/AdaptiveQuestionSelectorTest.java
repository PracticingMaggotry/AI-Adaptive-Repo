package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveQuestionSelectorTest {

    private static ConceptState concept(double pKnown, State state) {
        ConceptState concept = new ConceptState("concept");
        concept.pKnown = pKnown;
        concept.state = state;
        return concept;
    }

    private static List<ItemDifficultyModel.Observation> observations(int bloom, String type,
                                                                        int correct, int incorrect,
                                                                        String prefix) {
        List<ItemDifficultyModel.Observation> out = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < correct; i++) {
            out.add(new ItemDifficultyModel.Observation(prefix + "-c-" + index++, bloom, type, 1.0));
        }
        for (int i = 0; i < incorrect; i++) {
            out.add(new ItemDifficultyModel.Observation(prefix + "-w-" + index++, bloom, type, 0.0));
        }
        return out;
    }

    @Test
    void unseenConceptUsesFoundationalInitialDemand() {
        var selection = AdaptiveQuestionSelector.select(concept(0.30, State.UNSEEN),
                ItemDifficultyModel.estimate(List.of()));
        assertEquals(AiPolicy.ADAPTIVE_INITIAL_BLOOM_LEVEL, selection.targetBloomLevel());
        assertEquals(AdaptiveQuestionSelector.EvidenceBasis.BKT_BASELINE, selection.basis());
    }

    @Test
    void higherBktMasteryAllowsHigherCognitiveDemand() {
        var low = AdaptiveQuestionSelector.select(concept(0.20, State.WEAK), null);
        var high = AdaptiveQuestionSelector.select(concept(0.90, State.LEARNED), null);
        assertTrue(high.targetBloomLevel() > low.targetBloomLevel());
        assertEquals(AdaptiveQuestionSelector.EvidenceBasis.BKT_BASELINE, low.basis());
        assertEquals(AdaptiveQuestionSelector.EvidenceBasis.BKT_BASELINE, high.basis());
    }

    @Test
    void sparseObservedDemandDoesNotOverrideBktBaseline() {
        List<ItemDifficultyModel.Observation> data = observations(3, "MCQ", 2, 2, "few");
        var selection = AdaptiveQuestionSelector.select(concept(0.50, State.SHAKY),
                ItemDifficultyModel.estimate(data));
        assertEquals(selection.bktBaselineLevel(), selection.targetBloomLevel());
        assertEquals(AdaptiveQuestionSelector.EvidenceBasis.BKT_BASELINE, selection.basis());
    }

    @Test
    void observedFacilityCanMoveRequestedDemandDownOneBloomLevel() {
        List<ItemDifficultyModel.Observation> data = new ArrayList<>();
        data.addAll(observations(3, "MCQ", 8, 0, "level3"));
        data.addAll(observations(4, "MCQ", 0, 8, "level4"));
        var selection = AdaptiveQuestionSelector.select(concept(0.50, State.SHAKY),
                ItemDifficultyModel.estimate(data));

        assertEquals(4, selection.bktBaselineLevel());
        assertEquals(3, selection.targetBloomLevel());
        assertEquals(AdaptiveQuestionSelector.EvidenceBasis.BKT_WITH_OBSERVED_DEMAND, selection.basis());
        assertTrue(selection.observations() >= AiPolicy.ADAPTIVE_MIN_DEMAND_OBSERVATIONS);
        assertTrue(selection.distinctItems() >= AiPolicy.ADAPTIVE_MIN_DISTINCT_ITEMS);
    }

    @Test
    void empiricalAdjustmentNeverMovesMoreThanOneBloomLevel() {
        List<ItemDifficultyModel.Observation> data = new ArrayList<>();
        data.addAll(observations(1, "MCQ", 12, 0, "level1"));
        data.addAll(observations(2, "MCQ", 12, 0, "level2"));
        data.addAll(observations(3, "MCQ", 12, 0, "level3"));
        data.addAll(observations(4, "MCQ", 0, 12, "level4"));
        var selection = AdaptiveQuestionSelector.select(concept(0.50, State.SHAKY),
                ItemDifficultyModel.estimate(data));
        assertTrue(Math.abs(selection.targetBloomLevel() - selection.bktBaselineLevel())
                <= AiPolicy.ADAPTIVE_MAX_BLOOM_ADJUSTMENT);
    }

    @Test
    void fixedQuestionTypeUsesOnlyMatchingFormatEvidence() {
        List<ItemDifficultyModel.Observation> data = new ArrayList<>();
        data.addAll(observations(3, "MCQ", 0, 8, "mcq-hard"));
        data.addAll(observations(3, "FILLBLANK", 8, 0, "fill-easy"));
        var snapshot = ItemDifficultyModel.estimate(data);
        var concept = concept(0.50, State.SHAKY);

        var mcq = AdaptiveQuestionSelector.select(concept, snapshot, "MCQ");
        var fillBlank = AdaptiveQuestionSelector.select(concept, snapshot, "FILLBLANK");

        assertNotNull(mcq.estimatedFacility());
        assertNotNull(fillBlank.estimatedFacility());
        assertTrue(mcq.estimatedFacility() < 0.25);
        assertTrue(fillBlank.estimatedFacility() > 0.75);
    }

    @Test
    void demandSelectionUsesObservedItemCountsNotGroupPriorsRepeatedPerFormat() {
        List<ItemDifficultyModel.Observation> data = new ArrayList<>();
        data.addAll(observations(3, "MCQ", 4, 0, "mcq"));
        data.addAll(observations(3, "FILLBLANK", 4, 0, "fill"));
        var selection = AdaptiveQuestionSelector.select(concept(0.50, State.SHAKY),
                ItemDifficultyModel.estimate(data));
        assertEquals(AdaptiveQuestionSelector.EvidenceBasis.BKT_WITH_OBSERVED_DEMAND, selection.basis());
        assertEquals(8, selection.observations());
        assertEquals(8, selection.distinctItems());
    }
}
