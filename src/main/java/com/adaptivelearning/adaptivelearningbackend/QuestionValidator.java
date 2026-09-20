package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;

/** Validates a single question node returned by Claude before it is saved. */
public class QuestionValidator {

    /** Returns null if the question is valid, or a rejection reason string if it should be dropped. */
    public static String validate(JsonNode node, String sourceText) {
        String type = node.path("type").asText("MCQ").toUpperCase();
        String questionText = node.path("questionText").asText("").trim();

        if (questionText.isBlank()) return "Empty questionText";

        JsonNode payload = node.path("payload");
        if (payload.isMissingNode() || payload.isNull()) {
            if (type.equals("MCQ") || type.equals("TRUEFALSE")) return null;
            return "Missing payload for type " + type;
        }

        return switch (type) {
            case "MCQ" -> validateMcq(payload);
            case "TRUEFALSE" -> validateTrueFalse(payload);
            case "MATCHING" -> validateMatching(payload);
            case "FILLBLANK" -> validateExcerpt(payload, sourceText, type);
            case "ESSAY" -> validateEssay(payload);
            case "SORTING" -> validateSorting(payload);
            case "CONCEPTID" -> validateConceptId(payload);
            case "PRACTICAL" -> validatePractical(payload);
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

    /** Verifies FILLBLANK excerpts appear verbatim in the source text. */
    private static String validateExcerpt(JsonNode payload, String sourceText, String type) {
        String excerpt = payload.path("excerpt").asText("").trim();
        if (excerpt.isBlank()) return type + " missing excerpt";

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

    /** PRACTICAL is MCQ-shaped, but stricter: it can't be graded unless the key resolves to exactly one option. */
    private static String validatePractical(JsonNode payload) {
        JsonNode options = payload.path("options");
        if (!options.isArray() || options.size() != 4) return "PRACTICAL needs exactly 4 options";

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode option : options) {
            String text = QuestionParser.normalizePracticalOption(option.asText(""));
            if (text.isBlank()) return "PRACTICAL has a blank option";
            // Grading is case-insensitive, so options that differ only by case would both count as correct.
            if (!seen.add(text.toLowerCase(java.util.Locale.ROOT))) return "PRACTICAL has duplicate options";
        }

        if (QuestionParser.practicalCorrectIndex(payload) < 0)
            return "PRACTICAL correctAnswer does not match exactly one option";

        String kind = payload.path("kind").asText("").trim().toUpperCase(java.util.Locale.ROOT);
        if (!kind.isEmpty() && !kind.equals("SOLVE") && !kind.equals("OUTPUT") && !kind.equals("ERROR_SPOT"))
            return "PRACTICAL has unknown kind: " + kind;
        return null;
    }

    private static String validateConceptId(JsonNode payload) {
        JsonNode clues = payload.path("clues");
        if (!clues.isArray() || clues.size() < 2) return "CONCEPTID needs at least 2 clues";
        if (payload.path("correctAnswer").asText("").isBlank()) return "CONCEPTID missing correctAnswer";
        return null;
    }
}