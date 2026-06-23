package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

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

    // ── Get questions ─────────────────────────────────────────────────────

    @GetMapping("/questions")
    public List<Map<String, Object>> getQuestions(
            @RequestParam String topic,
            @RequestParam String difficulty,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) studentId = "demo";

        List<Question> questions = questionRepository.findByOwnerAndTopicAndDifficultyIgnoreCase(studentId, topic, difficulty);
        if (questions.isEmpty())
            questions = questionRepository.findByOwnerAndTopicAndDifficultyIgnoreCase(studentId, topic, "Easy");

        return questions.stream().map(this::questionToMap).toList();
    }

    // ── Latest attempt (for quizfinish.html fallback) ──────────────────────

    /**
     * Returns the logged-in student's most recent quiz attempt, including the
     * full per-question breakdown if it was saved (attempts taken before this
     * field existed will have an empty questionResults array).
     */
    @GetMapping("/latest")
    public Map<String, Object> getLatestAttempt(HttpSession session) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) studentId = "demo";

        List<Attempt> attempts = attemptRepository.findByStudentIdOrderByTimestampDesc(studentId);
        if (attempts.isEmpty()) {
            return Map.of("success", false);
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
                questionResults = new ObjectMapper().readValue(latest.getDetails(), List.class);
            } catch (Exception e) {
                System.err.println("Could not parse stored attempt details: " + e.getMessage());
            }
        }
        response.put("questionResults", questionResults);
        return response;
    }

    // ── Submit quiz ───────────────────────────────────────────────────────

    @PostMapping("/submit")
    public Map<String, Object> submitQuiz(@RequestBody QuizSubmission submission, HttpSession session) {
        int correctCount = 0;
        int totalItems = submission.answers == null ? 0 : submission.answers.size();

        // ── Essay grading (AI has full authority over the score) ────────────
        // Essays can't be scored by simple string matching like MCQ/TRUEFALSE.
        // Every ESSAY answer is sent to Claude, which returns a score from
        // 0-100 entirely at its own discretion (not a binary correct/wrong).
        // That fractional score (score/100) is the essay's contribution to
        // the overall quiz score — e.g. a 72% essay contributes 0.72 toward
        // correctCount, not a rounded 0 or 1.
        //
        // Structured types (MATCHING/FILLBLANK/DIAGRAM/SORTING) also contribute
        // fractional credit via gradeFraction() — e.g. matching 3 of 4 pairs
        // correctly contributes 0.75, not a rounded 0 or 1 — instead of the
        // previous all-or-nothing rule where missing one part of a multi-part
        // answer zeroed the entire question.
        Map<Long, Map<String, Object>> essayGrades = new LinkedHashMap<>(); // questionId -> {score, feedback, met, missed}
        double essayCreditTotal = 0.0;
        double structuredCreditTotal = 0.0; // fractional credit from MATCHING/FILLBLANK/DIAGRAM/SORTING

        if (submission.answers != null) {
            for (AnswerItem answer : submission.answers) {
                try {
                    if (answer.questionId == null) continue;
                    Optional<Question> questionOpt = questionRepository.findById(answer.questionId);
                    if (questionOpt.isEmpty()) continue;
                    Question q = questionOpt.get();

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
                                || type.equals("DIAGRAM") || type.equals("SORTING");
                        double fraction = gradeFraction(q, answer.selectedAnswer);
                        if (isStructured) {
                            structuredCreditTotal += fraction;
                        } else if (fraction >= 1.0) {
                            correctCount++;
                        }
                    }
                } catch (Exception e) {
                    // Never let one malformed answer take down the whole submission —
                    // that was the root cause of attempts silently failing to save.
                    System.err.println("Skipped one answer during scoring: " + e.getMessage());
                }
            }
        }

        // Whole-question-equivalent credit from essays and structured
        // (partial-credit) types, rounded only for the legacy int-based
        // correctCount/Attempt storage pipeline. The precise fractional score
        // is still used for the performanceScore below.
        int essayWholeCreditRounded = (int) Math.round(essayCreditTotal);
        int structuredWholeCreditRounded = (int) Math.round(structuredCreditTotal);
        int correctCountForStorage = correctCount + essayWholeCreditRounded + structuredWholeCreditRounded;

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) studentId = "demo";

        // Precise score using exact essay + structured-type fractional credit (not rounded).
        double precisePerformanceScore = totalItems > 0
                ? ((correctCount + essayCreditTotal + structuredCreditTotal) / totalItems) * 100.0
                : 0.0;

        // Adaptive difficulty: score ≥ 90 → Hard, ≥ 70 → Medium, else Easy.
        // Below 60% error rate (≥ 40% correct) is also treated as a weakness
        // that nudges difficulty down — matching the old PerfAnalytics rules.
        boolean isWeak = precisePerformanceScore < DifficultyTier.MEDIUM_MIN;
        String nextDiff = DifficultyTier.fromScore(precisePerformanceScore).toLowerCase(Locale.ROOT);

        // ── FirstQuizResult bookkeeping ─────────────────────────────────────
        // Targeted Problems quizzes never touch the locked general/adapted
        // score that drives Learning Hub lesson content.
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

        // ── AI Question Categorization ──────────────────────────────────────
        // Build the list of questions that were answered so Claude can assign
        // each to one of the 5 performance categories (Terminology, Computation,
        // Application, Analysis, Process Steps).  We batch all questions in a
        // single Claude call to keep latency low.
        Map<Long, String> questionCategoryMap = new LinkedHashMap<>();
        try {
            List<Map<String, String>> questionsForCategorization = new ArrayList<>();
            if (submission.answers != null) {
                for (AnswerItem answer : submission.answers) {
                    if (answer.questionId == null) continue;
                    Optional<Question> qOpt = questionRepository.findById(answer.questionId);
                    qOpt.ifPresent(q -> {
                        Map<String, String> qInfo = new LinkedHashMap<>();
                        qInfo.put("id", String.valueOf(q.getId()));
                        qInfo.put("questionText", q.getQuestionText() != null ? q.getQuestionText() : "");
                        qInfo.put("type", q.getType() != null ? q.getType() : "MCQ");
                        questionsForCategorization.add(qInfo);
                    });
                }
            }

            if (!questionsForCategorization.isEmpty()) {
                String catRaw = claudeService.categorizeQuestions(questionsForCategorization);
                catRaw = catRaw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
                ObjectMapper catMapper = new ObjectMapper();
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

        // Build a list of per-question results including category, for the
        // frontend quizfinish page to render accurate radar/bar charts.
        // ESSAY questions now carry a real AI-assigned numeric score/feedback
        // instead of a permanent "neutral/pending" placeholder.
        List<Map<String, Object>> questionResults = new ArrayList<>();
        if (submission.answers != null) {
            for (AnswerItem answer : submission.answers) {
                if (answer.questionId == null) continue;
                Optional<Question> qOpt = questionRepository.findById(answer.questionId);
                if (qOpt.isEmpty()) continue;
                Question q = qOpt.get();
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
                    // Graded essays are no longer "neutral/pending" — they now have
                    // a real score, classified the same way as other questions so
                    // category charts and accuracy bars treat them consistently:
                    // >=75 counts as a strong (correct-leaning) result, <75 as needing work.
                    qResult.put("result", score >= 75 ? "correct" : score >= 50 ? "partial" : "wrong");
                } else {
                    boolean correct = isCorrect(q, answer.selectedAnswer);
                    qResult.put("correctAnswer", q.getCorrectAnswer());
                    qResult.put("result", correct ? "correct" : "wrong");
                }

                questionResults.add(qResult);
                Double essayScoreForPerf = qResult.containsKey("essayScore")
                        ? ((Number) qResult.get("essayScore")).doubleValue() : null;
                questionPerformanceRepository.save(new QuestionPerformance(
                        studentId, submission.topic, category,
                        (String) qResult.get("result"), essayScoreForPerf, LocalDateTime.now()));
            }
        }

        // Persist the attempt now, including the full per-question breakdown,
        // so quizfinish.html can show the real breakdown for past attempts
        // too — not just immediately after submitting.
        Attempt savedAttempt = new Attempt(
                studentId, submission.topic, submission.difficulty,
                totalItems, correctCountForStorage, precisePerformanceScore,
                nextDiff, LocalDateTime.now());
        try {
            savedAttempt.setDetails(new ObjectMapper().writeValueAsString(questionResults));
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
        result.put("aiTutor", buildTutorInsight(submission.topic, precisePerformanceScore, studentId));
        result.put("questionResults", questionResults);
        return result;
    }

    /**
     * Extracts the rubric string list from an ESSAY question's stored payload
     * JSON (shape: {"rubric": ["point1","point2",...]}). Returns an empty
     * list if the payload is missing or malformed — gradeEssayWithAi() still
     * works without a rubric by falling back to general judgment.
     */
    private List<String> extractRubric(String payloadJson) {
        List<String> rubric = new ArrayList<>();
        if (payloadJson == null || payloadJson.isBlank()) return rubric;
        try {
            JsonNode node = new ObjectMapper().readTree(payloadJson);
            JsonNode rubricNode = node.path("rubric");
            if (rubricNode.isArray()) {
                rubricNode.forEach(r -> rubric.add(r.asText("")));
            }
        } catch (Exception e) {
            System.err.println("Could not parse essay rubric payload: " + e.getMessage());
        }
        return rubric;
    }

    /**
     * Calls Claude to grade a single essay answer and parses the result into
     * a plain map: {score: Integer, feedback: String, met: List<String>, missed: List<String>}.
     * Falls back to a 0 score with an explanatory feedback message if the AI
     * call or JSON parsing fails — grading is always attempted, never skipped.
     */
    private Map<String, Object> gradeEssayWithAi(String questionText, List<String> rubric, String studentAnswer) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String raw = claudeService.gradeEssay(questionText, rubric, studentAnswer);
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = new ObjectMapper().readTree(raw);

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
     * Keeps the FirstQuizResult row in sync with submission results.
     *
     *  - First-ever GENERAL quiz for (studentId, topic) → create the row,
     *    locking generalScore. Never overwritten again.
     *  - Any later GENERAL quiz (retake) for a topic that already has a row →
     *    do nothing. generalScore stays locked.
     *  - ADAPTED quiz submission (isAdapted = true) → always update
     *    latestAdaptedScore / latestAdaptedTier to the new result, and
     *    invalidate the lesson cache so the next lesson open regenerates
     *    content via Claude at the new tier.
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
                // Edge case: adapted quiz submitted with no general result on
                // record yet. Use this score as the best-available general
                // score too, so the lesson endpoint has something to work with.
                result.setLatestAdaptedScore(score);
                result.setLatestAdaptedTier(difficulty);
            }
            firstQuizResultRepository.save(result);

            // Force the Learning Hub lesson to regenerate at the new tier.
            lessonCacheRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
            return;
        }

        // General quiz path — only ever write once.
        if (existingOpt.isEmpty()) {
            FirstQuizResult result = new FirstQuizResult(studentId, topic, score, difficulty);
            firstQuizResultRepository.save(result);
        }
        // If it already exists, leave generalScore exactly as it was.
    }

    // ── Adapted quiz ──────────────────────────────────────────────────────

    @PostMapping("/adapted")
    public Map<String, Object> generateAdaptedQuiz(
            @RequestBody AdaptedQuizRequest request,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) studentId = "demo";

        String topic = request.topic;

        // Get best score for this student on this topic
        Double bestScore = attemptRepository.findBestScoreByStudentIdAndTopic(studentId, topic);
        double score = bestScore != null ? bestScore : 0.0;

        // Find the uploaded material text for this topic
        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId);
        Material material = materials.stream()
                .filter(m -> m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);

        if (material == null) {
            return Map.of("success", false, "message",
                    "No uploaded material found for topic: " + topic + ". Please upload a handout first.");
        }

        // Re-read the file to get extracted text
        String text = readMaterialText(material);
        if (text.isBlank()) {
            return Map.of("success", false, "message",
                    "Could not read material text. The file may be a scanned PDF or unsupported format.");
        }

        // Determine target difficulty label for response
        String targetDifficulty = DifficultyTier.fromScore(score);

        // Clear old questions and generate new adapted ones (mixed types, same parser as upload)
        clearQuestionsForTopic(studentId, topic);
        int generated = 0;
        try {
            String raw = claudeService.generateAdaptedQuestions(topic, text, score);
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode array = mapper.readTree(raw);
            QuestionParser.ParseResult parsed = QuestionParser.parse(
                    array, text, studentId, topic, targetDifficulty, "ADAPTED QUIZ DROPPED: ");
            questionRepository.saveAll(parsed.questions);
            generated = parsed.questions.size();
            generated += materialController.appendDiagramQuestionIfEligible(studentId, material, topic, targetDifficulty, targetDifficulty);
        } catch (Exception e) {
            System.err.println("Adapted quiz generation failed: " + e.getMessage());
            return Map.of("success", false, "message", "AI question generation failed: " + e.getMessage());
        }

        if (generated == 0) {
            return Map.of("success", false, "message", "AI could not generate questions. Try again.");
        }

        return Map.of(
                "success", true,
                "message", "Generated " + generated + " adapted questions at " + targetDifficulty + " difficulty based on your best score of " + Math.round(score) + "%.",
                "difficulty", targetDifficulty,
                "bestScore", Math.round(score),
                "quizUrl", "/quizpage.html?topic=" + java.net.URLEncoder.encode(topic, java.nio.charset.StandardCharsets.UTF_8) + "&difficulty=" + targetDifficulty
        );
    }

    // ── Targeted ("Target Problems") quiz ───────────────────────────────────

    @PostMapping("/targeted")
    public Map<String, Object> generateTargetedQuiz(
            @RequestBody TargetedQuizRequest request,
            HttpSession session) {

        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) studentId = "demo";

        String topic = request.topic;
        if (topic == null || topic.isBlank()) {
            return Map.of("success", false, "message", "Topic is required.");
        }

        // Find the uploaded material text for this topic (same approach as adapted quiz)
        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId);
        Material material = materials.stream()
                .filter(m -> m.getTopic().equalsIgnoreCase(topic))
                .findFirst()
                .orElse(null);

        if (material == null) {
            return Map.of("success", false, "message",
                    "No uploaded material found for topic: " + topic + ". Please upload a handout first.");
        }

        String text = readMaterialText(material);
        if (text.isBlank()) {
            return Map.of("success", false, "message",
                    "Could not read material text. The file may be a scanned PDF or unsupported format.");
        }

        // Determine the student's average score on this topic
        List<Attempt> topicAttempts = attemptRepository.findByStudentIdOrderByTimestampDesc(studentId).stream()
                .filter(a -> topic.equalsIgnoreCase(a.getTopic()))
                .toList();
        double avgScore = topicAttempts.isEmpty()
                ? -1.0
                : topicAttempts.stream().mapToDouble(Attempt::getPerformanceScore).average().orElse(-1.0);

        // weakConcepts is intentionally empty — the PRIMARY driver of question
        // generation below is the student's ACTUAL wrong answers (gathered just
        // below), since real, documented mistakes are far more useful than a
        // generic "weak key term" guess. ClaudeService.generateTargetedQuestions
        // already handles an empty weakConcepts list gracefully (MODE B fallback).
        List<String> weakConcepts = List.of();

        // Pull the student's ACTUAL wrong/partial answers from their recent attempts
        // on this topic. This is what makes Target Problems genuinely adaptive: Claude
        // is shown the real question, the exact wrong answer the student gave (or, for
        // essays, what their written answer missed), and is asked to generate a brand
        // new question that re-teaches and re-tests that exact concept — not just
        // another question loosely related to a generic "weak" key term.
        List<Map<String, String>> wrongAnswers = gatherWrongQuestionDetails(studentId, topic);

        // Generate the targeted questions (mixed types, same parser as upload)
        clearQuestionsForTopic(studentId, topic);
        int generated = 0;
        try {
            String raw = claudeService.generateTargetedQuestions(topic, text, weakConcepts, wrongAnswers, avgScore);
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode array = mapper.readTree(raw);
            QuestionParser.ParseResult parsed = QuestionParser.parse(
                    array, text, studentId, topic, "Targeted", "TARGETED QUIZ DROPPED: ");
            questionRepository.saveAll(parsed.questions);
            generated = parsed.questions.size();
            String diagramTier = DifficultyTier.fromScore(avgScore);
            generated += materialController.appendDiagramQuestionIfEligible(studentId, material, topic, diagramTier, "Targeted");
        } catch (Exception e) {
            System.err.println("Targeted quiz generation failed: " + e.getMessage());
            return Map.of("success", false, "message", "AI question generation failed: " + e.getMessage());
        }

        if (generated == 0) {
            return Map.of("success", false, "message", "AI could not generate targeted questions. Try again.");
        }

        String message = wrongAnswers.isEmpty()
                ? "Generated " + generated + " targeted questions focused on your weak areas in " + topic + "."
                : "Generated " + generated + " targeted questions built directly from " + wrongAnswers.size()
                  + " of your actual wrong answers in " + topic + ".";

        return Map.of(
                "success", true,
                "message", message,
                "weakConcepts", weakConcepts,
                "mistakesTargeted", wrongAnswers.size(),
                "quizUrl", "/quizpage.html?topic=" + java.net.URLEncoder.encode(topic, java.nio.charset.StandardCharsets.UTF_8) + "&difficulty=Targeted"
        );
    }

    /**
     * Pulls the student's ACTUAL wrong/partial answers from their recent
     * attempts on this topic, so the Target Problems quiz can be built
     * around their real, documented mistakes instead of a generic "weak
     * concept" guess. Reads from Attempt.details — the full per-question
     * breakdown already saved on every /api/quiz/submit call — since that's
     * the only place the original question text and the student's actual
     * wrong answer live together.
     *
     * Scans the student's most recent attempts on this topic (newest first,
     * across every difficulty including past Targeted quizzes, since a
     * repeated miss on a re-teaching question is still a real signal worth
     * targeting again), collecting every question marked "wrong" or
     * "partial". Deduplicates by question text so a concept missed across
     * multiple attempts is only sent to Claude once, using the most recent
     * miss. Capped at 15 entries to keep the prompt a reasonable size.
     */
    private List<Map<String, String>> gatherWrongQuestionDetails(String studentId, String topic) {
        List<Map<String, String>> wrongDetails = new ArrayList<>();
        Set<String> seenQuestions = new LinkedHashSet<>();

        List<Attempt> attempts = attemptRepository.findByStudentIdOrderByTimestampDesc(studentId).stream()
                .filter(a -> topic.equalsIgnoreCase(a.getTopic()))
                .limit(5)
                .toList();

        ObjectMapper mapper = new ObjectMapper();

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

                    // Essays have no single "correct answer" — the AI's own rubric
                    // feedback on what was missed is the richest signal of the gap.
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

    // ── Helpers ───────────────────────────────────────────────────────────

    private String readMaterialText(Material material) {
        try {
            Path filePath = java.nio.file.Paths.get("uploads", "materials", material.getStoredFilename());
            String name = material.getOriginalFilename().toLowerCase(Locale.ROOT);
            if (name.endsWith(".txt") || name.endsWith(".csv")) {
                return java.nio.file.Files.readString(filePath, java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ").trim();
            } else if (name.endsWith(".pdf")) {
                try (org.apache.pdfbox.pdmodel.PDDocument doc =
                             org.apache.pdfbox.pdmodel.PDDocument.load(filePath.toFile())) {
                    doc.setAllSecurityToBeRemoved(true);  // ← add this line
                    org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                    stripper.setSortByPosition(true);
                    return stripper.getText(doc).replaceAll("\\s+", " ").trim();
                }
            }
            return "";
        } catch (Exception e) {
            System.err.println("Could not read material file: " + e.getMessage());
            return "";
        }
    }

    private void clearQuestionsForTopic(String ownerId, String topic) {
        questionRepository.deleteByOwnerAndTopicIgnoreCase(ownerId, topic);
    }

    private Map<String, Object> questionToMap(Question q) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", q.getId());
        item.put("topic", q.getTopic());
        item.put("difficulty", q.getDifficulty());
        item.put("type", q.getType() != null ? q.getType() : "MCQ");
        item.put("payload", q.getPayload());
        item.put("questionText", q.getQuestionText());
        item.put("question", q.getQuestionText());
        item.put("optionA", q.getOptionA());
        item.put("optionB", q.getOptionB());
        item.put("optionC", q.getOptionC());
        item.put("optionD", q.getOptionD());
        item.put("correctAnswer", q.getCorrectAnswer());
        item.put("hint", q.getHint() != null ? q.getHint() : "Review the uploaded handout carefully.");
        item.put("explanation", q.getExplanation() != null ? q.getExplanation() : "Based on the uploaded material.");
        return item;
    }

    /**
     * Determines the "correct"/"wrong" label shown for a single question
     * (the per-question result in questionResults, and the legacy
     * correctCount path for MCQ/TRUEFALSE/CONCEPTID).
     *
     * For single-answer types (MCQ, TRUEFALSE, CONCEPTID) {@link #gradeFraction}
     * is always exactly 0.0 or 1.0, so this remains an exact match.
     *
     * For multi-part structured types (MATCHING, FILLBLANK, DIAGRAM, SORTING) —
     * which can earn any fraction between 0.0 and 1.0 depending on how many
     * parts were right — this applies a majority-rule threshold: getting
     * MORE THAN HALF of the parts correct labels the question "correct";
     * getting half or fewer labels it "wrong". This is a display-only
     * simplification — the actual quiz score still uses the exact fraction
     * (see structuredCreditTotal in submitQuiz), so a question with 3 of 4
     * matching pairs right contributes 0.75 to the score even though it's
     * shown here as a flat "correct", not "75% correct".
     */
    private boolean isCorrect(Question question, Object selectedAnswerObj) {
        return gradeFraction(question, selectedAnswerObj) > 0.5;
    }

    /**
     * Grades an answer and returns the credit it earns as a fraction from
     * 0.0 (fully wrong) to 1.0 (fully correct).
     *
     * MCQ / TRUEFALSE / CONCEPTID remain strictly binary (1.0 or 0.0) — there
     * is exactly one selectable option, so partial credit doesn't apply.
     *
     * MATCHING, FILLBLANK/DIAGRAM, and SORTING are multi-part answers (several
     * pairs / blanks / sorted items within ONE question). These now award
     * proportional credit — e.g. getting 3 of 4 matching pairs right earns
     * 0.75 instead of being marked entirely wrong — rather than the previous
     * all-or-nothing rule where a single missed part zeroed the whole question.
     */
    private double gradeFraction(Question question, Object selectedAnswerObj) {
        if (question == null || selectedAnswerObj == null) return 0.0;

        String type = question.getType() != null ? question.getType().toUpperCase(Locale.ROOT) : "MCQ";

        // Structured types (MATCHING/FILLBLANK/DIAGRAM/SORTING) send an array
        // of objects from the frontend, not a plain string, and their "correct
        // answer" lives inside the question's payload JSON rather than in
        // question.getCorrectAnswer() (which is only ever populated for
        // MCQ/TRUEFALSE). These used to always be marked wrong because the old
        // code bailed out early with `!(selectedAnswerObj instanceof String)`
        // and `question.getCorrectAnswer() == null` checks. Route each type to
        // its own payload-aware evaluator, mirroring quizpage.html's evaluate().
        switch (type) {
            case "MATCHING":
                return matchingFraction(question, selectedAnswerObj);
            case "FILLBLANK":
            case "DIAGRAM":
                return fillBlankOrDiagramFraction(selectedAnswerObj);
            case "SORTING":
                return sortingFraction(question, selectedAnswerObj);
            case "CONCEPTID":
                return isCorrectConceptId(question, selectedAnswerObj) ? 1.0 : 0.0;
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
                return new ObjectMapper().createObjectNode();
            }
            return new ObjectMapper().readTree(question.getPayload());
        } catch (Exception e) {
            return new ObjectMapper().createObjectNode();
        }
    }

    /**
     * MATCHING credit is proportional: each pair in payload.correctPairs that
     * is present in the student's submitted pairs earns 1/N of the question's
     * credit, where N is the total number of correct pairs. E.g. matching 3
     * of 4 pairs correctly now earns 0.75 instead of the whole question being
     * marked wrong for missing just one pair. selectedAnswer arrives as a
     * JSON array of {"left": <int>, "right": <int>} objects (see
     * quizpage.html getAnswer()).
     */
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
     * FILLBLANK/DIAGRAM credit is proportional across blanks: each submitted
     * blank whose "filled" text matches its "answer" text (case-insensitive,
     * trimmed) earns 1/N of the question's credit, where N is the total
     * number of blanks. E.g. filling in 2 of 3 blanks correctly now earns
     * ~0.67 instead of the whole question being marked wrong for missing
     * just one blank. selectedAnswer arrives as a JSON array of
     * {"id", "filled", "answer"} objects.
     */
    private double fillBlankOrDiagramFraction(Object selectedAnswerObj) {
        try {
            JsonNode answerNode = toJsonNode(selectedAnswerObj);
            if (!answerNode.isArray() || answerNode.size() == 0) return 0.0;

            int total = answerNode.size();
            int correctCount = 0;
            for (JsonNode a : answerNode) {
                String filled = a.path("filled").asText("").trim();
                String correctText = a.path("answer").asText("").trim();
                if (filled.equalsIgnoreCase(correctText)) correctCount++;
            }
            return total == 0 ? 0.0 : (double) correctCount / total;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * SORTING credit is proportional across items: each item from
     * payload.items whose submitted assignment matches its correctCategory
     * earns 1/N of the question's credit, where N is the total number of
     * items. E.g. sorting 4 of 6 items correctly now earns ~0.67 instead of
     * the whole question being marked wrong for missing two. selectedAnswer
     * arrives as a JSON array of {"text", "assigned", "correct"} objects —
     * but we re-verify against the question's own payload rather than
     * trusting the "correct" field the client sent. Items the student left
     * unassigned (not present in the submitted array, or with no matching
     * text) simply earn no credit for that item rather than failing the
     * whole question.
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
     * CONCEPTID's correct answer lives in payload.correctAnswer (the legacy
     * question.getCorrectAnswer() field is never populated for this type).
     * selectedAnswer arrives as a plain string.
     */
    private boolean isCorrectConceptId(Question question, Object selectedAnswerObj) {
        if (!(selectedAnswerObj instanceof String selected)) return false;
        JsonNode payload = parsePayload(question);
        String correctAnswer = payload.path("correctAnswer").asText("");
        if (correctAnswer.isBlank()) return false;
        return selected.trim().equalsIgnoreCase(correctAnswer.trim());
    }

    /**
     * Converts a selectedAnswer Object (deserialized by Jackson from JSON —
     * typically a List<Map> for structured types) back into a JsonNode so the
     * structured evaluators above can use consistent .path()/.isArray() access
     * regardless of whether Jackson handed us a List, Map, or already a JsonNode.
     */
    private JsonNode toJsonNode(Object value) {
        ObjectMapper m = new ObjectMapper();
        if (value instanceof JsonNode node) return node;
        return m.valueToTree(value);
    }

    /**
     * Renders the student's raw selectedAnswer object into a readable string for
     * quizfinish.html's "Your answer:" line. MCQ/TRUEFALSE/ESSAY/CONCEPTID answers
     * are already plain strings. MATCHING, SORTING, FILLBLANK, and DIAGRAM answers
     * arrive as a JSON array of objects (deserialized into a List<Map> by Jackson) —
     * Java's default toString() on that produces an unreadable dump like
     * "[{text=Keyboard, assigned=A, correct=A}, ...]", so each structured type gets
     * its own human-readable rendering here instead.
     */
    private String formatYourAnswer(Question question, Object selectedAnswerObj) {
        if (selectedAnswerObj == null) return "No answer given";

        String type = question.getType() != null ? question.getType().toUpperCase(Locale.ROOT) : "MCQ";

        switch (type) {
            case "MATCHING":
                return formatMatchingAnswer(question, toJsonNode(selectedAnswerObj));
            case "SORTING":
                return formatSortingAnswer(question, toJsonNode(selectedAnswerObj));
            case "FILLBLANK":
            case "DIAGRAM":
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

    private Map<String, Object> buildTutorInsight(String topic, double score, String studentId) {
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

    // ── Test all question types (debug) ───────────────────────────────────

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

        // Generate one of each type
        String raw;
        try {
            raw = claudeService.generateTestAllTypesQuiz(topic, text);
            System.out.println("=== TEST-TYPES RAW RESPONSE ===");
            System.out.println(raw);
            System.out.println("=== END TEST-TYPES RESPONSE ===");
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Claude call failed: " + e.getMessage()));
        }

        raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

        List<Map<String, Object>> parsed = new ArrayList<>();
        List<Question> saved = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        try {
            JsonNode array =
                    new ObjectMapper().readTree(raw);

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

            // If this material has an extracted diagram image, generate a DIAGRAM question via vision
            Material mat = materials.stream()
                    .filter(m -> m.getTopic().equalsIgnoreCase(topic))
                    .findFirst().orElse(null);
            if (mat != null && mat.getDiagramImageFilename() != null) {
                String imageBase64 = materialController.readDiagramImageBase64(mat.getDiagramImageFilename());
                if (imageBase64 != null) {
                    try {
                        String diagramRaw = claudeService.generateDiagramQuestion(topic, imageBase64);
                        diagramRaw = diagramRaw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
                        JsonNode dNode = new ObjectMapper().readTree(diagramRaw);
                        Question dq = new Question();
                        dq.setOwnerId(studentId);
                        dq.setTopic(topic);
                        dq.setDifficulty("Test");
                        dq.setType("DIAGRAM");
                        dq.setQuestionText(dNode.path("questionText").asText("Label the parts of the diagram."));
                        dq.setHint(dNode.path("hint").asText(""));
                        dq.setExplanation(dNode.path("explanation").asText(""));
                        // Build payload: labels + imageFilename so frontend can render the image
                        ObjectNode payloadNode = new ObjectMapper().createObjectNode();
                        payloadNode.set("labels", dNode.path("labels"));
                        payloadNode.put("imageFilename", mat.getDiagramImageFilename());
                        payloadNode.put("mode", "typed");
                        dq.setPayload(payloadNode.toString());
                        if (!dq.getQuestionText().isBlank()) saved.add(dq);
                    } catch (Exception e) {
                        System.err.println("Diagram vision question generation failed: " + e.getMessage());
                    }
                }
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

    // ── Request classes ───────────────────────────────────────────────────

    public static class QuizSubmission {
        public String topic;
        public String difficulty;
        public List<AnswerItem> answers;
        public Boolean isAdapted;
    }

    public static class AnswerItem {
        public Long questionId;
        // NOTE: was previously typed as String. MATCHING/FILLBLANK/SORTING/DIAGRAM
        // questions send an array of objects here (e.g. [{"left":0,"right":2}]),
        // not a plain string. With a String field, Jackson threw a deserialization
        // exception on submit for any non-MCQ/TRUEFALSE question, which made the
        // ENTIRE /api/quiz/submit request fail — meaning the attempt was never
        // saved, even though the frontend still rendered a "fake" local results
        // screen from in-browser data. That's why scores never showed up on the
        // Quiz Hub / Dashboard / Reports pages despite the quiz "completing".
        public Object selectedAnswer;
    }

    public static class AdaptedQuizRequest {
        public String topic;
    }

    public static class TargetedQuizRequest {
        public String topic;
    }
}