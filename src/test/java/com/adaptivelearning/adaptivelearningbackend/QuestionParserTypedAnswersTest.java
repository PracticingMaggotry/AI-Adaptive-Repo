package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What the parser stores for true/false, fill-in-the-blank and "four ideas, one name" questions. */
class QuestionParserTypedAnswersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Question build(String json) throws Exception {
        return QuestionParser.buildQuestion(MAPPER.readTree(json), "owner", "topic", "Easy");
    }

    @Test
    void trueFalseTextLosesItsLabel() throws Exception {
        Question q = build("""
                {"type":"TRUEFALSE","questionText":"True or False: Decoding is best described as thinking about information and remembering it.",
                 "explanation":"e","payload":{"correctAnswer":"False"}}
                """);
        assertEquals("Decoding is best described as thinking about information and remembering it.", q.getQuestionText());
        assertEquals("False", q.getCorrectAnswer());
    }

    @Test
    void fillBlankKeepsOnlyRealVariantsPerBlank() throws Exception {
        Question q = build("""
                {"type":"FILLBLANK","questionText":"Complete the sentence.","explanation":"e",
                 "payload":{"excerpt":"Critical approaches are different {{1}} we consider when examining a piece of {{2}}.",
                   "blanks":[
                     {"id":1,"answer":"perspectives","acceptedAnswers":["perspectives","PERSPECTIVES","perspective","Perspective"]},
                     {"id":2,"answer":"literature","acceptedAnswers":["Literature","LITERATURE"]}],
                   "mode":"dragdrop"}}
                """);
        JsonNode blanks = MAPPER.readTree(q.getPayload()).path("blanks");
        assertEquals(1, blanks.get(0).path("acceptedAnswers").size());
        assertEquals("perspective", blanks.get(0).path("acceptedAnswers").get(0).asText());
        assertTrue(blanks.get(1).path("acceptedAnswers").isMissingNode(), "case-only variants leave nothing to store");
        assertEquals("typed", MAPPER.readTree(q.getPayload()).path("mode").asText());
    }

    @Test
    void conceptIdKeepsOnlyRealVariants() throws Exception {
        Question q = build("""
                {"type":"CONCEPTID","questionText":"Identify the single concept described by all four clues below:","explanation":"e",
                 "payload":{"clues":["a","b","c","d"],"correctAnswer":"Marxist Criticism",
                   "acceptedAnswers":["Marxist Criticism","marxist criticism","Marxist","MARXIST","Marxism"]}}
                """);
        JsonNode accepted = MAPPER.readTree(q.getPayload()).path("acceptedAnswers");
        assertEquals(2, accepted.size());
        assertEquals("Marxist", accepted.get(0).asText());
        assertEquals("Marxism", accepted.get(1).asText());
    }

    @Test
    void conceptIdWithOnlyCaseVariantsStoresNone() throws Exception {
        Question q = build("""
                {"type":"CONCEPTID","questionText":"Identify the single concept described by all four clues below:","explanation":"e",
                 "payload":{"clues":["a","b","c","d"],"correctAnswer":"Perspectives","acceptedAnswers":["perspectives","PERSPECTIVES"]}}
                """);
        assertTrue(MAPPER.readTree(q.getPayload()).path("acceptedAnswers").isMissingNode());
    }
}
