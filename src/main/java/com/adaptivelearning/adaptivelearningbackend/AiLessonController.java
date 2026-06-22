package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the Learning Hub lesson feature (learninghub.html → openLesson()).
 *
 * This restores GET /api/ai/lesson, which was accidentally deleted. The
 * frontend has always expected this endpoint and degrades gracefully
 * ("Could not load lesson...") without it — this wires it back up using
 * pieces that already existed for exactly this purpose:
 *
 *   - FirstQuizResult   → which score/tier should drive the lesson
 *                         (locked general score, or latest adapted score
 *                         if the student has taken an Adapted Quiz since)
 *   - LessonCache       → avoids calling Claude again for a tier that's
 *                         already been generated for this student+topic
 *   - ClaudeService     → generateLessonContent() already returns the
 *                         exact JSON shape the frontend renders
 *
 * Grounding content for the lesson comes from the uploaded material's
 * topicSummary and extractedPreview, which are always populated on upload.
 */
@RestController
@RequestMapping("/api/ai")
public class AiLessonController {

    @Autowired private FirstQuizResultRepository firstQuizResultRepository;
    @Autowired private LessonCacheRepository lessonCacheRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private ClaudeService claudeService;

    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/lesson")
    public Map<String, Object> getLesson(@RequestParam String topic, HttpSession session) {
        String studentId = currentEmail(session);

        Optional<FirstQuizResult> resultOpt =
                firstQuizResultRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);

        // No quiz attempt yet for this topic — frontend renders a
        // "take a quiz first" placeholder state for this exact shape.
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

        // Adapted quiz result always wins over the original general score —
        // matches the rule already documented on FirstQuizResult/LessonCache:
        // completing an Adapted Quiz upgrades (or downgrades) the lesson tier.
        boolean hasAdapted = result.getLatestAdaptedScore() != null && result.getLatestAdaptedTier() != null;
        String tier = hasAdapted ? result.getLatestAdaptedTier() : result.getGeneralDifficulty();
        double score = hasAdapted ? result.getLatestAdaptedScore() : result.getGeneralScore();

        if (tier == null || tier.isBlank()) tier = "Easy";

        // ── Cache check ──────────────────────────────────────────────────
        Optional<LessonCache> cached = lessonCacheRepository
                .findByStudentIdAndTopicIgnoreCaseAndTierIgnoreCase(studentId, topic, tier);
        if (cached.isPresent()) {
            Map<String, Object> response = parseLessonJson(cached.get().getContentJson(), topic, tier, score);
            if (response != null) return response;
            // Fall through to regenerate if the cached JSON is somehow corrupt.
        }
        Long existingCacheId = cached.map(LessonCache::getId).orElse(null);

        // ── Cache miss — build grounding context and call Claude ─────────
        String knowledgeCtx = buildKnowledgeContext(studentId, topic);
        String raw = claudeService.generateLessonContent(topic, knowledgeCtx, score, tier);

        Map<String, Object> response = parseLessonJson(raw, topic, tier, score);
        if (response == null) {
            // Claude call failed or returned unparsable content — surface a
            // clear, honest state instead of a silent generic fallback.
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

        // Persist to cache so the next open (or a same-tier revisit) doesn't
        // call Claude again — same one-row-per-(student,topic,tier) shape
        // LessonCache already enforces via its unique constraint. If a row
        // already existed for this (student, topic, tier) — e.g. its JSON
        // was corrupt and we just regenerated — reuse its id so this is an
        // UPDATE, not a second INSERT that would violate that constraint.
        try {
            LessonCache toSave = new LessonCache(studentId, topic, tier, score, raw);
            if (existingCacheId != null) toSave.setId(existingCacheId);
            lessonCacheRepository.save(toSave);
        } catch (Exception e) {
            // Non-fatal — worst case this tier gets regenerated next time.
            System.err.println("Could not cache lesson content: " + e.getMessage());
        }

        return response;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String currentEmail(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        return email == null || email.isBlank() ? "demo" : email;
    }

    /**
     * Builds the grounding text passed to ClaudeService.generateLessonContent,
     * drawn from the uploaded material's topicSummary + extractedPreview —
     * which are always populated on upload — so the lesson is genuinely
     * grounded in the student's actual handout.
     */
    private String buildKnowledgeContext(String studentId, String topic) {
        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId);
        Material material = materials.stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);

        if (material == null) return null; // ClaudeService handles null/blank gracefully

        StringBuilder ctx = new StringBuilder();
        if (material.getTopicSummary() != null && !material.getTopicSummary().isBlank()) {
            ctx.append(material.getTopicSummary()).append("\n\n");
        }
        if (material.getExtractedPreview() != null && !material.getExtractedPreview().isBlank()) {
            ctx.append(material.getExtractedPreview());
        }
        return ctx.length() == 0 ? null : ctx.toString();
    }

    /**
     * Parses ClaudeService.generateLessonContent's raw JSON string output
     * (or a previously cached copy of it) into the response map shape the
     * frontend's renderLesson() expects, adding the request-scoped topic/
     * tier/score/noAttemptYet fields that aren't part of Claude's own output.
     * Returns null if parsing fails, so the caller can decide how to recover.
     */
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