package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Validates a single question node returned by Claude before it is saved.
 * Returns null if valid, or a rejection reason string if it should be dropped.
 */
public class QuestionValidator {

    /**
     * @param node         the parsed JSON object for one question
     * @param sourceText   the full extracted handout text (for excerpt checks)
     * @return null if the question is acceptable, or a reason string to log and skip it
     */
    public static String validate(JsonNode node, String sourceText) {
        String type = node.path("type").asText("MCQ").toUpperCase();
        String questionText = node.path("questionText").asText("").trim();

        if (questionText.isBlank()) return "Empty questionText";

        JsonNode payload = node.path("payload");
        if (payload.isMissingNode() || payload.isNull()) {
            // MCQ and TRUEFALSE can tolerate missing payload if legacy fields exist
            if (type.equals("MCQ") || type.equals("TRUEFALSE")) return null;
            return "Missing payload for type " + type;
        }

        return switch (type) {
            case "MCQ" -> validateMcq(payload);
            case "TRUEFALSE" -> validateTrueFalse(payload);
            case "MATCHING" -> validateMatching(payload);
            case "FILLBLANK" -> validateExcerpt(payload, sourceText, type);
            case "DIAGRAM" -> validateExcerpt(payload, sourceText, type);
            case "ESSAY" -> validateEssay(payload);
            case "SORTING" -> validateSorting(payload);
            case "CONCEPTID" -> validateConceptId(payload);
            default -> "Unknown type: " + type;
        };
    }

    private static String validateMcq(JsonNode payload) {
        JsonNode options = payload.path("options");
        if (!options.isArray() || options.size() < 2) return "MCQ needs at least 2 options";
        if (payload.path("correctAnswer").asText("").isBlank()) return "MCQ missing correctAnswer";
        return null;
    }

    private static String validateTrueFalse(JsonNode payload) {
        String ans = payload.path("correctAnswer").asText("").trim();
        if (!ans.equalsIgnoreCase("True") && !ans.equalsIgnoreCase("False"))
            return "TRUEFALSE correctAnswer must be 'True' or 'False', got: " + ans;
        return null;
    }

    private static String validateMatching(JsonNode payload) {
        JsonNode left  = payload.path("leftItems");
        JsonNode right = payload.path("rightItems");
        JsonNode pairs = payload.path("correctPairs");
        if (!left.isArray() || left.size() < 2)  return "MATCHING needs at least 2 leftItems";
        if (!right.isArray() || right.size() < 2) return "MATCHING needs at least 2 rightItems";
        if (!pairs.isArray() || pairs.size() < 2) return "MATCHING needs at least 2 correctPairs";
        return null;
    }

    private static String validateExcerpt(JsonNode payload, String sourceText, String type) {
        if (type.equals("DIAGRAM")) {
            // DIAGRAM uses svgContent not excerpt — just check labels exist
            JsonNode labels = payload.path("labels");
            if (!labels.isArray() || labels.size() == 0) return "DIAGRAM missing labels array";
            return null;
        }

        // FILLBLANK
        String excerpt = payload.path("excerpt").asText("").trim();
        if (excerpt.isBlank()) return type + " missing excerpt";

        // Build a searchable version: replace {{N}} placeholders with a wildcard gap
        // then check each fragment between placeholders exists in the source
        String[] parts = excerpt.split("\\{\\{\\d+}}");
        String normalizedSource = sourceText == null ? "" : sourceText.replaceAll("\\s+", " ").toLowerCase();

        for (String part : parts) {
            String fragment = part.replaceAll("\\s+", " ").trim().toLowerCase();
            if (fragment.length() > 8 && !normalizedSource.contains(fragment)) {
                return type + " excerpt not found verbatim in source material (likely fabricated): \""
                        + excerpt.substring(0, Math.min(80, excerpt.length())) + "\"";
            }
        }

        JsonNode blanks = payload.path("blanks");
        if (!blanks.isArray() || blanks.size() == 0) return "FILLBLANK missing blanks array";
        return null;
    }

    private static String validateEssay(JsonNode payload) {
        JsonNode rubric = payload.path("rubric");
        if (!rubric.isArray() || rubric.size() == 0) return "ESSAY missing rubric points";
        return null;
    }

    private static String validateSorting(JsonNode payload) {
        if (payload.path("categoryA").asText("").isBlank()) return "SORTING missing categoryA";
        if (payload.path("categoryB").asText("").isBlank()) return "SORTING missing categoryB";
        JsonNode items = payload.path("items");
        if (!items.isArray() || items.size() < 2) return "SORTING needs at least 2 items";
        return null;
    }

    private static String validateConceptId(JsonNode payload) {
        JsonNode clues = payload.path("clues");
        if (!clues.isArray() || clues.size() < 2) return "CONCEPTID needs at least 2 clues";
        if (payload.path("correctAnswer").asText("").isBlank()) return "CONCEPTID missing correctAnswer";
        return null;
    }
}