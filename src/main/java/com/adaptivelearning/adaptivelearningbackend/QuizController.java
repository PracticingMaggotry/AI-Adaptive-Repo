package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import java.io.InputStream;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Optional;

@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    @Autowired private QuestionRepository questionRepository;
    @Autowired private AttemptRepository attemptRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private ClaudeService claudeService;
    @Autowired private MaterialController materialController;
    @Autowired private FirstQuizResultRepository firstQuizResultRepository;
    @Autowired private LessonCacheRepository lessonCacheRepository;
    @Autowired private QuestionPerformanceRepository questionPerformanceRepository;
    @Autowired private DailyActionLimiter dailyActionLimiter;
    @Autowired private FileStorageService fileStorageService;

    // Shared instance — ObjectMapper is thread-safe and expensive to construct.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Daily per-student caps on AI-backed actions.
    private static final int MAX_ADAPTED_QUIZZES_PER_DAY = 6;
    private static final int MAX_TARGETED_QUIZZES_PER_DAY = 15;

    // submitQuiz() calls Claude for essay grading + categorization; cap bounds Anthropic spend.
    private static final int MAX_QUIZ_SUBMISSIONS_PER_DAY = 40;

    // ── Get questions ──

    @GetMapping("/questions")
    public ResponseEntity<?> getQuestions(
            @RequestParam String topic,
            @RequestParam String difficulty,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in to take a quiz."));

        List<Question> questions = questionRepository.findByOwnerAndTopicAndDifficultyIgnoreCase(studentId, topic, difficulty);
        if (questions.isEmpty())
            questions = questionRepository.findByOwnerAndTopicAndDifficultyIgnoreCase(studentId, topic, "Easy");

        return ResponseEntity.ok(questions.stream().map(this::questionToMap).toList());
    }

    // ── Latest attempt (for quizfinish.html fallback) ──

    /** Most recent quiz attempt, with per-question breakdown if saved. */
    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> getLatestAttempt(HttpSession session) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));

        List<Attempt> attempts = attemptRepository.findByStudentIdOrderByTimestampDesc(studentId);
        if (attempts.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false));
        }

        Attempt latest = attempts.get(0);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("topic", latest.getTopic());
        response.put("difficulty", latest.getDifficulty());
        response.put("totalItems", latest.getTotalItems());
        response.put("correctAnswers", latest.getCorrectAnswers());
        response.put("score", latest.getPerformanceScore());
        response.put("nextDiff", latest.getNextDiff());

        Object questionResults = new ArrayList<>();
        if (latest.getDetails() != null && !latest.getDetails().isBlank()) {
            try {
                questionResults = MAPPER.readValue(latest.getDetails(), List.class);
            } catch (Exception e) {
                System.err.println("Could not parse stored attempt details: " + e.getMessage());
            }
        }
        response.put("questionResults", questionResults);
        return ResponseEntity.ok(response);
    }

    // ── All-time topic performance — aggregates every attempt on this topic into categoryBreakdown
    // and typeBreakdown for quizfinish.html's charts. Sourced from Attempt.details (has both type
    // and weaknessCategory) rather than QuestionPerformance (lacks type). ──
    @GetMapping("/topic-performance")
    public ResponseEntity<Map<String, Object>> topicPerformance(
            @RequestParam String topic,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));

        List<Attempt> attempts = attemptRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);

        String[] categories = {"Terminology", "Computation", "Application", "Analysis", "Process Steps"};
        Map<String, Integer> catCounts = new LinkedHashMap<>();
        Map<String, Double> catCredit = new LinkedHashMap<>();
        for (String c : categories) { catCounts.put(c, 0); catCredit.put(c, 0.0); }

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, Double> typeCredit = new LinkedHashMap<>();

        int totalQuestions = 0;

        for (Attempt attempt : attempts) {
            if (attempt.getDetails() == null || attempt.getDetails().isBlank()) continue;
            try {
                JsonNode results = MAPPER.readTree(attempt.getDetails());
                if (!results.isArray()) continue;

                for (JsonNode q : results) {
                    totalQuestions++;

                    String type = q.path("type").asText("mcq").toLowerCase(Locale.ROOT);

                    String cat = q.path("weaknessCategory").asText("Analysis");
                    if (!catCounts.containsKey(cat)) cat = "Analysis";

                    // Essays carry a 0-100 AI score. Other types carry a 0.0-1.0 creditFraction; older
                    // attempts without it fall back to binary correct=1.0/else=0.0.
                    Double essayScore = (q.has("essayScore") && !q.path("essayScore").isNull())
                            ? q.path("essayScore").asDouble() : null;
                    double credit;
                    if (essayScore != null) {
                        credit = Math.max(0, Math.min(100, essayScore)) / 100.0;
                    } else if (q.has("creditFraction") && !q.path("creditFraction").isNull()) {
                        credit = Math.max(0, Math.min(1, q.path("creditFraction").asDouble()));
                    } else {
                        credit = "correct".equalsIgnoreCase(q.path("result").asText("wrong")) ? 1.0 : 0.0;
                    }

                    catCounts.merge(cat, 1, Integer::sum);
                    catCredit.put(cat, catCredit.get(cat) + credit);

                    typeCounts.merge(type, 1, Integer::sum);
                    typeCredit.put(type, typeCredit.getOrDefault(type, 0.0) + credit);
                }
            } catch (Exception e) {
                System.err.println("Could not parse attempt details for topic-performance: " + e.getMessage());
            }
        }

        // "Not tested" is count == 0; callers should check count, not scorePct, to distinguish that from a real 0%.
        List<Map<String, Object>> categoryBreakdown = new ArrayList<>();
        for (String c : categories) {
            int n = catCounts.get(c);
            int scorePct = n > 0 ? (int) Math.round((catCredit.get(c) / n) * 100) : 0;
            Map<String, Object> catMap = new LinkedHashMap<>();
            catMap.put("category", c);
            catMap.put("count", n);
            catMap.put("scorePct", scorePct);
            categoryBreakdown.add(catMap);
        }

        List<Map<String, Object>> typeBreakdown = new ArrayList<>();
        for (String type : typeCounts.keySet()) {
            int n = typeCounts.get(type);
            int scorePct = n > 0 ? (int) Math.round((typeCredit.get(type) / n) * 100) : 0;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", type);
            t.put("count", n);
            t.put("scorePct", scorePct);
            // Always 0 today (everything is graded synchronously); kept as a real field for future manual grading.
            t.put("pending", 0);
            typeBreakdown.add(t);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("topic", topic);
        response.put("totalAttempts", attempts.size());
        response.put("totalQuestions", totalQuestions);
        response.put("categoryBreakdown", categoryBreakdown);
        response.put("typeBreakdown", typeBreakdown);
        return ResponseEntity.ok(response);
    }

    // ── Submit quiz ──

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submitQuiz(@RequestBody QuizSubmission submission, HttpSession session) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in to submit a quiz."));

        // Checked before any scoring/Claude calls so an exhausted student never burns work on a rejected request.
        if (!dailyActionLimiter.tryConsume("quiz-submit", studentId, MAX_QUIZ_SUBMISSIONS_PER_DAY)) {
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "message", "Daily quiz submission limit reached (" + MAX_QUIZ_SUBMISSIONS_PER_DAY + " per day). Please try again tomorrow."));
        }

        int correctCount = 0;
        int totalItems = submission.answers == null ? 0 : submission.answers.size();

        // ── Batch-load all submitted questions in one query (replaces per-question findById calls) ──
        Map<Long, Question> questionMap = new LinkedHashMap<>();
        if (submission.answers != null) {
            List<Long> ids = submission.answers.stream()
                    .filter(a -> a.questionId != null)
                    .map(a -> a.questionId)
                    .toList();
            if (!ids.isEmpty()) {
                // Ownership check (question IDs are guessable) — fails closed on a null
                // ownerId, consistent with checkAnswer() and QuestionReportController: no
                // legitimate Question row should have a null owner, so treating null as
                // "belongs to whoever's asking" was pure fail-open risk with no upside.
                questionRepository.findAllById(ids).forEach(q -> {
                    if (q.getOwnerId() != null && q.getOwnerId().equalsIgnoreCase(studentId)) {
                        questionMap.put(q.getId(), q);
                    } else {
                        System.err.println("Ignored answer for question " + q.getId()
                                + " — owned by a different student than the submitter (" + studentId + ").");
                    }
                });
            }
        }

        // ── Essay grading — Claude assigns a 0-100 score directly; that fraction contributes to the
        // overall score. Structured types (MATCHING/FILLBLANK/SORTING) also contribute
        // fractional credit via gradeFraction() rather than all-or-nothing. ──
        Map<Long, Map<String, Object>> essayGrades = new LinkedHashMap<>(); // questionId -> {score, feedback, met, missed}
        double essayCreditTotal = 0.0;
        double structuredCreditTotal = 0.0; // fractional credit from MATCHING/FILLBLANK/SORTING

        if (submission.answers != null) {
            for (AnswerItem answer : submission.answers) {
                try {
                    if (answer.questionId == null) continue;
                    Question q = questionMap.get(answer.questionId);
                    if (q == null) continue;

                    if ("ESSAY".equalsIgnoreCase(q.getType())) {
                        String studentAnswer = answer.selectedAnswer != null ? answer.selectedAnswer.toString() : "";
                        List<String> rubric = extractRubric(q.getPayload());

                        Map<String, Object> grade = gradeEssayWithAi(q.getQuestionText(), rubric, studentAnswer);
                        essayGrades.put(q.getId(), grade);

                        double score = ((Number) grade.getOrDefault("score", 0)).doubleValue();
                        essayCreditTotal += Math.max(0, Math.min(100, score)) / 100.0;
                    } else {
                        String type = q.getType() != null ? q.getType().toUpperCase(Locale.ROOT) : "MCQ";
                        boolean isStructured = type.equals("MATCHING") || type.equals("FILLBLANK")
                                || type.equals("SORTING");
                        double fraction = gradeFraction(q, answer.selectedAnswer);
                        if (isStructured) {
                            structuredCreditTotal += fraction;
                        } else if (fraction >= 1.0) {
                            correctCount++;
                        }
                    }
                } catch (Exception e) {
                    // Never let one malformed answer take down the whole submission.
                    System.err.println("Skipped one answer during scoring: " + e.getMessage());
                }
            }
        }

        // Whole-question-equivalent credit, rounded only for the legacy int-based storage pipeline.
        // The precise fractional score is still used for performanceScore below.
        int essayWholeCreditRounded = (int) Math.round(essayCreditTotal);
        int structuredWholeCreditRounded = (int) Math.round(structuredCreditTotal);
        int correctCountForStorage = correctCount + essayWholeCreditRounded + structuredWholeCreditRounded;

        // Precise score using exact essay + structured-type fractional credit (not rounded).
        double precisePerformanceScore = totalItems > 0
                ? ((correctCount + essayCreditTotal + structuredCreditTotal) / totalItems) * 100.0
                : 0.0;

        // Adaptive difficulty: score ≥ 90 → Hard, ≥ 70 → Medium, else Easy.
        boolean isWeak = precisePerformanceScore < DifficultyTier.MEDIUM_MIN;
        String nextDiff = DifficultyTier.fromScore(precisePerformanceScore).toLowerCase(Locale.ROOT);

        // ── FirstQuizResult bookkeeping — Targeted quizzes never touch the locked general/adapted score. ──
        boolean isTargeted = submission.difficulty != null && submission.difficulty.equalsIgnoreCase("Targeted");
        boolean isAdapted = submission.isAdapted != null && submission.isAdapted;

        if (!isTargeted) {
            updateFirstQuizResult(studentId, submission.topic, submission.difficulty,
                    precisePerformanceScore, isAdapted);
        }

        String recoReason = isWeak
                ? "Read again — score below " + (int) DifficultyTier.MEDIUM_MIN + "%."
                : precisePerformanceScore >= DifficultyTier.HARD_MIN
                  ? "Excellent! Moving up to Hard difficulty."
                  : "Good job! Keep practicing at this level.";

        Map<String, Object> recoMap = new LinkedHashMap<>();
        recoMap.put("nextTopic", submission.topic);
        recoMap.put("nextDiff", nextDiff);
        recoMap.put("reason", recoReason);

        // ── AI Question Categorization — batches all questions into one Claude call per submission ──
        Map<Long, String> questionCategoryMap = new LinkedHashMap<>();
        try {
            List<Map<String, String>> questionsForCategorization = new ArrayList<>();
            if (submission.answers != null) {
                for (AnswerItem answer : submission.answers) {
                    if (answer.questionId == null) continue;
                    Question q = questionMap.get(answer.questionId);
                    if (q != null) {
                        Map<String, String> qInfo = new LinkedHashMap<>();
                        qInfo.put("id", String.valueOf(q.getId()));
                        qInfo.put("questionText", q.getQuestionText() != null ? q.getQuestionText() : "");
                        qInfo.put("type", q.getType() != null ? q.getType() : "MCQ");
                        questionsForCategorization.add(qInfo);
                    }
                }
            }

            if (!questionsForCategorization.isEmpty()) {
                String catRaw = claudeService.categorizeQuestions(questionsForCategorization);
                catRaw = catRaw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
                ObjectMapper catMapper = MAPPER;
                JsonNode catArray = catMapper.readTree(catRaw);
                if (catArray.isArray()) {
                    for (JsonNode node : catArray) {
                        String idStr = node.path("id").asText("");
                        String category = node.path("category").asText("Analysis");
                        try { questionCategoryMap.put(Long.parseLong(idStr), category); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Question categorization failed (non-fatal): " + e.getMessage());
        }

        // Per-question results (with category) for quizfinish.html's charts. QuestionPerformance rows
        // are batched into one saveAll() rather than per-question inserts.
        List<Map<String, Object>> questionResults = new ArrayList<>();
        List<QuestionPerformance> perfRows = new ArrayList<>();
        if (submission.answers != null) {
            for (AnswerItem answer : submission.answers) {
                if (answer.questionId == null) continue;
                Question q = questionMap.get(answer.questionId);
                if (q == null) continue;
                String category = questionCategoryMap.getOrDefault(answer.questionId, "Analysis");

                Map<String, Object> qResult = new LinkedHashMap<>();
                qResult.put("questionId", q.getId());
                qResult.put("type", q.getType() != null ? q.getType().toLowerCase() : "mcq");
                qResult.put("question", q.getQuestionText());
                qResult.put("yourAnswer", formatYourAnswer(q, answer.selectedAnswer));
                qResult.put("weaknessCategory", category);

                if ("ESSAY".equalsIgnoreCase(q.getType())) {
                    Map<String, Object> grade = essayGrades.get(q.getId());
                    int score = grade != null ? ((Number) grade.getOrDefault("score", 0)).intValue() : 0;
                    qResult.put("correctAnswer", null);
                    qResult.put("essayScore", score);
                    qResult.put("essayFeedback", grade != null ? grade.get("feedback") : "");
                    qResult.put("essayMetRubricPoints", grade != null ? grade.get("met") : List.of());
                    qResult.put("essayMissedRubricPoints", grade != null ? grade.get("missed") : List.of());
                    // >=75 counts as correct-leaning, <75 needs work — consistent with category charts.
                    qResult.put("result", score >= 75 ? "correct" : score >= 50 ? "partial" : "wrong");
                } else {
                    // Three-state result: fraction strictly between 0 and 1 (e.g. 3/4 matching pairs)
                    // is reported as "partial" here too, matching /api/quiz/check's live feedback.
                    double fraction = gradeFraction(q, answer.selectedAnswer);
                    String resultLabel = fraction >= 1.0 ? "correct" : fraction > 0.0 ? "partial" : "wrong";
                    qResult.put("correctAnswer", q.getCorrectAnswer());
                    qResult.put("result", resultLabel);
                    // Exact 0.0-1.0 credit alongside the rounded "result" label, for callers needing precise partial credit.
                    qResult.put("creditFraction", fraction);
                }

                questionResults.add(qResult);
                Double essayScoreForPerf = qResult.containsKey("essayScore")
                        ? ((Number) qResult.get("essayScore")).doubleValue() : null;
                // Collect for batch insert below — avoids one INSERT per question.
                perfRows.add(new QuestionPerformance(
                        studentId, submission.topic, category,
                        (String) qResult.get("result"), essayScoreForPerf, LocalDateTime.now()));
            }
        }
        // Single batch INSERT for all QuestionPerformance rows.
        if (!perfRows.isEmpty()) {
            questionPerformanceRepository.saveAll(perfRows);
        }

        // Persist including full per-question breakdown so quizfinish.html can show past attempts too.
        Attempt savedAttempt = new Attempt(
                studentId, submission.topic, submission.difficulty,
                totalItems, correctCountForStorage, precisePerformanceScore,
                nextDiff, LocalDateTime.now());
        try {
            savedAttempt.setDetails(MAPPER.writeValueAsString(questionResults));
        } catch (Exception e) {
            System.err.println("Could not serialize question-level details: " + e.getMessage());
        }
        attemptRepository.save(savedAttempt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalItems", totalItems);
        result.put("correctAnswers", correctCountForStorage);
        result.put("topic", submission.topic);
        result.put("difficulty", submission.difficulty);
        result.put("score", precisePerformanceScore);
        result.put("nextDiff", nextDiff);
        result.put("recommendation", recoMap);
        result.put("aiTutor", buildTutorInsight(submission.topic, precisePerformanceScore));
        result.put("questionResults", questionResults);
        return ResponseEntity.ok(result);
    }

    /** Extracts rubric strings from an ESSAY question's payload ({"rubric":[...]}. Empty list if missing/malformed. */
    private List<String> extractRubric(String payloadJson) {
        List<String> rubric = new ArrayList<>();
        if (payloadJson == null || payloadJson.isBlank()) return rubric;
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            JsonNode rubricNode = node.path("rubric");
            if (rubricNode.isArray()) {
                rubricNode.forEach(r -> rubric.add(r.asText("")));
            }
        } catch (Exception e) {
            System.err.println("Could not parse essay rubric payload: " + e.getMessage());
        }
        return rubric;
    }

    /** Grades one essay via Claude into {score, feedback, met, missed}. Falls back to 0 with an explanatory message on failure. */
    private Map<String, Object> gradeEssayWithAi(String questionText, List<String> rubric, String studentAnswer) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String raw = claudeService.gradeEssay(questionText, rubric, studentAnswer);
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = MAPPER.readTree(raw);

            int score = node.path("score").asInt(0);
            score = Math.max(0, Math.min(100, score));

            List<String> met = new ArrayList<>();
            node.path("metRubricPoints").forEach(n -> met.add(n.asText("")));
            List<String> missed = new ArrayList<>();
            node.path("missedRubricPoints").forEach(n -> missed.add(n.asText("")));

            result.put("score", score);
            result.put("feedback", node.path("feedback").asText(""));
            result.put("met", met);
            result.put("missed", missed);
        } catch (Exception e) {
            System.err.println("Essay grading failed, defaulting to 0: " + e.getMessage());
            result.put("score", 0);
            result.put("feedback", "This answer could not be automatically graded due to a system error. Please contact your instructor for a manual review.");
            result.put("met", List.of());
            result.put("missed", List.of());
        }
        return result;
    }

    /**
     * Keeps FirstQuizResult in sync. First-ever GENERAL quiz locks generalScore permanently.
     * Any ADAPTED submission always updates latestAdaptedScore/Tier and invalidates the lesson cache.
     */
    private void updateFirstQuizResult(String studentId, String topic, String difficulty,
                                       double score, boolean isAdapted) {
        Optional<FirstQuizResult> existingOpt =
                firstQuizResultRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);

        if (isAdapted) {
            FirstQuizResult result = existingOpt.orElseGet(() ->
                    new FirstQuizResult(studentId, topic, score, difficulty));

            if (existingOpt.isPresent()) {
                // generalScore stays untouched — only the adapted fields move.
                result.setLatestAdaptedScore(score);
                result.setLatestAdaptedTier(difficulty);
                result.setUpdatedAt(LocalDateTime.now());
            } else {
                // No general result yet — use this score as the best-available general score too.
                result.setLatestAdaptedScore(score);
                result.setLatestAdaptedTier(difficulty);
            }
            firstQuizResultRepository.save(result);

            // Force the lesson to regenerate at the new tier.
            lessonCacheRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
            return;
        }

        // General quiz path — only ever write once.
        if (existingOpt.isEmpty()) {
            FirstQuizResult result = new FirstQuizResult(studentId, topic, score, difficulty);
            firstQuizResultRepository.save(result);
        }
    }

    // ── Adapted quiz ──

    @PostMapping("/adapted")
    public ResponseEntity<Map<String, Object>> generateAdaptedQuiz(
            @RequestBody AdaptedQuizRequest request,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));

        // Daily cap, checked before any DB/Claude work.
        if (!dailyActionLimiter.tryConsume("adapted-quiz", studentId, MAX_ADAPTED_QUIZZES_PER_DAY)) {
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "message", "Daily Adapted Quiz limit reached (" + MAX_ADAPTED_QUIZZES_PER_DAY + " per day). Please try again tomorrow."));
        }

        String topic = request.topic;
        Double bestScore = attemptRepository.findBestScoreByStudentIdAndTopic(studentId, topic);
        double score = bestScore != null ? bestScore : 0.0;

        // Find the uploaded material text for this topic
        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId);
        Material material = materials.stream()
                .filter(m -> m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);

        if (material == null) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "No uploaded material found for topic: " + topic + ". Please upload a handout first."));
        }

        // Re-read the file to get extracted text
        String text = readMaterialText(material);
        if (text.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "Could not read material text. The file may be a scanned PDF or unsupported format."));
        }

        String targetDifficulty = DifficultyTier.fromScore(score);
        String aiContext = MaterialController.knowledgeContextForQuiz(material, text);

        int generated = 0;
        try {
            String raw = claudeService.generateAdaptedQuestions(topic, aiContext, score);
            raw = ClaudeService.stripJsonFence(raw);

            ObjectMapper mapper = MAPPER;
            JsonNode array = mapper.readTree(raw);
            QuestionParser.ParseResult parsed = QuestionParser.parse(
                    array, text, studentId, topic, targetDifficulty, "ADAPTED QUIZ DROPPED: ");

            if (parsed.questions.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "AI could not generate questions. Try again."));
            }

            // Hard server-side cap of 30 — the prompt only requests 15-30, don't trust the model's count.
            final int MAX_ADAPTED_QUESTIONS = 30;
            List<Question> questionsToSave = parsed.questions.size() > MAX_ADAPTED_QUESTIONS
                    ? parsed.questions.subList(0, MAX_ADAPTED_QUESTIONS)
                    : parsed.questions;

            // Generation succeeded — safe to replace the old question bank.
            clearQuestionsForTopic(studentId, topic);
            questionRepository.saveAll(questionsToSave);
            generated = questionsToSave.size();
        } catch (Exception e) {
            System.err.println("Adapted quiz generation failed: " + e.getMessage());
            return ResponseEntity.ok(Map.of("success", false, "message", "AI question generation failed: " + e.getMessage()));
        }

        if (generated == 0) {
            return ResponseEntity.ok(Map.of("success", false, "message", "AI could not generate questions. Try again."));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Generated " + generated + " adapted questions at " + targetDifficulty + " difficulty based on your best score of " + Math.round(score) + "%.",
                "difficulty", targetDifficulty,
                "bestScore", Math.round(score),
                "quizUrl", "/quizpage.html?topic=" + java.net.URLEncoder.encode(topic, java.nio.charset.StandardCharsets.UTF_8) + "&difficulty=" + targetDifficulty
        ));
    }

    // ── Targeted ("Target Problems") quiz ──

    @PostMapping("/targeted")
    public ResponseEntity<Map<String, Object>> generateTargetedQuiz(
            @RequestBody TargetedQuizRequest request,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));

        String topic = request.topic;
        if (topic == null || topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Topic is required."));
        }

        // Daily cap, checked before any DB/Claude work.
        if (!dailyActionLimiter.tryConsume("targeted-quiz", studentId, MAX_TARGETED_QUIZZES_PER_DAY)) {
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "message", "Daily Target Problems limit reached (" + MAX_TARGETED_QUIZZES_PER_DAY + " per day). Please try again tomorrow."));
        }

        // Find the uploaded material text for this topic (same approach as adapted quiz)
        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId);
        Material material = materials.stream()
                .filter(m -> m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);

        if (material == null) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "No uploaded material found for topic: " + topic + ". Please upload a handout first."));
        }

        String text = readMaterialText(material);
        if (text.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "Could not read material text. The file may be a scanned PDF or unsupported format."));
        }

        List<Attempt> topicAttempts = attemptRepository.findByStudentIdOrderByTimestampDesc(studentId).stream()
                .filter(a -> topic.equalsIgnoreCase(a.getTopic()))
                .toList();
        double avgScore = topicAttempts.isEmpty()
                ? -1.0
                : topicAttempts.stream().mapToDouble(Attempt::getPerformanceScore).average().orElse(-1.0);

        List<String> weakConcepts = List.of();
        List<Map<String, String>> wrongAnswers = gatherWrongQuestionDetails(studentId, topic);
        String aiContext = MaterialController.knowledgeContextForQuiz(material, text);

        int generated = 0;
        try {
            String raw = claudeService.generateTargetedQuestions(topic, aiContext, weakConcepts, wrongAnswers, avgScore);
            raw = ClaudeService.stripJsonFence(raw);

            ObjectMapper mapper = MAPPER;
            JsonNode array = mapper.readTree(raw);
            QuestionParser.ParseResult parsed = QuestionParser.parse(
                    array, text, studentId, topic, "Targeted", "TARGETED QUIZ DROPPED: ");

            if (parsed.questions.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "AI could not generate targeted questions. Try again."));
            }

            // Hard server-side cap of 15 — don't trust the model's count.
            final int MAX_TARGETED_QUESTIONS = 15;
            List<Question> questionsToSave = parsed.questions.size() > MAX_TARGETED_QUESTIONS
                    ? parsed.questions.subList(0, MAX_TARGETED_QUESTIONS)
                    : parsed.questions;

            // Generation succeeded — safe to replace the old question bank.
            clearQuestionsForTopic(studentId, topic);
            questionRepository.saveAll(questionsToSave);
            generated = questionsToSave.size();
        } catch (Exception e) {
            System.err.println("Targeted quiz generation failed: " + e.getMessage());
            return ResponseEntity.ok(Map.of("success", false, "message", "AI question generation failed: " + e.getMessage()));
        }

        if (generated == 0) {
            return ResponseEntity.ok(Map.of("success", false, "message", "AI could not generate targeted questions. Try again."));
        }

        String message = wrongAnswers.isEmpty()
                ? "Generated " + generated + " targeted questions focused on your weak areas in " + topic + "."
                : "Generated " + generated + " targeted questions built directly from " + wrongAnswers.size()
                  + " of your actual wrong answers in " + topic + ".";

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", message,
                "weakConcepts", weakConcepts,
                "mistakesTargeted", wrongAnswers.size(),
                "quizUrl", "/quizpage.html?topic=" + java.net.URLEncoder.encode(topic, java.nio.charset.StandardCharsets.UTF_8) + "&difficulty=Targeted"
        ));
    }

    /**
     * Pulls the student's actual wrong/partial answers from recent attempts on this topic, so the
     * Target Problems quiz targets real documented mistakes rather than a generic guess. Scans up to
     * 5 recent attempts, deduplicates by question text (most recent miss wins), capped at 15 entries.
     */
    private List<Map<String, String>> gatherWrongQuestionDetails(String studentId, String topic) {
        List<Map<String, String>> wrongDetails = new ArrayList<>();
        Set<String> seenQuestions = new LinkedHashSet<>();

        List<Attempt> attempts = attemptRepository.findByStudentIdOrderByTimestampDesc(studentId).stream()
                .filter(a -> topic.equalsIgnoreCase(a.getTopic()))
                .limit(5)
                .toList();

        ObjectMapper mapper = MAPPER;

        for (Attempt attempt : attempts) {
            if (attempt.getDetails() == null || attempt.getDetails().isBlank()) continue;
            try {
                JsonNode results = mapper.readTree(attempt.getDetails());
                if (!results.isArray()) continue;

                for (JsonNode q : results) {
                    String result = q.path("result").asText("");
                    if (!"wrong".equalsIgnoreCase(result) && !"partial".equalsIgnoreCase(result)) continue;

                    String questionText = q.path("question").asText("");
                    if (questionText.isBlank() || !seenQuestions.add(questionText)) continue;

                    String type = q.path("type").asText("mcq");

                    Map<String, String> detail = new LinkedHashMap<>();
                    detail.put("question", questionText);
                    detail.put("type", type);
                    detail.put("yourAnswer", q.path("yourAnswer").asText(""));
                    if (!q.path("correctAnswer").isNull() && !q.path("correctAnswer").isMissingNode()) {
                        detail.put("correctAnswer", q.path("correctAnswer").asText(""));
                    }
                    detail.put("category", q.path("weaknessCategory").asText("Analysis"));

                    // Essays have no single "correct answer" — the AI's rubric feedback is the richest signal.
                    if ("essay".equalsIgnoreCase(type)) {
                        StringBuilder missed = new StringBuilder();
                        q.path("essayMissedRubricPoints").forEach(m -> {
                            if (missed.length() > 0) missed.append("; ");
                            missed.append(m.asText(""));
                        });
                        if (missed.length() > 0) detail.put("missedPoints", missed.toString());
                        String feedback = q.path("essayFeedback").asText("");
                        if (!feedback.isBlank()) detail.put("feedback", feedback);
                    }

                    wrongDetails.add(detail);
                    if (wrongDetails.size() >= 15) return wrongDetails;
                }
            } catch (Exception e) {
                System.err.println("Could not parse attempt details while gathering wrong answers: " + e.getMessage());
            }
        }
        return wrongDetails;
    }

    // ── Helpers ──

    /**
     * Re-reads the handout's extracted text from R2. Passes the recorded content type since filename
     * extension alone breaks for unusual filenames. Returns "" if the R2 object is missing.
     */
    private String readMaterialText(Material material) {
        if (material.getStoredFilename() == null) return "";
        byte[] fileBytes = fileStorageService.load(FileStorageService.handoutKey(material.getStoredFilename()));
        if (fileBytes == null) return "";
        return DocumentTextExtractor.extractText(material.getOriginalFilename(), material.getContentType(), fileBytes);
    }

    private void clearQuestionsForTopic(String ownerId, String topic) {
        questionRepository.deleteByOwnerAndTopicIgnoreCase(ownerId, topic);
    }

    /**
     * Converts a Question into the map sent to the browser. Correct answers and grading keys are
     * always omitted — grading happens server-side in submitQuiz(). hint is sent (doesn't reveal the
     * answer); explanation is withheld until after submission.
     */
    private Map<String, Object> questionToMap(Question q) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", q.getId());
        item.put("topic", q.getTopic());
        item.put("difficulty", q.getDifficulty());
        String type = q.getType() != null ? q.getType().toUpperCase(Locale.ROOT) : "MCQ";
        item.put("type", type);
        item.put("questionText", q.getQuestionText());
        item.put("question", q.getQuestionText());

        // MCQ / TRUEFALSE: send the display options but NOT the correct answer.
        if (type.equals("MCQ") || type.equals("TRUEFALSE") || type.equals("PRACTICAL")) {
            item.put("optionA", q.getOptionA());
            item.put("optionB", q.getOptionB());
            item.put("optionC", q.getOptionC());
            item.put("optionD", q.getOptionD());
        }

        // Build a scrubbed payload for structured types.
        String scrubbedPayload = scrubPayload(type, q.getPayload());
        item.put("payload", scrubbedPayload);

        // hint is safe to send before submission; explanation is withheld until after grading.
        item.put("hint", q.getHint());
        item.put("explanation", null);

        return item;
    }

    /** Copy of the payload JSON with grading-sensitive fields removed, keeping only what the frontend needs to render. */
    private String scrubPayload(String type, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            ObjectMapper m = MAPPER;
            JsonNode root = m.readTree(payloadJson);
            ObjectNode out = m.createObjectNode();

            switch (type) {
                case "MCQ", "TRUEFALSE" -> {
                    // Options already sent as optionA-D flat fields.
                    return null;
                }
                case "MATCHING" -> {
                    // Strip correctPairs.
                    if (root.has("leftItems"))  out.set("leftItems",  root.get("leftItems"));
                    if (root.has("rightItems")) out.set("rightItems", root.get("rightItems"));
                }
                case "FILLBLANK" -> {
                    // Strip blanks[].answer.
                    if (root.has("excerpt")) out.put("excerpt", root.get("excerpt").asText());
                    String mode = root.path("mode").asText("");
                    if (root.has("mode")) out.put("mode", mode);
                    JsonNode blanks = root.path("blanks");
                    if (blanks.isArray()) {
                        com.fasterxml.jackson.databind.node.ArrayNode scrubbed =
                                m.createArrayNode();
                        for (JsonNode b : blanks) {
                            ObjectNode sb = m.createObjectNode();
                            if (b.has("id")) sb.set("id", b.get("id"));
                            scrubbed.add(sb);
                        }
                        out.set("blanks", scrubbed);

                        // Drag-drop mode: the word bank IS the answer set (matching is the challenge,
                        // not concealment), so send it as chips. Typed mode never gets this.
                        if ("dragdrop".equalsIgnoreCase(mode)) {
                            List<String> words = new ArrayList<>();
                            for (JsonNode b : blanks) {
                                String answer = b.path("answer").asText("");
                                if (!answer.isBlank()) words.add(answer);
                            }
                            Collections.shuffle(words);
                            com.fasterxml.jackson.databind.node.ArrayNode wordBank = m.createArrayNode();
                            words.forEach(wordBank::add);
                            out.set("wordBank", wordBank);
                        }
                    }
                }
                case "SORTING" -> {
                    // Strip correctCategory.
                    if (root.has("categoryA")) out.put("categoryA", root.get("categoryA").asText());
                    if (root.has("categoryB")) out.put("categoryB", root.get("categoryB").asText());
                    JsonNode items = root.path("items");
                    if (items.isArray()) {
                        com.fasterxml.jackson.databind.node.ArrayNode scrubbed =
                                m.createArrayNode();
                        for (JsonNode it : items) {
                            ObjectNode si = m.createObjectNode();
                            if (it.has("text")) si.set("text", it.get("text"));
                            scrubbed.add(si);
                        }
                        out.set("items", scrubbed);
                    }
                }
                case "CONCEPTID" -> {
                    // Strip correctAnswer.
                    if (root.has("clues"))   out.set("clues",   root.get("clues"));
                    if (root.has("options")) out.set("options", root.get("options"));
                }
                case "PRACTICAL" -> {
                    // Presentation metadata only — options travel as optionA-D; key and working stay server-side.
                    out.put("kind", root.path("kind").asText("SOLVE").toUpperCase(Locale.ROOT));
                    String language = root.path("language").asText("");
                    if (!language.isBlank()) out.put("language", language);
                }
                case "ESSAY" -> {
                    // Rubric is safe to show — it's guidance, not the answer.
                    if (root.has("rubric")) out.set("rubric", root.get("rubric"));
                }
                default -> {
                    // Unknown type — send nothing rather than accidentally leaking answers.
                    return null;
                }
            }
            return out.toString();
        } catch (Exception e) {
            System.err.println("scrubPayload failed for type " + type + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Grades an answer, returning credit as a fraction 0.0-1.0. MCQ/TRUEFALSE/CONCEPTID are binary.
     * MATCHING/FILLBLANK/SORTING are multi-part and award proportional credit.
     */
    private double gradeFraction(Question question, Object selectedAnswerObj) {
        if (question == null || selectedAnswerObj == null) return 0.0;

        String type = question.getType() != null ? question.getType().toUpperCase(Locale.ROOT) : "MCQ";

        // Structured types send an array of objects with their answer key in the payload JSON
        // (not question.getCorrectAnswer(), which is MCQ/TRUEFALSE-only) — route to their own evaluator.
        switch (type) {
            case "MATCHING":
                return matchingFraction(question, selectedAnswerObj);
            case "FILLBLANK":
                return fillBlankOrDiagramFraction(question, selectedAnswerObj);
            case "SORTING":
                return sortingFraction(question, selectedAnswerObj);
            case "CONCEPTID":
                return isCorrectConceptId(question, selectedAnswerObj) ? 1.0 : 0.0;
            case "PRACTICAL":
                return practicalFraction(question, selectedAnswerObj);
            default:
                break; // fall through to MCQ/TRUEFALSE string comparison below
        }

        if (question.getCorrectAnswer() == null) return 0.0;
        if (!(selectedAnswerObj instanceof String)) return 0.0;
        String selectedAnswer = (String) selectedAnswerObj;
        String selected = selectedAnswer.trim();
        String correctLetter = question.getCorrectAnswer().trim();
        if (correctLetter.equalsIgnoreCase(selected)) return 1.0;
        String correctText = switch (correctLetter.toUpperCase(Locale.ROOT)) {
            case "A" -> question.getOptionA();
            case "B" -> question.getOptionB();
            case "C" -> question.getOptionC();
            case "D" -> question.getOptionD();
            default -> correctLetter;
        };
        return (correctText != null && correctText.trim().equalsIgnoreCase(selected)) ? 1.0 : 0.0;
    }

    /**
     * Parses a question's stored payload JSON. Returns an empty/missing node
     * (never null/throws) so callers can safely chain .path(...) lookups.
     */
    private JsonNode parsePayload(Question question) {
        try {
            if (question.getPayload() == null || question.getPayload().isBlank()) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(question.getPayload());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** MATCHING credit is proportional: each correct pair present earns 1/N. selectedAnswer is a JSON array of {"left","right"}. */
    private double matchingFraction(Question question, Object selectedAnswerObj) {
        try {
            JsonNode payload = parsePayload(question);
            JsonNode correctPairs = payload.path("correctPairs");
            if (!correctPairs.isArray() || correctPairs.size() == 0) return 0.0;

            JsonNode answerNode = toJsonNode(selectedAnswerObj);
            if (!answerNode.isArray()) return 0.0;

            int totalPairs = correctPairs.size();
            int matchedPairs = 0;
            for (JsonNode pair : correctPairs) {
                if (!pair.isArray() || pair.size() < 2) continue;
                int wantLeft = pair.get(0).asInt(-1);
                int wantRight = pair.get(1).asInt(-1);
                for (JsonNode a : answerNode) {
                    if (a.path("left").asInt(-2) == wantLeft && a.path("right").asInt(-2) == wantRight) {
                        matchedPairs++;
                        break;
                    }
                }
            }
            return totalPairs == 0 ? 0.0 : (double) matchedPairs / totalPairs;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * FILLBLANK credit is proportional across blanks. The client only ever sends
     * {"id","filled"} — the correct "answer" is intentionally stripped out of the payload
     * before it reaches the browser (see scrubPayload()), so it must be looked up here from
     * the question's own stored payload (blanks[]), keyed by blank id.
     *
     * Previously this read a.path("answer") directly off the CLIENT-sent answer object, which
     * never has that field — so correctText was always "" and FILLBLANK questions were
     * graded wrong even when every blank was filled in correctly.
     */
    private double fillBlankOrDiagramFraction(Question question, Object selectedAnswerObj) {
        try {
            JsonNode payload = parsePayload(question);
            JsonNode correctList = payload.path("blanks");
            if (!correctList.isArray() || correctList.size() == 0) return 0.0;

            Map<Integer, String> correctById = new LinkedHashMap<>();
            for (JsonNode c : correctList) {
                correctById.put(c.path("id").asInt(-1), c.path("answer").asText("").trim());
            }

            JsonNode answerNode = toJsonNode(selectedAnswerObj);
            if (!answerNode.isArray() || answerNode.size() == 0) return 0.0;

            int total = correctById.size();
            int correctCount = 0;
            for (JsonNode a : answerNode) {
                int id = a.path("id").asInt(-1);
                String filled = a.path("filled").asText("").trim();
                String correctText = correctById.getOrDefault(id, "");
                if (!correctText.isBlank() && filled.equalsIgnoreCase(correctText)) correctCount++;
            }
            return total == 0 ? 0.0 : (double) correctCount / total;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * SORTING credit is proportional across items, re-verified against the question's own payload
     * rather than trusting the client-sent "correct" field. Unassigned items earn no credit.
     */
    private double sortingFraction(Question question, Object selectedAnswerObj) {
        try {
            JsonNode payload = parsePayload(question);
            JsonNode items = payload.path("items");
            if (!items.isArray() || items.size() == 0) return 0.0;

            JsonNode answerNode = toJsonNode(selectedAnswerObj);
            boolean hasAnswers = answerNode.isArray() && answerNode.size() > 0;

            int total = items.size();
            int correctCount = 0;
            for (JsonNode item : items) {
                String text = item.path("text").asText("");
                String correctCategory = item.path("correctCategory").asText("");
                if (!hasAnswers) continue;
                for (JsonNode a : answerNode) {
                    if (a.path("text").asText("").equals(text)) {
                        if (a.path("assigned").asText("").equals(correctCategory)) correctCount++;
                        break;
                    }
                }
            }
            return total == 0 ? 0.0 : (double) correctCount / total;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * PRACTICAL grading is a plain text match against the stored correct option. It is deliberately NOT
     * letter-mapped like MCQ: an option's own text can be "A" or "B" (e.g. print("A")), which the MCQ path
     * would misread as a positional reference.
     */
    private double practicalFraction(Question question, Object selectedAnswerObj) {
        if (!(selectedAnswerObj instanceof String selected)) return 0.0;
        String correct = question.getCorrectAnswer();
        if (correct == null || correct.isBlank()) return 0.0;
        return selected.trim().equalsIgnoreCase(correct.trim()) ? 1.0 : 0.0;
    }

    /** CONCEPTID's correct answer lives in payload.correctAnswer (never in question.getCorrectAnswer()). */
    private boolean isCorrectConceptId(Question question, Object selectedAnswerObj) {
        if (!(selectedAnswerObj instanceof String selected)) return false;
        JsonNode payload = parsePayload(question);
        String correctAnswer = payload.path("correctAnswer").asText("");
        if (correctAnswer.isBlank()) return false;
        return selected.trim().equalsIgnoreCase(correctAnswer.trim());
    }

    /** Converts a deserialized selectedAnswer (List/Map/JsonNode) into a JsonNode for consistent access. */
    private JsonNode toJsonNode(Object value) {
        if (value instanceof JsonNode node) return node;
        return MAPPER.valueToTree(value);
    }

    /** Renders selectedAnswer into a readable string for quizfinish.html. Structured types need custom rendering since Jackson's default toString() is unreadable. */
    private String formatYourAnswer(Question question, Object selectedAnswerObj) {
        if (selectedAnswerObj == null) return "No answer given";

        String type = question.getType() != null ? question.getType().toUpperCase(Locale.ROOT) : "MCQ";

        switch (type) {
            case "MATCHING":
                return formatMatchingAnswer(question, toJsonNode(selectedAnswerObj));
            case "SORTING":
                return formatSortingAnswer(question, toJsonNode(selectedAnswerObj));
            case "FILLBLANK":
                return formatFillBlankAnswer(toJsonNode(selectedAnswerObj));
            default:
                return selectedAnswerObj.toString();
        }
    }

    private String formatMatchingAnswer(Question question, JsonNode answerNode) {
        if (!answerNode.isArray() || answerNode.size() == 0) return "No answer given";
        JsonNode payload = parsePayload(question);
        JsonNode leftItems = payload.path("leftItems");
        JsonNode rightItems = payload.path("rightItems");

        List<String> pairs = new ArrayList<>();
        for (JsonNode pair : answerNode) {
            int left = pair.path("left").asInt(-1);
            int right = pair.path("right").asInt(-1);
            String leftText = (left >= 0 && left < leftItems.size()) ? leftItems.get(left).asText("") : "?";
            String rightText = (right >= 0 && right < rightItems.size()) ? rightItems.get(right).asText("") : "?";
            pairs.add(leftText + " → " + rightText);
        }
        return pairs.isEmpty() ? "No answer given" : String.join("; ", pairs);
    }

    private String formatSortingAnswer(Question question, JsonNode answerNode) {
        if (!answerNode.isArray() || answerNode.size() == 0) return "No answer given";
        JsonNode payload = parsePayload(question);
        String labelA = payload.path("categoryA").asText("Category A");
        String labelB = payload.path("categoryB").asText("Category B");

        List<String> groupA = new ArrayList<>();
        List<String> groupB = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (JsonNode item : answerNode) {
            String text = item.path("text").asText("");
            String assigned = item.path("assigned").asText("");
            if ("A".equalsIgnoreCase(assigned)) groupA.add(text);
            else if ("B".equalsIgnoreCase(assigned)) groupB.add(text);
            else if (!text.isBlank()) other.add(text);
        }
        List<String> parts = new ArrayList<>();
        if (!groupA.isEmpty()) parts.add(labelA + ": " + String.join(", ", groupA));
        if (!groupB.isEmpty()) parts.add(labelB + ": " + String.join(", ", groupB));
        if (!other.isEmpty()) parts.add("Unsorted: " + String.join(", ", other));
        return parts.isEmpty() ? "No answer given" : String.join(" | ", parts);
    }

    private String formatFillBlankAnswer(JsonNode answerNode) {
        if (!answerNode.isArray() || answerNode.size() == 0) return "No answer given";
        List<String> filled = new ArrayList<>();
        for (JsonNode a : answerNode) {
            String text = a.path("filled").asText("").trim();
            filled.add(text.isBlank() ? "(blank)" : text);
        }
        return String.join(", ", filled);
    }

    private Map<String, Object> buildTutorInsight(String topic, double score) {
        String focusConcept = topic;

        List<String> nextSteps = new ArrayList<>();
        if (score >= DifficultyTier.HARD_MIN) {
            nextSteps.add("Try the Adapted Quiz to get harder, more advanced questions tailored to your level.");
            nextSteps.add("Create one example that uses " + focusConcept + " in a real situation.");
            nextSteps.add("Do a short review tomorrow to confirm retention.");
        } else if (score >= DifficultyTier.MEDIUM_MIN) {
            nextSteps.add("Review " + focusConcept + " and explain why the correct answer is correct.");
            nextSteps.add("Try the Adapted Quiz to get targeted questions based on your best score.");
            nextSteps.add("Retake after reviewing your mistakes.");
        } else {
            nextSteps.add("Return to the basic definition of " + focusConcept + ".");
            nextSteps.add("Rewrite the concept in your own words before retaking.");
            nextSteps.add("Try the Adapted Quiz once you feel more confident.");
        }

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("recommendedDifficulty", DifficultyTier.fromScore(score));
        insight.put("weakConcept", score >= 80 ? "Application and retention" : focusConcept);
        insight.put("message", "Score: " + Math.round(score) + "%. Keep going — use the Adapted Quiz button below to get a quiz tailored to your performance.");
        insight.put("nextSteps", nextSteps);
        return insight;
    }

    // ── Test all question types (debug) ──

    @PostMapping("/test-types")
    public ResponseEntity<Map<String, Object>> testAllTypes(
            @RequestParam String topic,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }

        // Find uploaded material for this topic
        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId);
        Material material = materials.stream()
                .filter(m -> m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);

        if (material == null) {
            return ResponseEntity.ok(Map.of("success", false, "message",
                    "No uploaded material found for topic: " + topic + ". Upload a handout first."));
        }

        String text = readMaterialText(material);
        if (text.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message",
                    "Could not read material text. Try a text-based PDF or TXT file."));
        }

        String aiContext = MaterialController.knowledgeContextForQuiz(material, text);

        String raw;
        try {
            raw = claudeService.generateTestAllTypesQuiz(topic, aiContext);
            System.out.println("=== TEST-TYPES RAW RESPONSE ===");
            System.out.println(raw);
            System.out.println("=== END TEST-TYPES RESPONSE ===");
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Claude call failed: " + e.getMessage()));
        }

        raw = ClaudeService.stripJsonFence(raw);

        List<Map<String, Object>> parsed = new ArrayList<>();
        List<Question> saved = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        try {
            JsonNode array =
                    MAPPER.readTree(raw);

            if (!array.isArray()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "Claude did not return a JSON array."));
            }

            // Clear existing questions for this topic so test quiz is clean
            clearQuestionsForTopic(studentId, topic);

            QuestionParser.ParseResult parsedResult = QuestionParser.parse(
                    array, text, studentId, topic, "Test", "TEST-TYPES DROPPED: ");
            saved.addAll(parsedResult.questions);
            dropped.addAll(parsedResult.droppedReasons);
            for (Question q : parsedResult.questions) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("type", q.getType());
                info.put("questionText", q.getQuestionText());
                info.put("payload", q.getPayload() == null ? "" : q.getPayload());
                parsed.add(info);
            }

            questionRepository.saveAll(saved);

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Parse failed: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Generated " + saved.size() + " test questions (" + dropped.size() + " dropped).",
                "questionsGenerated", saved.size(),
                "droppedReasons", dropped,
                "questions", parsed,
                "quizUrl", "/quizpage.html?topic=" + java.net.URLEncoder.encode(topic,
                        java.nio.charset.StandardCharsets.UTF_8) + "&difficulty=Test"
        ));
    }

    // ── Check a single answer (live per-question feedback) ──
    //
    // GET /questions strips correct answers before sending to the browser, so the frontend can't
    // grade locally. This endpoint grades server-side with the same gradeFraction() logic used by
    // /api/quiz/submit, and returns result + explanation + correct answer for the feedback banner.
    // Does NOT save an attempt — /api/quiz/submit does that at the end.

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkAnswer(
            @RequestBody CheckRequest req,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));

        if (req.questionId == null)
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "questionId is required."));

        Optional<Question> qOpt = questionRepository.findById(req.questionId);
        if (qOpt.isEmpty())
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Question not found."));

        Question q = qOpt.get();

        // Ownership check — only the student who owns this question can check answers on it.
        // Fail closed on a null/blank ownerId rather than skipping the check: every current
        // generation path sets ownerId, so null means a legacy row or a bug — and skipping
        // here would leak another student's correct answer, not just misfile a report.
        if (q.getOwnerId() == null || !q.getOwnerId().equalsIgnoreCase(studentId)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Not your question."));
        }

        String type = q.getType() != null ? q.getType().toUpperCase(Locale.ROOT) : "MCQ";
        double fraction = "ESSAY".equalsIgnoreCase(type) ? -1.0 : gradeFraction(q, req.selectedAnswer);

        // result: "correct" | "partial" | "wrong" | "neutral" (essay)
        String result;
        if ("ESSAY".equalsIgnoreCase(type)) {
            result = "neutral";
        } else if (fraction >= 1.0) {
            result = "correct";
        } else if (fraction > 0.0) {
            result = "partial";
        } else {
            result = "wrong";
        }

        // Build the correct-answer display string for the feedback banner.
        // For MCQ/TRUEFALSE this is the full option text; for structured types
        // it is a human-readable summary so the student can see what was right.
        String correctAnswerDisplay = buildCorrectAnswerDisplay(q);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("result", result);
        response.put("fraction", fraction);
        response.put("correctAnswer", correctAnswerDisplay);
        response.put("explanation", q.getExplanation() != null ? q.getExplanation() : "");
        response.put("hint", q.getHint() != null ? q.getHint() : "");
        return ResponseEntity.ok(response);
    }

    /** Human-readable correct-answer string for the feedback banner, safe to show after submission. */
    private String buildCorrectAnswerDisplay(Question q) {
        String type = q.getType() != null ? q.getType().toUpperCase(Locale.ROOT) : "MCQ";
        try {
            switch (type) {
                case "MCQ": {
                    String letter = q.getCorrectAnswer();
                    if (letter == null) return "";
                    String text = switch (letter.trim().toUpperCase(Locale.ROOT)) {
                        case "A" -> q.getOptionA();
                        case "B" -> q.getOptionB();
                        case "C" -> q.getOptionC();
                        case "D" -> q.getOptionD();
                        default  -> letter;
                    };
                    return text != null ? text : letter;
                }
                case "PRACTICAL":
                    return q.getCorrectAnswer() != null ? q.getCorrectAnswer() : "";
                case "TRUEFALSE":
                    return q.getCorrectAnswer() != null ? q.getCorrectAnswer() : "";
                case "CONCEPTID": {
                    JsonNode payload = parsePayload(q);
                    return payload.path("correctAnswer").asText("");
                }
                case "MATCHING": {
                    JsonNode payload = parsePayload(q);
                    JsonNode left = payload.path("leftItems");
                    JsonNode right = payload.path("rightItems");
                    JsonNode pairs = payload.path("correctPairs");
                    if (!pairs.isArray()) return "";
                    List<String> lines = new ArrayList<>();
                    for (JsonNode pair : pairs) {
                        if (!pair.isArray() || pair.size() < 2) continue;
                        int li = pair.get(0).asInt(-1);
                        int ri = pair.get(1).asInt(-1);
                        String lt = (li >= 0 && li < left.size()) ? left.get(li).asText("") : "?";
                        String rt = (ri >= 0 && ri < right.size()) ? right.get(ri).asText("") : "?";
                        lines.add(lt + " → " + rt);
                    }
                    return String.join("; ", lines);
                }
                case "FILLBLANK": {
                    JsonNode payload = parsePayload(q);
                    JsonNode blanks = payload.path("blanks");
                    if (!blanks.isArray()) return "";
                    List<String> answers = new ArrayList<>();
                    for (JsonNode b : blanks) answers.add(b.path("answer").asText(""));
                    return String.join(", ", answers);
                }
                case "SORTING": {
                    JsonNode payload = parsePayload(q);
                    JsonNode items = payload.path("items");
                    if (!items.isArray()) return "";
                    String catA = payload.path("categoryA").asText("A");
                    String catB = payload.path("categoryB").asText("B");
                    List<String> groupA = new ArrayList<>(), groupB = new ArrayList<>();
                    for (JsonNode it : items) {
                        String cat = it.path("correctCategory").asText("");
                        if ("A".equals(cat)) groupA.add(it.path("text").asText(""));
                        else groupB.add(it.path("text").asText(""));
                    }
                    List<String> parts = new ArrayList<>();
                    if (!groupA.isEmpty()) parts.add(catA + ": " + String.join(", ", groupA));
                    if (!groupB.isEmpty()) parts.add(catB + ": " + String.join(", ", groupB));
                    return String.join(" | ", parts);
                }
                case "ESSAY":
                    return ""; // graded by AI at submit time
                default:
                    return q.getCorrectAnswer() != null ? q.getCorrectAnswer() : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ── Request classes ──

    public static class QuizSubmission {
        public String topic;
        public String difficulty;
        public List<AnswerItem> answers;
        public Boolean isAdapted;
    }

    public static class AnswerItem {
        public Long questionId;
        // NOTE: was previously typed as String. MATCHING/FILLBLANK/SORTING
        // questions send an array of objects here (e.g. [{"left":0,"right":2}]),
        // not a plain string. With a String field, Jackson threw a deserialization
        // exception on submit for any non-MCQ/TRUEFALSE question, which made the
        // ENTIRE /api/quiz/submit request fail — meaning the attempt was never
        // saved, even though the frontend still rendered a "fake" local results
        // screen from in-browser data. That's why scores never showed up on the
        // Quiz Hub / Dashboard / Reports pages despite the quiz "completing".
        public Object selectedAnswer;
    }

    public static class CheckRequest {
        public Long questionId;
        public Object selectedAnswer;
    }

    public static class AdaptedQuizRequest {
        public String topic;
    }

    public static class TargetedQuizRequest {
        public String topic;
    }
}