package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class ActiveSessionRegistryTest {

    private static ActiveSessionRegistry registry(boolean singleSession) {
        ActiveSessionRegistry r = new ActiveSessionRegistry();
        ReflectionTestUtils.setField(r, "enabled", singleSession);
        return r;
    }

    @Test
    void nothingIsRevokedUntilAPasswordChangeHappens() {
        ActiveSessionRegistry r = registry(true);
        assertFalse(r.isRevoked("a@x.com", null));
        assertFalse(r.isRevoked("a@x.com", 1L));
    }

    @Test
    void sessionsOlderThanThePasswordChangeAreRevokedAndTheChangingOneSurvives() {
        ActiveSessionRegistry r = registry(true);
        long signedIn = 1_000L;
        long changedAt = 5_000L;
        r.revokeAllExcept("A@x.com", "new-session", changedAt);

        assertTrue(r.isRevoked("a@x.com", signedIn), "older session must end");
        assertTrue(r.isRevoked("a@x.com", null), "session with no timestamp must end once a change happened");
        assertFalse(r.isRevoked("a@x.com", changedAt), "the session that made the change is stamped with the change time");
        assertFalse(r.isRevoked("a@x.com", changedAt + 10), "a later login is fine");
        assertFalse(r.isRevoked("someone-else@x.com", signedIn), "other accounts are unaffected");
    }

    @Test
    void revocationWorksEvenWhenSingleSessionModeIsOff() {
        ActiveSessionRegistry r = registry(false);
        r.revokeAllExcept("a@x.com", "keep", 5_000L);
        assertTrue(r.isRevoked("a@x.com", 1_000L));
        assertTrue(r.isCurrent("a@x.com", "any-session"), "single-session mode is off, so isCurrent stays permissive");
    }

    @Test
    void withSingleSessionOnTheChangingSessionBecomesTheOnlyLiveOne() {
        ActiveSessionRegistry r = registry(true);
        r.register("a@x.com", "old");
        r.revokeAllExcept("a@x.com", "new", 5_000L);
        assertTrue(r.isCurrent("a@x.com", "new"));
        assertFalse(r.isCurrent("a@x.com", "old"));
    }

    @Test
    void aSecondChangeNeverMovesTheCutoffBackwards() {
        ActiveSessionRegistry r = registry(true);
        r.revokeAllExcept("a@x.com", "s2", 9_000L);
        r.revokeAllExcept("a@x.com", "s1", 5_000L);
        assertTrue(r.isRevoked("a@x.com", 8_000L));
    }
}
