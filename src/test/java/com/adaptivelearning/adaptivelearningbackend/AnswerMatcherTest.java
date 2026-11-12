package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnswerMatcherTest {

    @Test
    void acceptsAnAcceptedVariantInAnyCase() {
        List<String> accepted = List.of("perspective");
        assertTrue(AnswerMatcher.matches("perspective", "perspectives", accepted));
        assertTrue(AnswerMatcher.matches("PERSPECTIVE", "perspectives", accepted));
        assertTrue(AnswerMatcher.matches("  Perspective ", "perspectives", accepted));
    }

    @Test
    void acceptsTheCanonicalAnswerIgnoringCaseAndSpacing() {
        assertTrue(AnswerMatcher.matches("PERSPECTIVES", "Perspectives", List.of()));
        assertTrue(AnswerMatcher.matches("marxist   criticism", "Marxist Criticism", null));
    }

    @Test
    void anAnswerThatIsNotListedIsStillWrong() {
        assertFalse(AnswerMatcher.matches("perspectiv", "perspectives", List.of("perspective")));
        assertFalse(AnswerMatcher.matches("marxist", "Marxist Criticism", List.of("Marxist Approach")));
    }

    @Test
    void blankAnswersAndBlankKeysNeverMatch() {
        assertFalse(AnswerMatcher.matches("", "perspectives", List.of("perspective")));
        assertFalse(AnswerMatcher.matches("   ", "perspectives", List.of("perspective")));
        assertFalse(AnswerMatcher.matches(null, "perspectives", List.of("perspective")));
        assertFalse(AnswerMatcher.matches("x", "", Arrays.asList("", null)));
    }

    @Test
    void punctuationIsNotIgnored() {
        assertFalse(AnswerMatcher.matches("cio", "C.I.O.", List.of()));
        assertTrue(AnswerMatcher.matches("c.i.o.", "C.I.O.", List.of()));
    }

    @Test
    void compatibilityFormsAreNormalised() {
        // full-width letters typed by some keyboards
        assertTrue(AnswerMatcher.matches("\uFF23\uFF29\uFF2F", "Chief Information Officer", List.of("CIO")));
    }

    @Test
    void variantsThatOnlyDifferByCaseOrSpacingAreDropped() {
        assertEquals(List.of("perspective"), AnswerMatcher.distinctVariants("Perspectives",
                List.of("perspectives", "PERSPECTIVES", "perspective", "Perspective", " ")));
        assertEquals(List.of(), AnswerMatcher.distinctVariants("Perspectives", List.of("perspectives", "Perspectives")));
        assertEquals(List.of("CIO", "Chief Information Officer (CIO)"), AnswerMatcher.distinctVariants(
                "Chief Information Officer",
                List.of("Chief Information Officer", "CIO", "cio", "Chief Information Officer (CIO)",
                        "chief information officer (cio)")));
    }

    @Test
    void variantsHandleMissingInput() {
        assertEquals(List.of(), AnswerMatcher.distinctVariants("x", null));
        assertEquals(List.of("a"), AnswerMatcher.distinctVariants("x", Arrays.asList(null, "a", "A")));
    }
}
