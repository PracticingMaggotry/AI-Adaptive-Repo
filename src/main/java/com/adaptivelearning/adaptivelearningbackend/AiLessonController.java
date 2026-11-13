package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.adaptivelearning.adaptivelearningbackend.ai.CostModel;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt;
import com.adaptivelearning.adaptivelearningbackend.ai.LessonPlanner;
import com.adaptivelearning.adaptivelearningbackend.ai.LessonVerifier;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;
import com.adaptivelearning.adaptivelearningbackend.ai.PassageRetriever;
import com.adaptivelearning.adaptivelearningbackend.ai.SingleFlight;
import com.adaptivelearning.adaptivelearningbackend.ai.TextSimilarity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

/**
 * Serves the Learning Hub lesson. A lesson is cached per (handout, tier, learner-state fingerprint): it is reused
 * until the student's open weaknesses / slips / recovered concepts change, then regenerated. Identical concurrent
 * requests share one generation.
 *
 * <p>In the default "units" mode a long handout is taught part by part. {@link LessonPlanner} picks the next few
 * concepts (weakest first, every concept gets a turn before any repeats), the model writes one unit per concept
 * from the handout passages, {@link LessonVerifier} checks each unit against those passages, and the student
 * answers a check question before the model answer is revealed. "classic" mode (config LESSON_MODE) keeps the
 * original single-summary lesson for comparison.
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
    @Autowired private FileStorageService fileStorageService;
    @Autowired private MaterialContentService materialContentService;
    @Autowired private LessonCoverageRepository lessonCoverageRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SingleFlight<String, Map<String, Object>> lessonFlight = new SingleFlight<>();

    /** Everything a lesson request needs to know about the student on one handout. */
    private record Ctx(Material material, StudyScope scope, String tier, double score,
                       LearnerModelService.Model model, boolean hasConcepts, LearnerPrompt.Rendered rendered,
                       String legacyMistakes, String stateHash) {}

    /** Null when the student has no handout or no first quiz on it yet. */
    private Ctx loadCtx(String studentId, String topic) {
        // Resolve the handout first: this closes out a stale baseline left over from an older handout of the same name.
        Material material = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId).stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);
        StudyScope scope = material == null ? null : scopeService.ensure(studentId, topic, material, 0);

        Optional<FirstQuizResult> resultOpt = scope == null
                ? Optional.empty()
                : firstQuizResultRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);
        if (resultOpt.isEmpty()) return null;

        FirstQuizResult result = resultOpt.get();

        boolean hasAdapted = result.getLatestAdaptedScore() != null && result.getLatestAdaptedTier() != null;
        String tier = hasAdapted ? result.getLatestAdaptedTier() : result.getGeneralDifficulty();
        double score = hasAdapted ? result.getLatestAdaptedScore() : result.getGeneralScore();

        String played = DifficultyTier.normalize(tier);
        tier = played != null ? played : "Easy";

        LearnerModelService.Model model = learnerModelService.build(scope);
        boolean hasConcepts = !model.concepts().isEmpty();
        // The lesson is pitched at the label of the student's current ability, like the next quiz. A handout with no
        // concept list (extraction failed) keeps the tier the student last played.
        if (hasConcepts) tier = model.tier();
        LearnerPrompt.Rendered rendered = hasConcepts
                ? learnerModelService.prompt(model, AiPolicy.LESSON_MAX_CONCEPT_ENTRIES, Instant.now()) : null;
        String legacyMistakes = hasConcepts ? null : buildMistakeContext(studentId, topic, scope.getId());
        String stateHash = hasConcepts ? rendered.stateHash()
                : "legacy-" + LearnerPrompt.sha1(legacyMistakes == null ? "" : legacyMistakes).substring(0, 16);
        return new Ctx(material, scope, tier, score, model, hasConcepts, rendered, legacyMistakes, stateHash);
    }

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

        Ctx c = loadCtx(studentId, topic);
        if (c == null) {
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

        if (c.hasConcepts() && configurationService.isUnitLessonMode()) {
            return unitLesson(studentId, topic, c);
        }

        StudyScope scope = c.scope();
        String tier = c.tier();
        double score = c.score();
        String stateHash = c.stateHash();
        LearnerModelService.Model model = c.model();
        LearnerPrompt.Rendered rendered = c.rendered();
        String legacyMistakes = c.legacyMistakes();

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
            return failureResponse(topic, finalTier, finalScore, "We couldn't generate a lesson right now. Please try again in a moment.");
        }
    }

    // UNITS MODE: part-by-part lesson written from handout passages

    /** The lesson part a student is on right now: which concepts, and the keys that identify it. */
    private record Part(LessonPlanner.Plan plan, String token, String cacheHash) {}

    private Part partFor(Ctx c) {
        Map<String, Integer> taught = new HashMap<>();
        for (LessonCoverage row : lessonCoverageRepository.findByScopeId(c.scope().getId())) {
            taught.put(row.getConceptKey(), row.getTimesTaught());
        }
        LessonPlanner.Plan plan = LessonPlanner.next(c.model().states().values(), taught, c.tier(), Instant.now());
        List<String> sorted = new ArrayList<>(plan.keys());
        Collections.sort(sorted);
        StringBuilder counts = new StringBuilder();
        for (String k : sorted) counts.append(taught.getOrDefault(k, 0)).append(',');
        String keys = String.join(",", sorted);
        String token = LearnerPrompt.sha1(keys + "|" + counts).substring(0, 16);
        String cacheHash = LearnerPrompt.sha1(c.stateHash() + "|" + keys).substring(0, 16);
        return new Part(plan, token, cacheHash);
    }

    private Map<String, Object> unitLesson(String studentId, String topic, Ctx c) {
        Part part = partFor(c);
        if (part.plan().keys().isEmpty()) {
            return unitsResponse(null, topic, c, part);
        }

        Optional<LessonSnapshot> cached = lessonSnapshotRepository
                .findFirstByScopeIdAndTierAndStateHashAndPromptVersionOrderByIdDesc(
                        c.scope().getId(), c.tier(), part.cacheHash(), AiPolicy.PROMPT_VERSION);
        if (cached.isPresent()) {
            Map<String, Object> response = unitsResponse(cached.get().getContentJson(), topic, c, part);
            if (response != null) return response;
        }

        final String flightKey = c.scope().getId() + "|" + c.tier() + "|" + part.cacheHash();
        try {
            return lessonFlight.run(flightKey, AiPolicy.SCHED_MAX_WAIT_MS + 30_000L, () -> {
                try (AiCallContext.Handle ignored = AiCallContext.open(studentId)) {
                    return generateUnitLesson(studentId, topic, c, part);
                }
            });
        } catch (Exception e) {
            return failureResponse(topic, c.tier(), c.score(), "We couldn't generate a lesson right now. Please try again in a moment.");
        }
    }

    private Map<String, Object> generateUnitLesson(String studentId, String topic, Ctx c, Part part) {
        String text = readMaterialText(c.material());
        PassageRetriever retriever = new PassageRetriever(text);
        List<PassageRetriever.Passage> all = retriever.passages();
        if (text.isBlank() || all.isEmpty()) {
            // Scanned or unreadable file: nothing to quote from, so use the original summary lesson.
            LearnerModelService.Model model = c.model();
            String learnerText = c.rendered() != null && model.responseCount() > 0 ? c.rendered().text() : null;
            return generateLesson(studentId, topic, c.scope(), c.tier(), c.score(), c.stateHash(), learnerText);
        }

        int limit = dailyActionLimiter.getEffectiveLimit("lesson-regeneration", studentId);
        if (!dailyActionLimiter.tryConsume("lesson-regeneration", studentId, limit)) {
            return failureResponse(topic, c.tier(), c.score(),
                    "You've reached today's limit of " + limit + " new lesson generations. Please try again tomorrow.");
        }

        Map<String, ScopeService.ConceptDef> byKey = c.model().byKey();
        List<ClaudeService.LessonUnitSpec> specs = new ArrayList<>();
        List<PassageRetriever.Concept> wanted = new ArrayList<>();
        for (String key : part.plan().keys()) {
            ScopeService.ConceptDef d = byKey.get(key);
            if (d == null) continue;
            ConceptState st = c.model().states().get(key);
            List<LearnerPrompt.Example> ex = c.model().examples().get(key);
            boolean open = st != null && (st.state == State.WEAK || (st.state == State.SHAKY && st.misses > 0));
            boolean refute = open && ex != null && !ex.isEmpty();
            String role = open ? "OPEN WEAKNESS"
                    : st != null && st.state == State.SLIP ? "ONE-OFF SLIP"
                    : st != null && st.state == State.LEARNED ? "REFRESHER" : "NEW";
            String mistake = "";
            if (refute) {
                LearnerPrompt.Example e = ex.get(0);
                mistake = "Q: " + clip(e.question()) + " | student answered: " + clip(e.studentAnswer())
                        + " | correct: " + clip(e.correctAnswer());
            }
            specs.add(new ClaudeService.LessonUnitSpec(key, d.term(), d.definition(), role, refute, mistake));
            wanted.add(new PassageRetriever.Concept(key, d.term(), d.definition()));
        }

        // Passages: the whole handout when it is short, otherwise the best ones for these concepts. Each is tagged [P#].
        Set<Integer> chosen = new TreeSet<>();
        Map<String, List<Integer>> byConcept = new HashMap<>();
        if (PassageRetriever.wordCount(text) <= AiPolicy.FULL_TEXT_WORD_LIMIT) {
            for (PassageRetriever.Passage p : all) chosen.add(p.id());
        } else {
            PassageRetriever.Result r = retriever.retrieve(wanted, text, AiPolicy.FULL_TEXT_WORD_LIMIT,
                    AiPolicy.PASSAGES_PER_CONCEPT, AiPolicy.PASSAGE_WORD_BUDGET);
            byConcept.putAll(r.passagesByConcept());
            for (List<Integer> ids : byConcept.values()) chosen.addAll(ids);
        }
        // Every concept must have at least one passage to cite, even when retrieval found nothing for it.
        Map<String, Integer> homePassage = new HashMap<>();
        for (ClaudeService.LessonUnitSpec sp : specs) {
            List<Integer> ids = byConcept.get(sp.conceptKey());
            int home = ids != null && !ids.isEmpty() ? ids.get(0) : bestPassage(all, sp.term(), sp.definition());
            homePassage.put(sp.conceptKey(), home);
            chosen.add(home);
        }
        Map<String, String> passages = new LinkedHashMap<>();
        StringBuilder tagged = new StringBuilder();
        for (int id : chosen) {
            String pid = "P" + (id + 1);
            passages.put(pid, all.get(id).text());
            tagged.append('[').append(pid).append("] ").append(all.get(id).text()).append("\n\n");
        }

        // Write, verify, retry the failures once. A unit whose explanation and example are grounded in the handout but
        // whose check question is not keeps its generated content and simply has no question (withoutQuestion).
        // Only a unit whose content fails the check still shows the handout passage itself.
        Map<String, LessonVerifier.Unit> good = new LinkedHashMap<>();
        Map<String, LessonVerifier.Unit> withoutQuestion = new LinkedHashMap<>();
        List<ClaudeService.LessonUnitSpec> todo = new ArrayList<>(specs);
        for (int attempt = 0; attempt < 2 && !todo.isEmpty(); attempt++) {
            String raw = claudeService.generateLessonUnits(topic, tagged.toString().trim(), c.tier(), todo);
            Map<String, ClaudeService.LessonUnitSpec> todoByKey = new HashMap<>();
            for (ClaudeService.LessonUnitSpec sp : todo) todoByKey.put(sp.conceptKey(), sp);
            for (LessonVerifier.Unit u : parseUnits(raw)) {
                ClaudeService.LessonUnitSpec sp = todoByKey.get(u.conceptKey());
                if (sp == null || good.containsKey(u.conceptKey())) continue;
                LessonVerifier.Result res = LessonVerifier.check(u, passages, sp.term(), sp.refute());
                if (res.ok()) {
                    good.put(u.conceptKey(), res.unit());
                    withoutQuestion.remove(u.conceptKey());
                } else if (res.withoutQuestion() != null) {
                    withoutQuestion.put(u.conceptKey(), res.withoutQuestion());
                }
            }
            todo.removeIf(sp -> good.containsKey(sp.conceptKey()));
        }
        if (good.isEmpty() && withoutQuestion.isEmpty()) {
            // Nothing usable came back (outage, or every unit failed the check): do not cache an all-fallback lesson.
            dailyActionLimiter.refund("lesson-regeneration", studentId);
            return failureResponse(topic, c.tier(), c.score(), "We couldn't generate a lesson right now. Please try again in a moment.");
        }

        List<Map<String, Object>> stored = new ArrayList<>();
        for (ClaudeService.LessonUnitSpec sp : specs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conceptKey", sp.conceptKey());
            m.put("term", sp.term());
            m.put("role", sp.role());
            LessonVerifier.Unit u = good.get(sp.conceptKey());
            if (u == null) u = withoutQuestion.get(sp.conceptKey()); // question and answer are "" here
            if (u != null) {
                String src = passages.get(LessonVerifier.normalizeId(u.source()));
                m.put("fallback", false);
                m.put("explain", u.explain());
                m.put("example", u.example() == null ? "" : u.example());
                m.put("refutation", u.refutation() == null ? "" : u.refutation());
                m.put("question", u.question());
                m.put("answer", u.answer());
                m.put("sourceText", shorten(src, AiPolicy.LESSON_SOURCE_SHOW_CHARS));
            } else {
                m.put("fallback", true);
                m.put("sourceText", shorten(all.get(homePassage.get(sp.conceptKey())).text(), AiPolicy.LESSON_SOURCE_SHOW_CHARS));
            }
            stored.add(m);
        }
        String json;
        try {
            json = mapper.writeValueAsString(Map.of("units", stored));
        } catch (Exception e) {
            dailyActionLimiter.refund("lesson-regeneration", studentId);
            return failureResponse(topic, c.tier(), c.score(), "We couldn't generate a lesson right now. Please try again in a moment.");
        }
        try {
            lessonSnapshotRepository.save(new LessonSnapshot(c.scope().getId(), studentId, topic, c.tier(),
                    part.cacheHash(), AiPolicy.PROMPT_VERSION, c.score(), json));
        } catch (Exception e) {
            System.err.println("Could not store lesson snapshot: " + e.getMessage());
        }
        return unitsResponse(json, topic, c, part);
    }

    /** Builds the response the page renders. Check answers and model answers stay on the server until the student attempts. */
    private Map<String, Object> unitsResponse(String storedJson, String topic, Ctx c, Part part) {
        List<Map<String, Object>> units = new ArrayList<>();
        if (storedJson != null) {
            try {
                for (JsonNode n : mapper.readTree(storedJson).path("units")) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("conceptKey", n.path("conceptKey").asText(""));
                    u.put("term", n.path("term").asText(""));
                    u.put("role", n.path("role").asText("NEW"));
                    boolean fallback = n.path("fallback").asBoolean(false);
                    u.put("fallback", fallback);
                    if (fallback) {
                        u.put("sourceText", n.path("sourceText").asText(""));
                    } else {
                        u.put("explain", n.path("explain").asText(""));
                        u.put("example", n.path("example").asText(""));
                        u.put("refutation", n.path("refutation").asText(""));
                        u.put("question", n.path("question").asText(""));
                    }
                    units.add(u);
                }
            } catch (Exception e) {
                System.err.println("Could not parse stored lesson units: " + e.getMessage());
                return null;
            }
        }
        LessonPlanner.Plan p = part.plan();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("part", p.partNumber());
        plan.put("parts", p.partsInRound());
        plan.put("partSize", p.partSize());
        plan.put("weakTotal", p.weakTotal());
        plan.put("weakLeft", p.weakLeftInRound());
        plan.put("token", part.token());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("topic", topic);
        response.put("tier", c.tier());
        response.put("score", c.score());
        response.put("noAttemptYet", false);
        response.put("mode", "units");
        response.put("allDone", p.keys().isEmpty());
        response.put("plan", plan);
        response.put("units", units);
        return response;
    }

    @PostMapping("/lesson/check")
    public Map<String, Object> checkAnswer(@RequestBody Map<String, String> body, HttpSession session, HttpServletResponse httpResponse) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return Map.of("success", false, "message", "Please log in first.");
        }
        String attempt = body.get("attempt") == null ? "" : body.get("attempt").trim();
        if (attempt.length() < 3) return Map.of("success", false, "message", "Write your answer first, then check it.");

        Ctx c = loadCtx(studentId, body.getOrDefault("topic", ""));
        if (c == null || !c.hasConcepts() || !configurationService.isUnitLessonMode()) {
            return Map.of("success", false, "message", "This lesson is not available. Reopen it from the Learning Hub.");
        }
        Part part = partFor(c);
        Optional<LessonSnapshot> snap = lessonSnapshotRepository
                .findFirstByScopeIdAndTierAndStateHashAndPromptVersionOrderByIdDesc(
                        c.scope().getId(), c.tier(), part.cacheHash(), AiPolicy.PROMPT_VERSION);
        if (snap.isEmpty()) return Map.of("success", false, "message", "This lesson part is out of date. Reopen the lesson.");
        try {
            String key = body.getOrDefault("conceptKey", "");
            for (JsonNode n : mapper.readTree(snap.get().getContentJson()).path("units")) {
                if (!key.equals(n.path("conceptKey").asText())) continue;
                if (n.path("fallback").asBoolean(false) || n.path("answer").asText("").isBlank()) break;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", true);
                out.put("answer", n.path("answer").asText(""));
                out.put("sourceText", n.path("sourceText").asText(""));
                return out;
            }
        } catch (Exception e) {
            System.err.println("Could not read stored lesson units: " + e.getMessage());
        }
        return Map.of("success", false, "message", "That question is no longer available.");
    }

    /**
     * The student finished this part: every concept in it counts as taught once, so the next part moves on.
     * The token is the one the page was served; if the part has already been advanced it no longer matches and
     * nothing is counted twice.
     */
    @PostMapping("/lesson/advance")
    public Map<String, Object> advance(@RequestBody Map<String, String> body, HttpSession session, HttpServletResponse httpResponse) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return Map.of("success", false, "message", "Please log in first.");
        }
        Ctx c = loadCtx(studentId, body.getOrDefault("topic", ""));
        if (c == null || !c.hasConcepts() || !configurationService.isUnitLessonMode()) {
            return Map.of("success", false, "message", "This lesson is not available. Reopen it from the Learning Hub.");
        }
        Part part = partFor(c);
        if (!part.token().equals(body.get("token"))) {
            return Map.of("success", true, "advanced", false);
        }
        try {
            Map<String, LessonCoverage> rows = new HashMap<>();
            for (LessonCoverage row : lessonCoverageRepository.findByScopeId(c.scope().getId())) rows.put(row.getConceptKey(), row);
            List<LessonCoverage> touched = new ArrayList<>();
            for (String key : part.plan().keys()) {
                LessonCoverage row = rows.computeIfAbsent(key, k -> new LessonCoverage(c.scope().getId(), k));
                row.markTaught();
                touched.add(row);
            }
            lessonCoverageRepository.saveAll(touched);
        } catch (Exception e) {
            System.err.println("Could not record lesson progress: " + e.getMessage());
            return Map.of("success", false, "message", "Could not save your progress. Please try again.");
        }
        return Map.of("success", true, "advanced", true);
    }

    private List<LessonVerifier.Unit> parseUnits(String raw) {
        List<LessonVerifier.Unit> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        try {
            JsonNode root = mapper.readTree(ClaudeService.stripJsonFence(raw));
            JsonNode arr = root.isArray() ? root : root.path("units");
            for (JsonNode n : arr) {
                out.add(new LessonVerifier.Unit(n.path("conceptKey").asText(""), n.path("source").asText(""),
                        n.path("explain").asText(""), n.path("example").asText(""), n.path("refutation").asText(""),
                        n.path("question").asText(""), n.path("answer").asText("")));
            }
        } catch (Exception e) {
            System.err.println("Could not parse lesson units JSON: " + e.getMessage());
        }
        return out;
    }

    /** Passage that shares the most content words with the concept's name and definition (first passage if none). */
    private static int bestPassage(List<PassageRetriever.Passage> all, String term, String definition) {
        Set<String> want = TextSimilarity.tokens(term + " " + (definition == null ? "" : definition));
        int best = 0, bestHits = -1;
        for (PassageRetriever.Passage p : all) {
            Set<String> have = TextSimilarity.tokens(p.text());
            int hits = 0;
            for (String w : want) if (have.contains(w)) hits++;
            if (hits > bestHits) { bestHits = hits; best = p.id(); }
        }
        return best;
    }

    private String readMaterialText(Material material) {
        return materialContentService.textFor(material);
    }

    private static String shorten(String v, int max) {
        if (v == null) return "";
        String flat = v.replaceAll("\\s+", " ").trim();
        return flat.length() > max ? flat.substring(0, max).trim() + "..." : flat;
    }

    private Map<String, Object> failureResponse(String topic, String tier, double score, String message) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("topic", topic);
        failure.put("tier", tier);
        failure.put("score", score);
        failure.put("noAttemptYet", false);
        failure.put("intro", message);
        failure.put("concepts", List.of());
        failure.put("tips", List.of());
        failure.put("studyPlan", List.of());
        return failure;
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