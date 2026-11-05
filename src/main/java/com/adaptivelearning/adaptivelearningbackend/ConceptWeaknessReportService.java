package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ScopeService.ConceptDef;
import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only student report assembled from the existing handout scopes, learner model, and permanent response log.
 * No additional learner state is persisted by this service.
 */
@Service
public class ConceptWeaknessReportService {

    /** A concept needs repeated eligible binary evidence before the report calls it a detected weakness. */
    static final int MIN_BKT_EVIDENCE_FOR_STATUS = 2;

    public record Report(
            String generatedAt,
            int handoutCount,
            int conceptCount,
            int needsReviewCount,
            int developingCount,
            int demonstratedCount,
            int insufficientDataCount,
            List<HandoutReport> handouts) {}

    public record HandoutReport(
            Long scopeId,
            String handoutName,
            String topic,
            String uploadedAt,
            boolean hasConceptMap,
            int totalQuestionAttempts,
            int mappedQuestionAttempts,
            int unassignedQuestionAttempts,
            List<ConceptReport> concepts) {}

    public record ConceptReport(
            String conceptKey,
            String name,
            String definition,
            String status,
            String statusLabel,
            boolean detectedWeakness,
            int questionAttempts,
            int quizAttempts,
            int correctResponses,
            int incorrectResponses,
            int partialResponses,
            int bktEligibleAttempts,
            Double averageCreditPercent,
            Double masteryProbability,
            String lastAttemptAt) {}

    private final StudyScopeRepository scopes;
    private final MaterialRepository materials;
    private final QuestionResponseRepository responses;
    private final ScopeService scopeService;
    private final LearnerModelService learnerModelService;

    public ConceptWeaknessReportService(StudyScopeRepository scopes,
                                       MaterialRepository materials,
                                       QuestionResponseRepository responses,
                                       ScopeService scopeService,
                                       LearnerModelService learnerModelService) {
        this.scopes = scopes;
        this.materials = materials;
        this.responses = responses;
        this.scopeService = scopeService;
        this.learnerModelService = learnerModelService;
    }

    public Report build(String studentId) {
        List<StudyScope> activeScopes = scopes.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                .filter(s -> StudyScope.ACTIVE.equalsIgnoreCase(s.getStatus()))
                .toList();
        List<Material> studentMaterials = materials.findByUploadedByOrderByUploadedAtDesc(studentId);

        List<HandoutReport> handouts = new ArrayList<>();
        int conceptCount = 0, needsReview = 0, developing = 0, demonstrated = 0, insufficient = 0;

        for (StudyScope scope : activeScopes) {
            List<ConceptDef> conceptDefs = scopeService.concepts(scope);
            Map<String, ConceptDef> definitions = conceptDefs.stream()
                    .collect(Collectors.toMap(ConceptDef::key, c -> c, (a, b) -> a, LinkedHashMap::new));

            LearnerModelService.Model learnerModel = learnerModelService.build(scope);
            List<QuestionResponse> evidenceRows = responseHistory(scope);
            Map<String, List<QuestionResponse>> byConcept = evidenceRows.stream()
                    .filter(r -> r.getConceptKey() != null && definitions.containsKey(r.getConceptKey()))
                    .collect(Collectors.groupingBy(QuestionResponse::getConceptKey));

            int mappedAttempts = byConcept.values().stream().mapToInt(List::size).sum();
            List<ConceptReport> conceptReports = new ArrayList<>();
            for (ConceptDef def : conceptDefs) {
                List<QuestionResponse> rows = byConcept.getOrDefault(def.key(), List.of());
                ConceptState state = learnerModel.states().get(def.key());
                ConceptReport concept = summarize(def, rows, state);
                conceptReports.add(concept);
                conceptCount++;
                switch (concept.status()) {
                    case "NEEDS_REVIEW" -> needsReview++;
                    case "DEVELOPING" -> developing++;
                    case "DEMONSTRATED" -> demonstrated++;
                    default -> insufficient++;
                }
            }

            Material material = findMaterial(scope, studentMaterials);
            String filename = material == null ? null : material.getOriginalFilename();
            String handoutName = filename == null || filename.isBlank() ? "Handout details unavailable" : filename;
            LocalDateTime uploaded = material != null && material.getUploadedAt() != null
                    ? material.getUploadedAt() : scope.getCreatedAt();

            handouts.add(new HandoutReport(
                    scope.getId(), handoutName, scope.getTopic(), uploaded == null ? null : uploaded.toString(),
                    !conceptDefs.isEmpty(), evidenceRows.size(), mappedAttempts, evidenceRows.size() - mappedAttempts,
                    conceptReports));
        }

        return new Report(Instant.now().toString(), activeScopes.size(), conceptCount,
                needsReview, developing, demonstrated, insufficient, handouts);
    }

    private List<QuestionResponse> responseHistory(StudyScope scope) {
        LinkedHashMap<String, QuestionResponse> unique = new LinkedHashMap<>();
        addResponses(unique, responses.findByScopeIdOrderByAnsweredAtAsc(scope.getId()));
        if (scope.getCarriedFromScopeId() != null) {
            addResponses(unique, responses.findByScopeIdOrderByAnsweredAtAsc(scope.getCarriedFromScopeId()));
        }
        return new ArrayList<>(unique.values());
    }

    private static void addResponses(Map<String, QuestionResponse> into, List<QuestionResponse> rows) {
        for (QuestionResponse row : rows) {
            String key = row.getId() != null ? "id:" + row.getId()
                    : "legacy:" + row.getAttemptId() + ":" + row.getQuestionId() + ":" + row.getAnsweredAt();
            into.putIfAbsent(key, row);
        }
    }

    private static ConceptReport summarize(ConceptDef definition, List<QuestionResponse> rows, ConceptState state) {
        int correct = 0, incorrect = 0, partial = 0;
        double creditTotal = 0.0;
        Set<Long> quizzes = new HashSet<>();
        LocalDateTime last = null;
        for (QuestionResponse row : rows) {
            double credit = Math.max(0.0, Math.min(1.0, row.getCredit()));
            creditTotal += credit;
            if (credit >= 1.0) correct++;
            else if (credit <= 0.0) incorrect++;
            else partial++;
            if (row.getAttemptId() != null) quizzes.add(row.getAttemptId());
            if (row.getAnsweredAt() != null && (last == null || row.getAnsweredAt().isAfter(last))) last = row.getAnsweredAt();
        }

        int eligible = state == null ? 0 : state.asked;
        double pKnown = state == null ? AiPolicy.BKT_P_INIT : state.pKnown;
        String status;
        String label;
        boolean detected;
        if (eligible < MIN_BKT_EVIDENCE_FOR_STATUS) {
            status = "INSUFFICIENT_DATA";
            label = "Insufficient Data";
            detected = false;
        } else if (state.state == State.WEAK || pKnown < AiPolicy.BKT_WEAK_PROBABILITY) {
            status = "NEEDS_REVIEW";
            label = "Needs Review";
            detected = true;
        } else if (state.state == State.LEARNED || pKnown >= AiPolicy.BKT_LEARNED_PROBABILITY) {
            status = "DEMONSTRATED";
            label = "Demonstrated";
            detected = false;
        } else {
            status = "DEVELOPING";
            label = "Developing";
            detected = false;
        }

        Double averageCredit = rows.isEmpty() ? null : (creditTotal / rows.size()) * 100.0;
        Double mastery = eligible < MIN_BKT_EVIDENCE_FOR_STATUS ? null : pKnown * 100.0;
        return new ConceptReport(definition.key(), definition.term(), definition.definition(), status, label, detected,
                rows.size(), quizzes.size(), correct, incorrect, partial, eligible, averageCredit, mastery,
                last == null ? null : last.toString());
    }

    private static Material findMaterial(StudyScope scope, List<Material> studentMaterials) {
        List<Material> sameTopic = studentMaterials.stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(scope.getTopic()))
                .toList();
        if (sameTopic.isEmpty()) return null;
        for (Material material : sameTopic) {
            String contentHash = material.getContentHash();
            if (contentHash != null && contentHash.equals(scope.getContentHash())) return material;
            if (scope.getContentHash() != null && material.getId() != null
                    && scope.getContentHash().equals("material-" + material.getId())) return material;
        }
        // Do not attach a potentially unrelated file merely because it shares the topic label.
        return null;
    }
}
