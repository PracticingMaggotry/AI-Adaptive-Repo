package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;

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
            String optA = opts.has(0) ? opts.get(0).asText("") : "";
            String optB = opts.has(1) ? opts.get(1).asText("") : "";
            String optC = opts.has(2) ? opts.get(2).asText("") : "";
            String optD = opts.has(3) ? opts.get(3).asText("") : "";
            q.setOptionA(stripLeadingChoiceLabel(optA));
            q.setOptionB(stripLeadingChoiceLabel(optB));
            q.setOptionC(stripLeadingChoiceLabel(optC));
            q.setOptionD(stripLeadingChoiceLabel(optD));

            // correctAnswer is documented as "full text of correct option" — if
            // Claude echoed it back with the same letter prefix it put on the
            // option itself (e.g. "B. Web server controls"), strip it the same
            // way so it still matches the now-stripped option text exactly.
            // Without this, a correctAnswer that still carried its prefix would
            // never match any of the cleaned optionA-D strings, breaking grading
            // and the "this is the correct option" highlight after submission.
            q.setCorrectAnswer(stripLeadingChoiceLabel(payload.path("correctAnswer").asText("")));
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

    /**
     * Strips a leading single-letter choice label from MCQ option text, e.g.
     * "A. HTML server controls" -> "HTML server controls",
     * "B) Web server controls"  -> "Web server controls",
     * "(C) Validation controls" -> "Validation controls",
     * "D: User controls"        -> "User controls".
     *
     * WHY THIS EXISTS: the prompt sent to Claude for every MCQ-generating
     * method (generateMixedQuestions, generateAdaptedQuestions,
     * generateTargetedQuestions, generateTestAllTypesQuiz) asks for
     * payload.options as plain option text, but in practice the model very
     * often echoes back each option already prefixed with its own letter
     * (e.g. "A. ...", "B. ...") — a common LLM habit when asked to produce
     * multiple-choice content, regardless of the prompt wording.
     *
     * The frontend (quizpage.html buildMCQ) ALWAYS renders its own A/B/C/D
     * circular badge next to each option's text, independent of whatever
     * Claude returned. If the option text itself still starts with "A. "
     * etc., the student sees the badge and the embedded label side by side
     * — visually doubled choice labels, e.g. a circled "B" next to text
     * that itself begins with "B. Web server controls".
     *
     * Stripping here, once, at the point every MCQ option is first stored,
     * fixes this for every caller (initial upload, Adapted Quiz, Targeted
     * Quiz, Test-Types) without needing a matching fix in each frontend
     * page that renders MCQ options.
     *
     * Deliberately conservative: only strips a SINGLE leading letter (A-D,
     * case-insensitive) followed by ". " / ") " / ": ' or a parenthesized
     * "(A) " form, and only when it appears at the very start of the
     * string. This avoids accidentally eating real option text that simply
     * happens to start with a capital letter followed by a period (e.g. an
     * abbreviation), since genuine MCQ choice prefixes are always exactly
     * one of these few punctuation patterns.
     */
    private static final java.util.regex.Pattern CHOICE_LABEL_PATTERN =
            java.util.regex.Pattern.compile("^\\(?([A-Da-d])[\\.\\):]\\)?\\s+");

    static String stripLeadingChoiceLabel(String optionText) {
        if (optionText == null) return "";
        java.util.regex.Matcher m = CHOICE_LABEL_PATTERN.matcher(optionText.trim());
        return m.find() ? optionText.trim().substring(m.end()) : optionText;
    }
}