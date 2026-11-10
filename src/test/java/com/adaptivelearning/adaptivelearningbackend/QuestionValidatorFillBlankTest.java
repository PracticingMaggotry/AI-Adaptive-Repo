package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuestionValidatorFillBlankTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = """
            Percentage change = ((new value - original value) / original value) x 100%
            Percentage change uses the original value as its reference quantity.
            A discount reduces an original price by a percentage of that original price.
            """;

    private static JsonNode fillBlank(String excerpt, String blanksJson) throws Exception {
        return fillBlank(excerpt, blanksJson, "typed");
    }

    private static JsonNode fillBlank(String excerpt, String blanksJson, String mode) throws Exception {
        String json = "{\"type\":\"FILLBLANK\",\"questionText\":\"Complete the sentence.\",\"hint\":\"h\","
                + "\"explanation\":\"e\",\"payload\":{\"excerpt\":" + MAPPER.writeValueAsString(excerpt)
                + ",\"blanks\":" + blanksJson + ",\"mode\":\"" + mode + "\"}}";
        return MAPPER.readTree(json);
    }

    /**
     * Fill-in-the-blank is always type-the-answer, so the same answer in two blanks is valid recall (typing both parts
     * of a formula), whatever mode the model wrote.
     */
    @Test
    void acceptsTheSameAnswerInTwoBlanks() throws Exception {
        String excerpt = "Percentage change = ((new value - {{1}}) / {{2}}) x 100%";
        String blanks = "[{\"id\":1,\"answer\":\"original value\"},{\"id\":2,\"answer\":\"original value\"}]";
        assertNull(QuestionValidator.validate(fillBlank(excerpt, blanks), SOURCE));
        assertNull(QuestionValidator.validate(fillBlank(excerpt, blanks, "dragdrop"), SOURCE));
        assertNull(QuestionValidator.validate(fillBlank(excerpt, blanks, ""), SOURCE));
    }

    @Test
    void stillRejectsVisibleAnswersAndMismatchedBlanks() throws Exception {
        JsonNode leak = fillBlank("Percentage change uses the original value as its {{1}} quantity, the original value.",
                "[{\"id\":1,\"answer\":\"original value\"}]", "typed");
        assertNotNull(QuestionValidator.validate(leak, SOURCE.replace("reference quantity.",
                "reference quantity, the original value.")));

        JsonNode mismatch = fillBlank("Percentage change = ((new value - {{1}}) / {{2}}) x 100%",
                "[{\"id\":1,\"answer\":\"original value\"}]", "typed");
        assertNotNull(QuestionValidator.validate(mismatch, SOURCE));
    }

    @Test
    void acceptsTwoDifferentAnswersInProse() throws Exception {
        JsonNode ok = fillBlank("Percentage change uses the {{1}} as its {{2}} quantity.",
                "[{\"id\":1,\"answer\":\"original value\"},{\"id\":2,\"answer\":\"reference\"}]");
        assertNull(QuestionValidator.validate(ok, SOURCE));
    }

    @Test
    void rejectsAnswerRepeatedLaterInTheSameSentence() throws Exception {
        JsonNode q = fillBlank("A discount reduces an {{1}} price by a percentage of that original price.",
                "[{\"id\":1,\"answer\":\"original\"}]");
        String reason = QuestionValidator.validate(q, SOURCE);
        assertNotNull(reason);
        assertTrue(reason.contains("already visible"), reason);
    }

    @Test
    void rejectsAnswerAlreadyVisibleInExcerpt() throws Exception {
        JsonNode q = fillBlank("Percentage change uses the original value as its {{1}} quantity, the original value.",
                "[{\"id\":1,\"answer\":\"original value\"}]");
        String reason = QuestionValidator.validate(q, SOURCE.replace("reference quantity.",
                "reference quantity, the original value."));
        assertNotNull(reason);
        assertTrue(reason.contains("already visible"), reason);
    }

    @Test
    void shortAnswersAreNotTreatedAsLeaks() throws Exception {
        // "of" is two letters and is common, so its presence elsewhere must not reject a good blank.
        JsonNode q = fillBlank("A discount reduces an original price by a percentage {{1}} that original price.",
                "[{\"id\":1,\"answer\":\"of\"}]");
        assertNull(QuestionValidator.validate(q, SOURCE));
    }

    @Test
    void rejectsBlankIdsThatDoNotMatchPlaceholders() throws Exception {
        JsonNode missing = fillBlank("Percentage change uses the {{1}} as its {{2}} quantity.",
                "[{\"id\":1,\"answer\":\"original value\"}]");
        assertNotNull(QuestionValidator.validate(missing, SOURCE));

        JsonNode extra = fillBlank("Percentage change uses the {{1}} as its reference quantity.",
                "[{\"id\":1,\"answer\":\"original value\"},{\"id\":2,\"answer\":\"base\"}]");
        assertNotNull(QuestionValidator.validate(extra, SOURCE));

        JsonNode noIds = fillBlank("Percentage change uses the {{1}} as its {{2}} quantity.",
                "[{\"answer\":\"original value\"},{\"answer\":\"reference\"}]");
        assertNotNull(QuestionValidator.validate(noIds, SOURCE));
    }

    @Test
    void rejectsRepeatedPlaceholderAndEmptyAnswer() throws Exception {
        JsonNode repeated = fillBlank("Percentage change uses the {{1}} as its {{1}} quantity.",
                "[{\"id\":1,\"answer\":\"original value\"}]");
        assertNotNull(QuestionValidator.validate(repeated, SOURCE));

        JsonNode empty = fillBlank("Percentage change uses the {{1}} as its {{2}} quantity.",
                "[{\"id\":1,\"answer\":\"original value\"},{\"id\":2,\"answer\":\"   \"}]");
        assertNotNull(QuestionValidator.validate(empty, SOURCE));
    }

    @Test
    void existingChecksStillApply() throws Exception {
        JsonNode fabricated = fillBlank("This sentence about something entirely different is {{1}} here.",
                "[{\"id\":1,\"answer\":\"invented\"}]");
        String reason = QuestionValidator.validate(fabricated, SOURCE);
        assertNotNull(reason);
        assertTrue(reason.contains("not found verbatim"), reason);
    }
}
