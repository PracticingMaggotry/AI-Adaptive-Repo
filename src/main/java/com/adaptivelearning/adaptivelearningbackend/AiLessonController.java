package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Serves the Learning Hub lesson feature, generating and caching adaptive lesson content per student, topic, and tier. */
@RestController
@RequestMapping("/api/ai")
public class AiLessonController {

    @Autowired private FirstQuizResultRepository firstQuizResultRepository;
    @Autowired private LessonCacheRepository lessonCacheRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private ClaudeService claudeService;
    @Autowired private DailyActionLimiter dailyActionLimiter;

    /** Daily per-student cap on lesson regenerations (cache misses); cache hits don't count. */
    private static final int MAX_LESSON_REGENERATIONS_PER_DAY = 10;

    private final ObjectMapper mapper = new ObjectMapper();

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

        Optional<FirstQuizResult> resultOpt =
                firstQuizResultRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);

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

        Optional<LessonCache> cached = lessonCacheRepository
                .findByStudentIdAndTopicIgnoreCaseAndTierIgnoreCase(studentId, topic, tier);
        if (cached.isPresent()) {
            Map<String, Object> response = parseLessonJson(cached.get().getContentJson(), topic, tier, score);
            if (response != null) return response;
        }
        Long existingCacheId = cached.map(LessonCache::getId).orElse(null);

        if (!dailyActionLimiter.tryConsume("lesson-regeneration", studentId, MAX_LESSON_REGENERATIONS_PER_DAY)) {
            Map<String, Object> limitResponse = new LinkedHashMap<>();
            limitResponse.put("topic", topic);
            limitResponse.put("tier", tier);
            limitResponse.put("score", score);
            limitResponse.put("noAttemptYet", false);
            limitResponse.put("intro", "You've reached today's limit of " + MAX_LESSON_REGENERATIONS_PER_DAY
                    + " new lesson generations. Please try again tomorrow.");
            limitResponse.put("concepts", List.of());
            limitResponse.put("tips", List.of());
            limitResponse.put("studyPlan", List.of());
            return limitResponse;
        }

        String knowledgeCtx = buildKnowledgeContext(studentId, topic);
        String raw = claudeService.generateLessonContent(topic, knowledgeCtx, score, tier);

        Map<String, Object> response = parseLessonJson(raw, topic, tier, score);
        if (response == null) {
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
            LessonCache toSave = new LessonCache(studentId, topic, tier, score, raw);
            if (existingCacheId != null) toSave.setId(existingCacheId);
            lessonCacheRepository.save(toSave);
        } catch (Exception e) {
            System.err.println("Could not cache lesson content: " + e.getMessage());
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
            String rendered = MaterialController.knowledgeContextOrFullText(material, null);
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
                concepts.add(concept);
            });
            response.put("concepts", concepts);

            List<String> tips = new java.util.ArrayList<>();
            node.path("tips").forEach(t -> tips.add(t.asText("")));
            response.put("tips", tips);

            List<String> studyPlan = new java.util.ArrayList<>();
            node.path("studyPlan").forEach(s -> studyPlan.add(s.asText("")));
            response.put("studyPlan", studyPlan);

            return response;
        } catch (Exception e) {
            System.err.println("Could not parse lesson content JSON: " + e.getMessage());
            return null;
        }
    }
}