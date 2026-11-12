package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuestionTextCleanerTest {

    private static final String CLAIM = "Decoding is best described as thinking about information and remembering it.";

    @Test
    void removesALeadingTrueOrFalseLabel() {
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("True or False: " + CLAIM));
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("TRUE OR FALSE: " + CLAIM));
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("True/False: " + CLAIM));
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("True or False? " + CLAIM));
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("True or False \u2014 " + CLAIM));
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("[True or False] " + CLAIM));
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("T/F: " + CLAIM));
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel("  True or False:   " + CLAIM + "  "));
    }

    @Test
    void removesATrailingLabel() {
        assertEquals("Decoding is x", QuestionTextCleaner.stripTrueFalseLabel("Decoding is x (True or False)"));
        assertEquals("Decoding is x", QuestionTextCleaner.stripTrueFalseLabel("Decoding is x - True or False?"));
        assertEquals("Decoding is x", QuestionTextCleaner.stripTrueFalseLabel("Decoding is x [T/F]"));
    }

    @Test
    void leavesOtherWordingAlone() {
        assertEquals(CLAIM, QuestionTextCleaner.stripTrueFalseLabel(CLAIM));
        assertEquals("A true or false question tests recall.",
                QuestionTextCleaner.stripTrueFalseLabel("A true or false question tests recall."));
        assertEquals("The answer is true or false depending on x.",
                QuestionTextCleaner.stripTrueFalseLabel("The answer is true or false depending on x."));
    }

    @Test
    void neverReturnsAnEmptyQuestion() {
        assertEquals("True or False:", QuestionTextCleaner.stripTrueFalseLabel("True or False:"));
        assertNull(QuestionTextCleaner.stripTrueFalseLabel(null));
    }

    @Test
    void onlyTrueFalseQuestionsAreCleanedForDisplay() {
        assertEquals("Decoding is x", QuestionTextCleaner.forDisplay("TRUEFALSE", "True or False: Decoding is x"));
        assertEquals("Decoding is x", QuestionTextCleaner.forDisplay("truefalse", "True or False: Decoding is x"));
        assertEquals("True or False: x", QuestionTextCleaner.forDisplay("MCQ", "True or False: x"));
        assertEquals("True or False: x", QuestionTextCleaner.forDisplay(null, "True or False: x"));
    }
}
