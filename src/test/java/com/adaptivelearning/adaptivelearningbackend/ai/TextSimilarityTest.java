package com.adaptivelearning.adaptivelearningbackend.ai;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real handout questions (SQL UPDATE/SET): copies and rewordings must score high, new framings low. */
class TextSimilarityTest {

    private static final Set<String> UPDATE = TextSimilarity.tokens("UPDATE statement");
    private static final Set<String> SET = TextSimilarity.tokens("SET clause");

    private static String mcq(String q, String correct) { return TextSimilarity.signature("MCQ", q, correct, null); }

    private static final String OLD_UPDATE_MCQ = mcq("Which SQL statement is used to update existing data in a database table?", "UPDATE");
    private static final String OLD_SET_MCQ = mcq("What does the SET clause do in an UPDATE statement?", "Defines which field is to be updated as a condition");

    @Test
    void restatingTheHandoutSentenceIsACopy() {
        String again = mcq("What does the UPDATE statement do in SQL?", "Updates existing data in a database table");
        assertTrue(TextSimilarity.similarity(again, OLD_UPDATE_MCQ, UPDATE) >= AiPolicy.NEAR_COPY_THRESHOLD);

        String asTrueFalse = TextSimilarity.signature("TRUEFALSE",
                "The SET clause is part of the UPDATE statement and defines which field is to be updated as a condition.", "True", null);
        assertTrue(TextSimilarity.similarity(asTrueFalse, OLD_SET_MCQ, SET) >= AiPolicy.NEAR_COPY_THRESHOLD);
    }

    @Test
    void twoQuestionsOfTheSameQuizThatAskTheSameFactAreCopies() {
        String a = TextSimilarity.signature("TRUEFALSE",
                "The SET clause is part of the UPDATE statement and defines which field is to be updated as a condition.", "True", null);
        String b = mcq("Which clause in the UPDATE statement defines which field is to be updated as a condition?", "SET clause");
        assertTrue(TextSimilarity.similarity(a, b, SET) >= AiPolicy.NEAR_COPY_THRESHOLD);
    }

    @Test
    void aNewScenarioIsNotACopy() {
        String scenario = mcq("An employees table has a salary column. Which statement raises the salary of employee 5 to 6000?",
                "UPDATE employees SET salary = 6000 WHERE id = 5");
        String discriminate = mcq("A student runs INSERT INTO to fix a typo in an existing row and gets a second row. Which statement should be used instead?", "UPDATE");
        String snippet = mcq("In UPDATE t SET price = 5, what is price?", "The column being changed");
        assertTrue(TextSimilarity.similarity(scenario, OLD_UPDATE_MCQ, UPDATE) < AiPolicy.NEAR_COPY_THRESHOLD);
        assertTrue(TextSimilarity.similarity(discriminate, OLD_UPDATE_MCQ, UPDATE) < AiPolicy.NEAR_COPY_THRESHOLD);
        assertTrue(TextSimilarity.similarity(snippet, OLD_SET_MCQ, SET) < AiPolicy.NEAR_COPY_THRESHOLD);
        assertTrue(TextSimilarity.similarity(scenario, discriminate, UPDATE) < AiPolicy.NEAR_COPY_THRESHOLD);
    }

    @Test
    void payloadTypesAreHeldToTheLooserThreshold() {
        // A "four clues" question restates the handout definition by design; that is not a copy of an MCQ about it...
        String clues = TextSimilarity.signature("CONCEPTID", "Identify the single concept described by all four clues below:", "UPDATE statement",
                "{\"clues\":[\"It is a SQL statement.\",\"It works on data that already exists in a table, not on new data.\","
                        + "\"It is used together with the SET clause.\",\"It targets a database table to change stored values.\"],"
                        + "\"correctAnswer\":\"UPDATE statement\"}");
        double vsMcq = TextSimilarity.similarity(clues, OLD_UPDATE_MCQ, UPDATE);
        assertTrue(vsMcq < AiPolicy.NEAR_COPY_THRESHOLD_PAYLOAD_TYPES);
        // ...but the same fill-in-the-blank sentence again is.
        String fill = "{\"excerpt\":\"The UPDATE statement is used to update {{1}} data in a {{2}} table\",\"blanks\":[{\"id\":1,\"answer\":\"existing\"},{\"id\":2,\"answer\":\"database\"}]}";
        String sameFill = TextSimilarity.signature("FILLBLANK", "Complete the sentence about the UPDATE statement.", "existing | database", fill);
        assertTrue(TextSimilarity.similarity(sameFill, sameFill, UPDATE) >= AiPolicy.NEAR_COPY_THRESHOLD_PAYLOAD_TYPES);
    }

    @Test
    void conceptNameAndScaffoldingAloneNeverMakeACopy() {
        assertEquals(0.0, TextSimilarity.similarity("What is the UPDATE statement?", "Which is the UPDATE statement?", UPDATE), 1e-9);
        assertEquals(0.0, TextSimilarity.similarity("", "anything", null), 1e-9);
        assertEquals(0.0, TextSimilarity.maxSimilarity("x y z", null, null), 1e-9);
    }

    @Test
    void stemmingMeetsInflections() {
        assertEquals(TextSimilarity.tokens("update"), TextSimilarity.tokens("updates"));
        assertEquals(TextSimilarity.tokens("update"), TextSimilarity.tokens("updated"));
        assertEquals(TextSimilarity.tokens("table"), TextSimilarity.tokens("tables"));
    }
}
