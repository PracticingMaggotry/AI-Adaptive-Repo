package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.ConceptInsights;
import com.adaptivelearning.adaptivelearningbackend.ai.ConceptInsights.Counts;
import com.adaptivelearning.adaptivelearningbackend.ai.ConceptInsights.Item;
import com.adaptivelearning.adaptivelearningbackend.ai.ConceptInsights.View;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.*;

/**
 * Concept progress for the student's own pages (dashboard, quiz hub, quiz result, learning hub).
 *
 * <p>Read-only and student-scoped: it only ever reads the caller's own scopes and responses, never calls the AI and
 * never writes anything except opening the study scope for a handout that pre-dates scopes (the same idempotent step
 * the lesson endpoint already takes). The admin screens do not use it. The translation from the internal mastery
 * model to plain language lives in {@link ConceptInsights}; this class only loads data and shapes JSON.
 */
@RestController
@RequestMapping("/api/concepts")
public class ConceptInsightsController {

    @Autowired private ScopeService scopeService;
    @Autowired private LearnerModelService learnerModelService;
    @Autowired private StudyScopeRepository studyScopeRepository;
    @Autowired private MaterialRepository materialRepository;

    /** One handout: summary counts, the few concepts to focus on, and every concept grouped by status. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> forTopic(@RequestParam String topic, HttpSession session) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) return unauthorized();
        if (topic == null || topic.isBlank() || topic.length() > TopicNames.MAX_LOOKUP_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "A valid topic name is required."));
        }

        StudyScope scope = scopeService.active(studentId, topic).orElse(null);
        if (scope == null) {
            // A handout from before scopes existed: adopt it once, exactly as the lesson endpoint does.
            try {
                Material m = materialRepository.findByUploadedByAndTopicIgnoreCase(studentId, topic).stream()
                        .max(Comparator.comparing(Material::getUploadedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                        .orElse(null);
                if (m != null) scope = scopeService.ensure(studentId, topic, m, 0);
            } catch (Exception e) {
                System.err.println("Could not open study scope for concept view (non-fatal): " + e.getMessage());
            }
        }
        if (scope == null) return ResponseEntity.ok(empty(topic));

        LearnerModelService.Model model = learnerModelService.build(scope);
        View view = viewOf(model);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("topic", scope.getTopic());
        out.put("hasConcepts", !model.concepts().isEmpty());
        out.put("hasActivity", model.responseCount() > 0);
        // Next Bloom level and ELO on this handout (null when nothing is answered yet).
        out.put("adaptiveLevel", learnerModelService.levelView(model));
        out.put("counts", countsMap(view.counts()));
        out.put("focus", itemMaps(view.focus()));
        out.put("items", itemMaps(view.items()));
        return ResponseEntity.ok(out);
    }

    /**
     * Every active handout in one call, for surfaces that only need a glance: per-handout counts, plus the few most
     * urgent concepts across all handouts. Concept lists are deliberately not included.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(HttpSession session) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) return unauthorized();

        List<StudyScope> scopes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StudyScope s : studyScopeRepository.findByStudentIdOrderByCreatedAtDesc(studentId)) {
            if (StudyScope.ACTIVE.equals(s.getStatus()) && seen.add(s.getTopicKey())) scopes.add(s);
        }

        List<Map<String, Object>> topics = new ArrayList<>();
        List<Map.Entry<String, Item>> candidates = new ArrayList<>();
        int total = 0, needs = 0, almost = 0, got = 0, tried = 0, fixed = 0, review = 0;
        boolean anyActivity = false;

        for (StudyScope scope : scopes) {
            try {
                LearnerModelService.Model model = learnerModelService.build(scope);
                if (model.concepts().isEmpty()) continue;
                View view = viewOf(model);
                Counts c = view.counts();
                boolean active = model.responseCount() > 0;
                anyActivity |= active;
                total += c.total(); needs += c.needsPractice(); almost += c.almostThere(); got += c.gotIt();
                tried += c.notTried(); fixed += c.fixed(); review += c.reviewSoon();

                Map<String, Object> t = new LinkedHashMap<>();
                t.put("topic", scope.getTopic());
                t.put("hasActivity", active);
                t.put("counts", countsMap(c));
                List<Map<String, Object>> topicFocus = new ArrayList<>();
                for (Item it : view.focus()) topicFocus.add(focusMap(scope.getTopic(), it));
                t.put("focus", topicFocus);
                topics.add(t);
                for (Item it : view.focus()) candidates.add(Map.entry(scope.getTopic(), it));
            } catch (Exception e) {
                System.err.println("Concept summary skipped one handout (non-fatal): " + e.getMessage());
            }
        }

        candidates.sort((a, b) -> Double.compare(b.getValue().priority(), a.getValue().priority()));
        List<Map<String, Object>> focus = new ArrayList<>();
        for (Map.Entry<String, Item> e : candidates) {
            if (focus.size() >= ConceptInsights.FOCUS_MAX) break;
            focus.add(focusMap(e.getKey(), e.getValue()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("hasActivity", anyActivity);
        out.put("totals", countsMap(new Counts(total, needs, almost, got, tried, fixed, review)));
        out.put("topics", topics);
        out.put("focus", focus);
        return ResponseEntity.ok(out);
    }


    private static Map<String, Object> focusMap(String topic, Item it) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("topic", topic);
        f.put("key", it.key());
        f.put("term", it.term());
        f.put("evidence", it.evidence());
        return f;
    }

    private static View viewOf(LearnerModelService.Model model) {
        List<LearnerPrompt.ConceptInfo> info = new ArrayList<>();
        for (ScopeService.ConceptDef c : model.concepts()) {
            info.add(new LearnerPrompt.ConceptInfo(c.key(), c.term(), c.definition()));
        }
        return ConceptInsights.build(info, model.states(), model.examples(), Instant.now());
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
    }

    private static Map<String, Object> empty(String topic) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("topic", topic);
        out.put("hasConcepts", false);
        out.put("hasActivity", false);
        out.put("counts", countsMap(new Counts(0, 0, 0, 0, 0, 0, 0)));
        out.put("focus", List.of());
        out.put("items", List.of());
        return out;
    }

    private static Map<String, Object> countsMap(Counts c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", c.total());
        m.put("needsPractice", c.needsPractice());
        m.put("almostThere", c.almostThere());
        m.put("gotIt", c.gotIt());
        m.put("notTried", c.notTried());
        m.put("fixed", c.fixed());
        m.put("reviewSoon", c.reviewSoon());
        return m;
    }

    private static List<Map<String, Object>> itemMaps(List<Item> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (Item it : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", it.key());
            m.put("term", it.term());
            m.put("definition", it.definition());
            m.put("status", it.status().name().toLowerCase(Locale.ROOT));
            m.put("fixed", it.fixed());
            m.put("reviewSoon", it.reviewSoon());
            m.put("evidence", it.evidence());
            if (it.lastMistake() != null) {
                Map<String, Object> mk = new LinkedHashMap<>();
                mk.put("question", it.lastMistake().question());
                mk.put("yourAnswer", it.lastMistake().yourAnswer());
                mk.put("correctAnswer", it.lastMistake().correctAnswer());
                m.put("lastMistake", mk);
            }
            out.add(m);
        }
        return out;
    }
}
