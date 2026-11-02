package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AnswerPositionBalancerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An MCQ whose correct option sits at index {@code correctAt} — i.e. the "model always picks B" pattern. */
    private static String mcq(int id, int correctAt) throws Exception {
        List<String> opts = new ArrayList<>();
        for (int i = 0; i < 4; i++) opts.add(i == correctAt ? "right-" + id : "wrong-" + id + "-" + i);
        return "{\"type\":\"MCQ\",\"questionText\":\"Q" + id + "?\",\"hint\":\"h\",\"explanation\":\"e\","
                + "\"payload\":{\"options\":" + MAPPER.writeValueAsString(opts)
                + ",\"correctAnswer\":\"right-" + id + "\"}}";
    }

    private static ArrayNode array(String... questions) throws Exception {
        return (ArrayNode) MAPPER.readTree("[" + String.join(",", questions) + "]");
    }

    private static int correctPosition(JsonNode q) {
        String correct = q.path("payload").path("correctAnswer").asText();
        JsonNode opts = q.path("payload").path("options");
        for (int i = 0; i < opts.size(); i++) if (opts.get(i).asText().equals(correct)) return i;
        return -1;
    }

    @Test
    void spreadsCorrectAnswerEvenlyWhenModelAlwaysPicksB() throws Exception {
        String[] qs = new String[8];
        for (int i = 0; i < 8; i++) qs[i] = mcq(i, 1);
        ArrayNode questions = array(qs);

        AnswerPositionBalancer.Summary s = AnswerPositionBalancer.balanceMcq(questions, new Random(7));

        assertEquals(8, s.shuffled());
        int[] counts = new int[4];
        for (JsonNode q : questions) {
            int pos = correctPosition(q);
            assertTrue(pos >= 0, "correctAnswer must still match exactly one option");
            counts[pos]++;
        }
        assertArrayEquals(new int[]{2, 2, 2, 2}, counts);
    }

    @Test
    void keepsTheSameOptionsAndTheSameCorrectText() throws Exception {
        ArrayNode questions = array(mcq(1, 1));
        AnswerPositionBalancer.balanceMcq(questions, new Random(1));
        JsonNode payload = questions.get(0).path("payload");

        List<String> got = new ArrayList<>();
        payload.path("options").forEach(o -> got.add(o.asText()));
        assertTrue(got.containsAll(List.of("right-1", "wrong-1-0", "wrong-1-2", "wrong-1-3")));
        assertEquals(4, got.size());
        assertEquals("right-1", payload.path("correctAnswer").asText());
    }

    @Test
    void resolvesALetterAnswerAndRewritesItAsText() throws Exception {
        ArrayNode questions = array("{\"type\":\"MCQ\",\"questionText\":\"Q?\",\"payload\":{"
                + "\"options\":[\"A. alpha\",\"B. beta\",\"C. gamma\",\"D. delta\"],\"correctAnswer\":\"C\"}}");
        AnswerPositionBalancer.balanceMcq(questions, new Random(3));
        JsonNode payload = questions.get(0).path("payload");

        assertEquals("gamma", payload.path("correctAnswer").asText());
        assertTrue(correctPosition(questions.get(0)) >= 0);
        payload.path("options").forEach(o -> assertFalse(o.asText().matches("^[A-D]\\..*"), "labels are stripped"));
    }

    @Test
    void leavesOrderDependentQuestionsAlone() throws Exception {
        String before = "{\"type\":\"MCQ\",\"questionText\":\"Q?\",\"payload\":{"
                + "\"options\":[\"x\",\"y\",\"Both A and B\",\"z\"],\"correctAnswer\":\"Both A and B\"}}";
        ArrayNode questions = array(before);
        AnswerPositionBalancer.Summary s = AnswerPositionBalancer.balanceMcq(questions, new Random(5));

        assertEquals(0, s.shuffled());
        assertEquals(MAPPER.readTree(before), questions.get(0));
    }

    @Test
    void leavesQuestionsAloneWhenTheExplanationCitesALetter() throws Exception {
        String before = "{\"type\":\"MCQ\",\"questionText\":\"Q?\",\"explanation\":\"Option B is right because...\","
                + "\"payload\":{\"options\":[\"a1\",\"b1\",\"c1\",\"d1\"],\"correctAnswer\":\"b1\"}}";
        ArrayNode questions = array(before);
        assertEquals(0, AnswerPositionBalancer.balanceMcq(questions, new Random(5)).shuffled());
        assertEquals(MAPPER.readTree(before), questions.get(0));
    }

    @Test
    void allOfTheAboveStaysInItsSlot() throws Exception {
        for (long seed = 0; seed < 20; seed++) {
            ArrayNode questions = array("{\"type\":\"MCQ\",\"questionText\":\"Q?\",\"payload\":{"
                    + "\"options\":[\"one\",\"two\",\"three\",\"All of the above\"],\"correctAnswer\":\"two\"}}");
            AnswerPositionBalancer.balanceMcq(questions, new Random(seed));
            JsonNode opts = questions.get(0).path("payload").path("options");
            assertEquals("All of the above", opts.get(3).asText());
            assertEquals("two", questions.get(0).path("payload").path("correctAnswer").asText());
        }
    }

    @Test
    void ignoresOtherTypesAndUnresolvableAnswers() throws Exception {
        String tf = "{\"type\":\"TRUEFALSE\",\"questionText\":\"Q?\",\"payload\":{\"correctAnswer\":\"False\"}}";
        String broken = "{\"type\":\"MCQ\",\"questionText\":\"Q?\",\"payload\":{"
                + "\"options\":[\"a\",\"b\",\"c\",\"d\"],\"correctAnswer\":\"not an option\"}}";
        ArrayNode questions = array(tf, broken);
        AnswerPositionBalancer.Summary s = AnswerPositionBalancer.balanceMcq(questions, new Random(2));

        assertEquals(0, s.shuffled());
        assertEquals(MAPPER.readTree(tf), questions.get(0));
        assertEquals(MAPPER.readTree(broken), questions.get(1));
    }
}
