package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ScopeService.ConceptDef;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConceptWeaknessReportServiceTest {

    private StudyScopeRepository scopes;
    private MaterialRepository materials;
    private QuestionResponseRepository responses;
    private ScopeService scopeService;
    private LearnerModelService learnerModelService;
    private ConceptWeaknessReportService service;
    private StudyScope scope;
    private List<ConceptDef> concepts;

    @BeforeEach
    void setUp() {
        scopes = mock(StudyScopeRepository.class);
        materials = mock(MaterialRepository.class);
        responses = mock(QuestionResponseRepository.class);
        scopeService = mock(ScopeService.class);
        learnerModelService = mock(LearnerModelService.class);
        service = new ConceptWeaknessReportService(scopes, materials, responses, scopeService, learnerModelService);

        scope = new StudyScope("student@example.com", "Cell Biology", "hash-1", "[]", 1000);
        ReflectionTestUtils.setField(scope, "id", 11L);
        concepts = List.of(
                new ConceptDef("cell-membrane", "Cell Membrane", "Controls movement into and out of the cell."),
                new ConceptDef("cell-nucleus", "Cell Nucleus", "Contains genetic material."));

        when(scopes.findByStudentIdOrderByCreatedAtDesc("student@example.com")).thenReturn(List.of(scope));
        when(scopeService.concepts(scope)).thenReturn(concepts);
        when(materials.findByUploadedByOrderByUploadedAtDesc("student@example.com")).thenReturn(List.of(material()));
        when(responses.findByScopeIdOrderByAnsweredAtAsc(11L)).thenReturn(List.of());
    }

    @Test
    void repeatedWrongResponsesAreReportedAsNeedsReviewWithEvidence() {
        List<QuestionResponse> rows = List.of(
                response("cell-membrane", 0.0, 101L, 1001L),
                response("cell-membrane", 0.0, 102L, 1002L));
        when(responses.findByScopeIdOrderByAnsweredAtAsc(11L)).thenReturn(rows);
        stubLearnerModel(List.of(event("cell-membrane", 0.0, 0), event("cell-membrane", 0.0, 1)));

        var report = service.build("student@example.com");
        var concept = report.handouts().get(0).concepts().get(0);

        assertEquals("unit-1.pdf", report.handouts().get(0).handoutName());
        assertEquals("Cell Biology", report.handouts().get(0).topic());
        assertEquals("NEEDS_REVIEW", concept.status());
        assertTrue(concept.detectedWeakness());
        assertEquals(2, concept.questionAttempts());
        assertEquals(2, concept.incorrectResponses());
        assertEquals(0, concept.correctResponses());
        assertEquals(2, concept.bktEligibleAttempts());
        assertNotNull(concept.masteryProbability());
    }

    @Test
    void oneWrongResponseIsInsufficientRatherThanAnAssertedWeakness() {
        when(responses.findByScopeIdOrderByAnsweredAtAsc(11L))
                .thenReturn(List.of(response("cell-membrane", 0.0, 101L, 1001L)));
        stubLearnerModel(List.of(event("cell-membrane", 0.0, 0)));

        var concept = service.build("student@example.com").handouts().get(0).concepts().get(0);

        assertEquals("INSUFFICIENT_DATA", concept.status());
        assertEquals("Insufficient Data", concept.statusLabel());
        assertFalse(concept.detectedWeakness());
        assertNull(concept.masteryProbability());
        assertEquals(1, concept.incorrectResponses());
    }

    @Test
    void partialCreditIsVisibleButDoesNotBecomeBinaryBktEvidence() {
        when(responses.findByScopeIdOrderByAnsweredAtAsc(11L)).thenReturn(List.of(
                response("cell-membrane", 0.5, 101L, 1001L),
                response("cell-membrane", 0.75, 102L, 1002L)));
        stubLearnerModel(List.of());

        var concept = service.build("student@example.com").handouts().get(0).concepts().get(0);

        assertEquals("INSUFFICIENT_DATA", concept.status());
        assertEquals(2, concept.questionAttempts());
        assertEquals(2, concept.partialResponses());
        assertEquals(0, concept.bktEligibleAttempts());
        assertEquals(62.5, concept.averageCreditPercent(), 1e-9);
        assertNull(concept.masteryProbability());
    }

    @Test
    void repeatedCorrectResponsesAreReportedAsDemonstrated() {
        when(responses.findByScopeIdOrderByAnsweredAtAsc(11L)).thenReturn(List.of(
                response("cell-membrane", 1.0, 101L, 1001L),
                response("cell-membrane", 1.0, 102L, 1002L),
                response("cell-membrane", 1.0, 103L, 1003L)));
        stubLearnerModel(List.of(
                event("cell-membrane", 1.0, 0), event("cell-membrane", 1.0, 1), event("cell-membrane", 1.0, 2)));

        var concept = service.build("student@example.com").handouts().get(0).concepts().get(0);
        assertEquals("DEMONSTRATED", concept.status());
        assertEquals(3, concept.correctResponses());
        assertFalse(concept.detectedWeakness());
    }

    @Test
    void unattemptedConceptIsInsufficientAndHasNoFabricatedAverage() {
        stubLearnerModel(List.of());
        var concept = service.build("student@example.com").handouts().get(0).concepts().get(1);
        assertEquals("INSUFFICIENT_DATA", concept.status());
        assertEquals(0, concept.questionAttempts());
        assertNull(concept.averageCreditPercent());
        assertNull(concept.masteryProbability());
    }

    private void stubLearnerModel(List<MasteryModel.Event> events) {
        LinkedHashMap<String, ConceptState> states = MasteryModel.evaluate(
                concepts.stream().map(ConceptDef::key).toList(), events, java.time.Instant.now());
        LearnerModelService.Model model = new LearnerModelService.Model(
                scope, concepts, states, Map.of(), null, events.size());
        when(learnerModelService.build(scope)).thenReturn(model);
    }

    private static MasteryModel.Event event(String key, double credit, int secondsOffset) {
        return new MasteryModel.Event(key, credit, "MCQ",
                java.time.Instant.parse("2026-11-01T00:00:00Z").plusSeconds(secondsOffset));
    }

    private static QuestionResponse response(String key, double credit, long questionId, long attemptId) {
        QuestionResponse row = new QuestionResponse();
        row.setConceptKey(key);
        row.setCredit(credit);
        row.setQuestionType("MCQ");
        row.setQuestionId(questionId);
        row.setAttemptId(attemptId);
        row.setResult(credit >= 1.0 ? "correct" : credit <= 0.0 ? "wrong" : "partial");
        row.setAnsweredAt(LocalDateTime.parse("2026-11-01T00:00:00").plusSeconds(attemptId));
        return row;
    }

    private static Material material() {
        Material material = new Material();
        material.setId(21L);
        material.setTopic("Cell Biology");
        material.setOriginalFilename("unit-1.pdf");
        material.setContentHash("hash-1");
        material.setUploadedBy("student@example.com");
        return material;
    }
}
