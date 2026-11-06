package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.adaptivelearning.adaptivelearningbackend.ai.CostModel;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt;
import com.adaptivelearning.adaptivelearningbackend.ai.PassageRetriever;
import com.adaptivelearning.adaptivelearningbackend.ai.SingleFlight;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

/**
 * Serves the Learning Hub lesson. A lesson is cached per (handout, tier, learner-state fingerprint): it is reused
 * until the student's open weaknesses / slips / recovered concepts change, then regenerated. Identical concurrent
 * requests share one generation.
 */
@RestController
@RequestMapping("/api/ai")
public class AiLessonController {

    @Autowired private FirstQuizResultRepository firstQuizResultRepository;
    @Autowired private LessonSnapshotRepository lessonSnapshotRepository;
    @Autowired private ScopeService scopeService;
    @Autowired private LearnerModelService learnerModelService;
    @Autowired private AiGateway aiGateway;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private ClaudeService claudeService;
    @Autowired private QuizController quizController;
    @Autowired private DailyActionLimiter dailyActionLimiter;
    @Autowired private ConfigurationService configurationService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SingleFlight<String, Map<String, Object>> lessonFlight = new SingleFlight<>();

    @GetMapping("/lesson")
    public Map<String, Object> getLesson(@RequestParam String topic, HttpSession session, HttpServletResponse httpResponse) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            Map<String, Object> unauthorized = new LinkedHashMap<>();
            unauthorized.put("success", false);
            unauthorized.put("message", "Please log in first.");
            return unauthorized;
        }

        // Resolve the handout first: this closes out a stale baseline left over from an older handout of the same name.
        Material material = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId).stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);
        StudyScope scope = material == null ? null : scopeService.ensure(studentId, topic, material, 0);

        Optional<FirstQuizResult> resultOpt = scope == null
                ? Optional.empty()
                : firstQuizResultRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);

        if (resultOpt.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("topic", topic);
            response.put("tier", "Easy");
            response.put("score", null);
            response.put("noAttemptYet", true);
            response.put("intro", "You haven't taken a quiz on " + topic + " yet. Take the first quiz so your lesson can be calibrated to your actual performance.");
            response.put("concepts", List.of());
            response.put("tips", List.of(
                    "Upload a handout for this topic if you haven't already.",
                    "Take the Easy quiz first — this becomes your locked baseline score.",
                    "Come back here right after to get a lesson tailored to how you did."
            ));
            response.put("studyPlan", List.of());
            return response;
        }

        FirstQuizResult result = resultOpt.get();

        boolean hasAdapted = result.getLatestAdaptedScore() != null && result.getLatestAdaptedTier() != null;
        String tier = hasAdapted ? result.getLatestAdaptedTier() : result.getGeneralDifficulty();
        double score = hasAdapted ? result.getLatestAdaptedScore() : result.getGeneralScore();

        if (tier == null || tier.isBlank()) tier = "Easy";
        // The stored tier is the one the student PLAYED; the lesson should match the tier they earned
        // (>=80 up, <50 down), the same rule that drives nextDiff and the Adapted Quiz.
        tier = DifficultyTier.next(tier, score,
                configurationService.getHardDifficultyThreshold(),
                configurationService.getMediumDifficultyThreshold());

        // What the AI knows about this student on this handout, and a fingerprint of it.
        LearnerModelService.Model model = learnerModelService.build(scope);
        boolean hasConcepts = !model.concepts().isEmpty();
        // Ability model: the lesson is pitched at the label of the student's current ability, like the next quiz.
        if (hasConcepts && learnerModelService.abilityAdaptation()) tier = model.tier();
        LearnerPrompt.Rendered rendered = hasConcepts
                ? learnerModelService.prompt(model, AiPolicy.LESSON_MAX_CONCEPT_ENTRIES, Instant.now()) : null;
        String legacyMistakes = hasConcepts ? null : buildMistakeContext(studentId, topic, scope.getId());
        String stateHash = hasConcepts ? rendered.stateHash()
                : "legacy-" + LearnerPrompt.sha1(legacyMistakes == null ? "" : legacyMistakes).substring(0, 16);

        Optional<LessonSnapshot> cached = lessonSnapshotRepository
                .findFirstByScopeIdAndTierAndStateHashAndPromptVersionOrderByIdDesc(scope.getId(), tier, stateHash, AiPolicy.PROMPT_VERSION);
        if (cached.isPresent()) {
            Map<String, Object> response = parseLessonJson(cached.get().getContentJson(), topic, tier, score);
            if (response != null) return response;
        }

        final String finalTier = tier;
        final double finalScore = score;

        // Out of AI budget for today: show the newest lesson we already have for this tier instead of generating.
        if (aiGateway.level(studentId) == CostModel.Level.BLOCKED) {
            Optional<LessonSnapshot> stale = lessonSnapshotRepository.findFirstByScopeIdAndTierOrderByIdDesc(scope.getId(), finalTier);
            Map<String, Object> response = stale.map(l -> parseLessonJson(l.getContentJson(), topic, finalTier, finalScore)).orElse(null);
            if (response != null) return response;
        }

        final String legacyCtx = legacyMistakes;
        final String learnerText = rendered != null && model.responseCount() > 0 ? rendered.text() : legacyCtx;
        final String flightKey = scope.getId() + "|" + finalTier + "|" + stateHash;
        try {
            return lessonFlight.run(flightKey, AiPolicy.SCHED_MAX_WAIT_MS + 30_000L, () -> {
                try (AiCallContext.Handle ignored = AiCallContext.open(studentId)) {
                    return generateLesson(studentId, topic, scope, finalTier, finalScore, stateHash, learnerText);
                }
            });
        } catch (Exception e) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("topic", topic);
            failure.put("tier", finalTier);
            failure.put("score", finalScore);
            failure.put("noAttemptYet", false);
            failure.put("intro", "We couldn't generate a lesson right now. Please try again in a moment.");
            failure.put("concepts", List.of());
            failure.put("tips", List.of());
            failure.put("studyPlan", List.of());
            return failure;
        }
    }

    private Map<String, Object> generateLesson(String studentId, String topic, StudyScope scope, String tier,
                                               double score, String stateHash, String learnerText) {
        int maxLessonRegenerationsPerDay = dailyActionLimiter.getEffectiveLimit("lesson-regeneration", studentId);
        if (!dailyActionLimiter.tryConsume("lesson-regeneration", studentId, maxLessonRegenerationsPerDay)) {
            Map<String, Object> limitResponse = new LinkedHashMap<>();
            limitResponse.put("topic", topic);
            limitResponse.put("tier", tier);
            limitResponse.put("score", score);
            limitResponse.put("noAttemptYet", false);
            limitResponse.put("intro", "You've reached today's limit of " + maxLessonRegenerationsPerDay
                    + " new lesson generations. Please try again tomorrow.");
            limitResponse.put("concepts", List.of());
            limitResponse.put("tips", List.of());
            limitResponse.put("studyPlan", List.of());
            return limitResponse;
        }

        String knowledgeCtx = buildKnowledgeContext(studentId, topic);
        String raw = claudeService.generateLessonContent(topic, knowledgeCtx, score, tier, learnerText);

        Map<String, Object> response = parseLessonJson(raw, topic, tier, score);
        if (response == null) {
            dailyActionLimiter.refund("lesson-regeneration", studentId);
            response = new LinkedHashMap<>();
            response.put("topic", topic);
            response.put("tier", tier);
            response.put("score", score);
            response.put("noAttemptYet", false);
            response.put("intro", "We couldn't generate a lesson right now. Please try again in a moment.");
            response.put("concepts", List.of());
            response.put("tips", List.of());
            response.put("studyPlan", List.of());
            return response;
        }

        try {
            lessonSnapshotRepository.save(new LessonSnapshot(scope.getId(), studentId, topic, tier, stateHash,
                    AiPolicy.PROMPT_VERSION, score, raw));
        } catch (Exception e) {
            System.err.println("Could not store lesson snapshot: " + e.getMessage());
        }
        return response;
    }

    /** Builds the grounding text passed to Claude, from the student's uploaded material for this topic. */
    private String buildKnowledgeContext(String studentId, String topic) {
        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId);
        Material material = materials.stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);

        if (material == null) return null;

        if (material.getKnowledgeExtract() != null && !material.getKnowledgeExtract().isBlank()) {
            String rendered = MaterialController.knowledgeContextForLesson(material, null);
            if (rendered != null && !rendered.isBlank()) return rendered;
        }

        StringBuilder ctx = new StringBuilder();
        if (material.getTopicSummary() != null && !material.getTopicSummary().isBlank()) {
            ctx.append(material.getTopicSummary()).append("\n\n");
        }
        if (material.getExtractedPreview() != null && !material.getExtractedPreview().isBlank()) {
            ctx.append(material.getExtractedPreview());
        }
        return ctx.length() == 0 ? null : ctx.toString();
    }

    private static String clip(String v) {
        if (v == null) return "";
        String flat = v.replaceAll("\\s+", " ").trim();
        return flat.length() > AiPolicy.EXAMPLE_FIELD_CHARS ? flat.substring(0, AiPolicy.EXAMPLE_FIELD_CHARS) + "..." : flat;
    }

    /**
     * FALLBACK for handouts with no extracted concept list: a short summary of the wrong/partial answers on THIS
     * handout. Normal lessons use the learner model instead. Returns null when there is no usable history.
     */
    private String buildMistakeContext(String studentId, String topic, Long scopeId) {
        try {
            List<Map<String, String>> wrong = quizController.gatherWrongQuestionDetails(studentId, topic, scopeId);
            if (wrong == null || wrong.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map<String, String> w : wrong) {
                if (n >= AiPolicy.LESSON_MAX_CONCEPT_ENTRIES) break;
                String q = clip(w.get("question"));
                if (q.isBlank()) continue;
                n++;
                sb.append(n).append(". Question: ").append(q).append("\n");
                String ans = clip(w.get("yourAnswer"));
                sb.append("   Student answered: ").append(ans.isBlank() ? "(no answer)" : ans).append("\n");
                String correct = clip(w.get("correctAnswer"));
                if (!correct.isBlank()) sb.append("   Correct: ").append(correct).append("\n");
                String missed = clip(w.get("missedPoints"));
                if (!missed.isBlank()) sb.append("   Missed points: ").append(missed).append("\n");
            }
            return n == 0 ? null : sb.toString();
        } catch (Exception e) {
            System.err.println("Could not build mistake context for lesson (non-fatal): " + e.getMessage());
            return null;
        }
    }

    /** Parses Claude's lesson JSON into the response shape the frontend expects, or null if parsing fails. */
    private Map<String, Object> parseLessonJson(String raw, String topic, String tier, double score) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = mapper.readTree(cleaned);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("topic", topic);
            response.put("tier", tier);
            response.put("score", score);
            response.put("noAttemptYet", false);
            response.put("intro", node.path("intro").asText(""));

            List<Map<String, String>> concepts = new java.util.ArrayList<>();
            node.path("concepts").forEach(c -> {
                Map<String, String> concept = new LinkedHashMap<>();
                concept.put("term", c.path("term").asText(""));
                concept.put("explanation", c.path("explanation").asText(""));
                concept.put("example", c.path("example").asText(""));
                concepts.add(concept);
            });
            response.put("concepts", concepts);

            List<String> tips = new java.util.ArrayList<>();
            node.path("tips").forEach(t -> tips.add(t.asText("")));
            response.put("tips", tips);

            List<String> studyPlan = new java.util.ArrayList<>();
            node.path("studyPlan").forEach(s -> studyPlan.add(s.asText("")));
            response.put("studyPlan", studyPlan);

            List<String> checkYourself = new java.util.ArrayList<>();
            node.path("checkYourself").forEach(c -> {
                String q = c.asText("").trim();
                if (!q.isBlank()) checkYourself.add(q);
            });
            response.put("checkYourself", checkYourself);

            return response;
        } catch (Exception e) {
            System.err.println("Could not parse lesson content JSON: " + e.getMessage());
            return null;
        }
    }
}