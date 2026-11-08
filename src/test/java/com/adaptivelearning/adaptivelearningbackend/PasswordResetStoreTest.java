package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.adaptivelearning.adaptivelearningbackend.PasswordResetStore.Verdict;
import static org.junit.jupiter.api.Assertions.*;

class PasswordResetStoreTest {

    @Test
    void aCorrectCodeIsAcceptedAndStaysUntilRemoved() {
        PasswordResetStore s = new PasswordResetStore();
        s.put("a@x.com", "123456");

        assertEquals(Verdict.OK, s.check("a@x.com", "123456"));
        assertEquals(Verdict.OK, s.check("a@x.com", "123456"), "checking does not consume it");

        s.remove("a@x.com");
        assertEquals(Verdict.NONE, s.check("a@x.com", "123456"));
        assertFalse(s.hasPending("a@x.com"));
    }

    @Test
    void emailMatchingIgnoresCaseAndSurroundingSpace() {
        PasswordResetStore s = new PasswordResetStore();
        s.put("  A@X.com ", "123456");
        assertEquals(Verdict.OK, s.check("a@x.COM", "123456"));
    }

    @Test
    void aWrongCodeIsWrongAndAnUnknownAddressIsNone() {
        PasswordResetStore s = new PasswordResetStore();
        s.put("a@x.com", "123456");

        assertEquals(Verdict.WRONG, s.check("a@x.com", "654321"));
        assertEquals(Verdict.NONE, s.check("nobody@x.com", "123456"));
        assertEquals(Verdict.WRONG, s.check("a@x.com", null));
    }

    @Test
    void theCodeIsDestroyedAfterTooManyWrongGuesses() {
        PasswordResetStore s = new PasswordResetStore();
        s.put("a@x.com", "123456");

        for (int i = 0; i < PasswordResetStore.MAX_WRONG_GUESSES; i++) {
            assertEquals(Verdict.WRONG, s.check("a@x.com", "000000"));
        }
        assertEquals(Verdict.NONE, s.check("a@x.com", "123456"), "even the right code no longer works");
        assertFalse(s.hasPending("a@x.com"));
    }

    @Test
    void aCodeThatSurvivesFewerThanTheCapStillWorks() {
        PasswordResetStore s = new PasswordResetStore();
        s.put("a@x.com", "123456");
        for (int i = 0; i < PasswordResetStore.MAX_WRONG_GUESSES - 1; i++) s.check("a@x.com", "000000");
        assertEquals(Verdict.OK, s.check("a@x.com", "123456"));
    }

    @Test
    void anExpiredCodeIsNone() {
        PasswordResetStore s = new PasswordResetStore();
        s.put("a@x.com", "123456", Instant.now().minusSeconds(1));

        assertEquals(Verdict.NONE, s.check("a@x.com", "123456"));
        assertFalse(s.hasPending("a@x.com"));
    }

    @Test
    void aNewCodeReplacesTheOldOneAndResetsTheGuessCount() {
        PasswordResetStore s = new PasswordResetStore();
        s.put("a@x.com", "111111");
        for (int i = 0; i < PasswordResetStore.MAX_WRONG_GUESSES - 1; i++) s.check("a@x.com", "000000");

        s.put("a@x.com", "222222");

        assertEquals(Verdict.WRONG, s.check("a@x.com", "111111"), "the old code is dead");
        assertEquals(Verdict.OK, s.check("a@x.com", "222222"), "and the counter started again");
    }
}
