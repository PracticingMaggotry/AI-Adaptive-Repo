package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Converts a Claude-generated JSON array of question objects into saveable Question entities. */
public class QuestionParser {

    /** Parsed questions plus any rejection reasons. */
    public static class ParseResult {
        public final List<Question> questions = new ArrayList<>();
        public final List<String> droppedReasons = new ArrayList<>();
    }

    /** Parses a Claude-generated JSON array into Question entities, dropping any that fail validation. */
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

    /** Builds a single Question entity from one Claude-generated JSON node. */
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
            q.setCorrectAnswer(stripLeadingChoiceLabel(payload.path("correctAnswer").asText("")));
        } else if (type.equals("TRUEFALSE")) {
            q.setOptionA("True");
            q.setOptionB("False");
            q.setCorrectAnswer(payload.path("correctAnswer").asText(""));
        }

        if (!payload.isMissingNode() && !payload.isNull()) {
            q.setPayload(payload.toString());
        }

        return q;
    }

    /** Strips a leading single-letter choice label (e.g. "A. ", "B) ", "(C) ") from MCQ option text. */
    private static final java.util.regex.Pattern CHOICE_LABEL_PATTERN =
            java.util.regex.Pattern.compile("^\\(?([A-Da-d])[\\.\\):]\\)?\\s+");

    static String stripLeadingChoiceLabel(String optionText) {
        if (optionText == null) return "";
        java.util.regex.Matcher m = CHOICE_LABEL_PATTERN.matcher(optionText.trim());
        return m.find() ? optionText.trim().substring(m.end()) : optionText;
    }
}