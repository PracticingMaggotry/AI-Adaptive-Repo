package com.adaptivelearning.adaptivelearningbackend.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemDifficultyModelTest {

    private static ItemDifficultyModel.Observation obs(String id, int bloom, String type, double credit) {
        return new ItemDifficultyModel.Observation(id, bloom, type, credit);
    }

    @Test
    void higherObservedFacilityMeansLowerEstimatedDifficulty() {
        List<ItemDifficultyModel.Observation> data = new ArrayList<>();
        data.add(obs("easy-a", 1, "MCQ", 1));
        data.add(obs("easy-a", 1, "MCQ", 1));
        data.add(obs("easy-b", 1, "MCQ", 1));
        data.add(obs("easy-b", 1, "MCQ", 1));
        data.add(obs("hard-a", 6, "MCQ", 0));
        data.add(obs("hard-a", 6, "MCQ", 0));
        data.add(obs("hard-b", 6, "MCQ", 0));
        data.add(obs("hard-b", 6, "MCQ", 0));

        var snapshot = ItemDifficultyModel.estimate(data);
        var lowerDemand = snapshot.demandGroups().get(new ItemDifficultyModel.DemandGroup(1, "MCQ"));
        var higherDemand = snapshot.demandGroups().get(new ItemDifficultyModel.DemandGroup(6, "MCQ"));

        assertNotNull(lowerDemand);
        assertNotNull(higherDemand);
        assertTrue(lowerDemand.posterior().facility() > higherDemand.posterior().facility());
        assertTrue(lowerDemand.posterior().difficulty() < higherDemand.posterior().difficulty());
    }

    @Test
    void sparseItemEstimateIsRegularizedTowardOtherItemsInItsDemandGroup() {
        List<ItemDifficultyModel.Observation> data = new ArrayList<>();
        for (int i = 0; i < 12; i++) data.add(obs("established-" + i, 4, "MCQ", 1));
        data.add(obs("new-item", 4, "MCQ", 0));

        var estimate = ItemDifficultyModel.estimate(data).items().get("new-item");
        assertNotNull(estimate);
        assertTrue(estimate.priorFacility() > 0.5);
        assertTrue(estimate.posterior().facility() > 0.5,
                "one response should not override the facility estimate from the demand group");
        assertTrue(estimate.empiricalWeight() < 0.25);
    }

    @Test
    void repeatedItemEvidenceMovesItsEstimateAwayFromTheGroupPrior() {
        List<ItemDifficultyModel.Observation> data = new ArrayList<>();
        for (int i = 0; i < 8; i++) data.add(obs("other-" + i, 3, "FILLBLANK", 1));
        data.add(obs("repeated-item", 3, "FILLBLANK", 0));
        data.add(obs("repeated-item", 3, "FILLBLANK", 0));
        data.add(obs("repeated-item", 3, "FILLBLANK", 0));

        var estimate = ItemDifficultyModel.estimate(data).items().get("repeated-item");
        assertNotNull(estimate);
        assertTrue(estimate.posterior().difficulty() > 0.5);
        assertEquals(3, estimate.attempts());
        assertEquals(3, estimate.incorrect());
    }

    @Test
    void partialCreditEssaysAndInvalidBloomLevelsAreExcluded() {
        List<ItemDifficultyModel.Observation> data = List.of(
                obs("binary-correct", 2, "MCQ", 1),
                obs("binary-wrong", 2, "MCQ", 0),
                obs("partial", 2, "MATCHING", 0.5),
                obs("essay", 2, "ESSAY", 1),
                obs("bad-bloom", 7, "MCQ", 1),
                obs("missing-bloom", 0, "MCQ", 0),
                obs("unknown-type", 2, "UNRECOGNIZED", 1));

        var snapshot = ItemDifficultyModel.estimate(data);
        assertEquals(2, snapshot.acceptedObservations());
        assertEquals(5, snapshot.ignoredObservations());
        assertEquals(2, snapshot.items().size());
    }

    @Test
    void itemPosteriorDoesNotDoubleCountItsOwnDataInTheGroupPrior() {
        var snapshot = ItemDifficultyModel.estimate(List.of(
                obs("same-item", 5, "CONCEPTID", 1),
                obs("same-item", 5, "CONCEPTID", 1),
                obs("other-item", 5, "CONCEPTID", 0)));
        var item = snapshot.items().get("same-item");
        assertEquals(0.4, item.priorFacility(), 1e-12,
                "only the other item's incorrect response and the Beta(2,2) prior inform the prior");
    }

    @Test
    void evidenceOrderDoesNotChangeEstimates() {
        List<ItemDifficultyModel.Observation> observations = new ArrayList<>(List.of(
                obs("a", 2, "MCQ", 1), obs("a", 2, "MCQ", 0), obs("b", 2, "MCQ", 1),
                obs("c", 4, "TRUEFALSE", 0), obs("c", 4, "TRUEFALSE", 1)));
        var first = ItemDifficultyModel.estimate(observations);
        Collections.reverse(observations);
        var second = ItemDifficultyModel.estimate(observations);

        assertEquals(first.demandGroups().get(new ItemDifficultyModel.DemandGroup(2, "MCQ")).posterior().facility(),
                second.demandGroups().get(new ItemDifficultyModel.DemandGroup(2, "MCQ")).posterior().facility(), 1e-12);
        assertEquals(first.items().get("a").posterior().facility(), second.items().get("a").posterior().facility(), 1e-12);
    }
}
