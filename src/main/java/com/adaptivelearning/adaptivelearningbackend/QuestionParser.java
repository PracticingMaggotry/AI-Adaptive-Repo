package com.adaptivelearning.adaptivelearningbackend;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for turning a Claude-generated JSON array of question objects
 * into saveable Question entities.
 *
 * This logic used to be copy-pasted (with tiny variations) in four places:
 * MaterialController.generateQuestionsWithClaude, QuizController.generateAdaptedQuiz,
 * QuizController.generateTargetedQuiz, and QuizController.testAllTypes. Centralizing
 * it here means a new question type, or a change to how the legacy MCQ/TRUEFALSE
 * flat fields get populated, only has to happen in one place.
 */
public class QuestionParser {

    /**
     * Parsed questions plus any rejection reasons, for callers that want
     * diagnostics (e.g. the test-types debug endpoint shows dropped reasons).
     */
    public static class ParseResult {
        public final List<Question> questions = new ArrayList<>();
        public final List<String> droppedReasons = new ArrayList<>();
    }

    /**
     * Parses a Claude-generated JSON array into Question entities ready to save.
     * Each node is run through QuestionValidator first; invalid or fabricated
     * questions are dropped (and logged) rather than saved.
     *
     * @param array         parsed JSON array from Claude's response — a non-array node yields an empty result
     * @param sourceText    the handout text used for excerpt verification
     * @param ownerId       student/owner id to stamp on every question
     * @param topic         topic name to stamp on every question
     * @param difficulty    difficulty/tier label to stamp on every question
     * @param dropLogPrefix text prepended to the console log line when a question is dropped,
     *                      e.g. "ADAPTED QUIZ DROPPED: " — kept caller-specific so existing log
     *                      output doesn't change for anyone grepping logs
     */
    public static ParseResult parse(JsonNode array, String sourceText, String ownerId,
                                    String topic, String difficulty, String dropLogPrefix) {
        ParseResult result = new ParseResult();
        if (array == null || !array.isArray()) return result;

        for (JsonNode node : array) {
            String rejection = QuestionValidator.validate(node, sourceText);
            if (rejection != null) {
                result.droppedReasons.add(rejection);
                System.out.println(dropLogPrefix + rejection);
                continue;
            }

            Question q = buildQuestion(node, ownerId, topic, difficulty);
            if (!q.getQuestionText().isBlank()) {
                result.questions.add(q);
            }
        }
        return result;
    }

    /**
     * Builds a single Question entity from one Claude-generated JSON node.
     * Populates the legacy flat MCQ/TRUEFALSE fields (the quiz page still
     * reads them) and always stores the full payload JSON for every type.
     */
    public static Question buildQuestion(JsonNode node, String ownerId, String topic, String difficulty) {
        String type = node.path("type").asText("MCQ").toUpperCase();
        JsonNode payload = node.path("payload");

        Question q = new Question();
        q.setOwnerId(ownerId);
        q.setTopic(topic);
        q.setDifficulty(difficulty);
        q.setType(type);
        q.setQuestionText(node.path("questionText").asText(""));
        q.setHint(node.path("hint").asText(""));
        q.setExplanation(node.path("explanation").asText(""));

        // For MCQ/TRUEFALSE keep the flat fields populated (quiz page still reads them)
        if (type.equals("MCQ")) {
            JsonNode opts = payload.path("options");
            q.setOptionA(opts.has(0) ? opts.get(0).asText("") : "");
            q.setOptionB(opts.has(1) ? opts.get(1).asText("") : "");
            q.setOptionC(opts.has(2) ? opts.get(2).asText("") : "");
            q.setOptionD(opts.has(3) ? opts.get(3).asText("") : "");
            q.setCorrectAnswer(payload.path("correctAnswer").asText(""));
        } else if (type.equals("TRUEFALSE")) {
            q.setOptionA("True");
            q.setOptionB("False");
            q.setCorrectAnswer(payload.path("correctAnswer").asText(""));
        }

        // Store full payload JSON for all types
        if (!payload.isMissingNode() && !payload.isNull()) {
            q.setPayload(payload.toString());
        }

        return q;
    }
}