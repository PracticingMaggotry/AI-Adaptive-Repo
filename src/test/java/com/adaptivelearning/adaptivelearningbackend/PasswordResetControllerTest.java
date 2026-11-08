package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetControllerTest {

    private static final String BAD_CODE_FRAGMENT = "invalid or has expired";

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock PasswordPolicy passwordPolicy;
    @Mock AccountAccessService accountAccessService;
    @Mock TrustedDeviceRepository trustedDeviceRepository;
    @Mock EmailService emailService;
    @Spy PasswordResetStore store = new PasswordResetStore();
    @Spy ActiveSessionRegistry activeSessions = new ActiveSessionRegistry();
    @InjectMocks PasswordResetController controller;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "secureCookies", true);
        // Send emails inline so the tests can see them; production sends on a background thread.
        ReflectionTestUtils.setField(controller, "mailer", (Executor) Runnable::run);

        user = new User("a@x.com", "oldhash", "A User");
        when(userRepository.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(user));
        when(accountAccessService.checkEmail(anyString())).thenReturn(AccountAccessService.AccessResult.OK);
        when(passwordPolicy.validate(anyString())).thenReturn(null);
        when(passwordEncoder.encode("New-pass-1!")).thenReturn("newhash");
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/password/forgot");
    }

    private Map<String, Object> forgot(String email) {
        return controller.forgotPassword(email, request());
    }

    private Map<String, Object> reset(String email, String code, String newPassword, MockHttpServletResponse res) {
        return controller.resetPassword(email, code, newPassword, request(), res);
    }

    private Map<String, Object> reset(String email, String code, String newPassword) {
        return reset(email, code, newPassword, new MockHttpServletResponse());
    }

    /** Requests a code for a@x.com and returns the code that was emailed. Call at most once per test. */
    private String issueCode() {
        forgot("a@x.com");
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetOtp(eq("a@x.com"), otp.capture(), eq("A User"));
        return otp.getValue();
    }

    private static String wrongCodeFor(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }


    @Test
    void emailsASixDigitCodeToAnEligibleStudent() {
        Map<String, Object> out = forgot("  A@X.com ");

        assertEquals(true, out.get("success"));
        assertEquals("a@x.com", out.get("email"));
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetOtp(eq("a@x.com"), otp.capture(), eq("A User"));
        assertTrue(otp.getValue().matches("\\d{6}"), otp.getValue());
        assertTrue(store.hasPending("a@x.com"));
    }

    @Test
    void answersIdenticallyForAnUnknownAddressAndSendsNothing() {
        Map<String, Object> unknown = forgot("nobody@x.com");
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), any());
        assertFalse(store.hasPending("nobody@x.com"));

        Map<String, Object> known = forgot("a@x.com");

        assertEquals(true, unknown.get("success"));
        assertEquals(known.get("success"), unknown.get("success"));
        assertEquals(known.get("message"), unknown.get("message"), "the message must not reveal whether the account exists");
    }

    @Test
    void neverSendsToAnAdminAccountButStillAnswersTheSame() {
        user.setAdmin(true);

        Map<String, Object> out = forgot("a@x.com");

        assertEquals(true, out.get("success"));
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), any());
        assertFalse(store.hasPending("a@x.com"));
    }

    @Test
    void neverSendsToASuspendedOrBannedAccount() {
        when(accountAccessService.checkEmail(anyString()))
                .thenReturn(new AccountAccessService.AccessResult(true, "This account has been suspended."));

        Map<String, Object> out = forgot("a@x.com");

        assertEquals(true, out.get("success"));
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), any());
    }

    @Test
    void neverSendsToAStudyAccountWithNoMailbox() {
        User participant = new User("p001@study.invalid", "hash", "Participant");
        when(userRepository.findByEmailIgnoreCase("p001@study.invalid")).thenReturn(Optional.of(participant));

        Map<String, Object> out = forgot("p001@study.invalid");

        assertEquals(true, out.get("success"));
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), any());
    }

    @Test
    void aFailedSendIsNotReportedToTheCaller() {
        doThrow(new RuntimeException("resend is down")).when(emailService).sendPasswordResetOtp(any(), any(), any());

        Map<String, Object> out = forgot("a@x.com");

        assertEquals(true, out.get("success"), "reporting the failure would reveal that the account exists");
    }

    @Test
    void rejectsAnAddressThatIsNotAnEmail() {
        Map<String, Object> out = forgot("not-an-email");

        assertEquals(false, out.get("success"));
        verify(emailService, never()).sendPasswordResetOtp(any(), any(), any());
    }

    @Test
    void limitsHowOftenACodeCanBeRequestedForOneAddress() {
        for (int i = 0; i < 3; i++) assertEquals(true, forgot("a@x.com").get("success"));

        Map<String, Object> out = forgot("a@x.com");

        assertEquals(false, out.get("success"));
        assertTrue(out.get("message").toString().startsWith("Too many reset requests"), out.get("message").toString());
    }

    @Test
    void limitsCodeRequestsFromOneIpAcrossDifferentAddresses() {
        // Unknown addresses count too, so the limit cannot be used to find out which ones exist.
        for (int i = 0; i < 3; i++) forgot("nobody" + i + "@x.com");

        Map<String, Object> out = forgot("another@x.com");

        assertEquals(false, out.get("success"));
    }


    @Test
    void resetsThePasswordForgetsDevicesAndEndsEarlierSessions() {
        String code = issueCode();
        MockHttpServletResponse res = new MockHttpServletResponse();

        Map<String, Object> out = reset("a@x.com", code, "New-pass-1!", res);

        assertEquals(true, out.get("success"), String.valueOf(out.get("message")));
        assertEquals("newhash", user.getPassword());
        verify(trustedDeviceRepository).deleteAllForUser("a@x.com");
        assertTrue(activeSessions.isRevoked("a@x.com", 1_000L), "a session that signed in earlier is ended");
        assertTrue(activeSessions.isRevoked("a@x.com", null), "so is one with no timestamp");
        assertFalse(activeSessions.isRevoked("a@x.com", System.currentTimeMillis() + 60_000L), "a later login is fine");

        String cookie = res.getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("td=;") && cookie.contains("Max-Age=0") && cookie.contains("Secure"), cookie);
    }

    @Test
    void theCodeWorksOnlyOnce() {
        String code = issueCode();
        assertEquals(true, reset("a@x.com", code, "New-pass-1!").get("success"));
        user.setPassword("changed-again");

        Map<String, Object> second = reset("a@x.com", code, "New-pass-1!");

        assertEquals(false, second.get("success"));
        assertEquals("changed-again", user.getPassword());
    }

    @Test
    void aWrongCodeChangesNothing() {
        String code = issueCode();

        Map<String, Object> out = reset("a@x.com", wrongCodeFor(code), "New-pass-1!");

        assertEquals(false, out.get("success"));
        assertTrue(out.get("message").toString().contains(BAD_CODE_FRAGMENT));
        assertEquals("oldhash", user.getPassword());
        verify(trustedDeviceRepository, never()).deleteAllForUser(any());
        assertFalse(activeSessions.isRevoked("a@x.com", 1_000L));
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAsAWrongCode() {
        String code = issueCode();

        Map<String, Object> wrong = reset("a@x.com", wrongCodeFor(code), "New-pass-1!");
        Map<String, Object> unknown = reset("nobody@x.com", "123456", "New-pass-1!");

        assertEquals(false, unknown.get("success"));
        assertEquals(wrong.get("message"), unknown.get("message"));
    }

    @Test
    void aWeakPasswordIsRefusedButTheCodeSurvivesForARetry() {
        when(passwordPolicy.validate("weak")).thenReturn("Password must be at least 8 characters long.");
        String code = issueCode();

        Map<String, Object> weak = reset("a@x.com", code, "weak");
        assertEquals(false, weak.get("success"));
        assertEquals("Password must be at least 8 characters long.", weak.get("message"));
        assertEquals("oldhash", user.getPassword());
        verify(trustedDeviceRepository, never()).deleteAllForUser(any());

        Map<String, Object> good = reset("a@x.com", code, "New-pass-1!");
        assertEquals(true, good.get("success"));
        assertEquals("newhash", user.getPassword());
    }

    @Test
    void anExpiredCodeIsRejected() {
        store.put("a@x.com", "123456", Instant.now().minusSeconds(1));

        Map<String, Object> out = reset("a@x.com", "123456", "New-pass-1!");

        assertEquals(false, out.get("success"));
        assertTrue(out.get("message").toString().contains(BAD_CODE_FRAGMENT));
        assertEquals("oldhash", user.getPassword());
    }

    @Test
    void anAccountSuspendedAfterTheCodeWasIssuedCannotBeReset() {
        String code = issueCode();
        when(accountAccessService.checkEmail(anyString()))
                .thenReturn(new AccountAccessService.AccessResult(true, "This account has been suspended."));

        Map<String, Object> out = reset("a@x.com", code, "New-pass-1!");

        assertEquals(false, out.get("success"));
        assertEquals("oldhash", user.getPassword());
        assertFalse(store.hasPending("a@x.com"), "the code is discarded");
    }

    @Test
    void anAdminAccountCanNeverBeResetHere() {
        store.put("a@x.com", "123456");
        user.setAdmin(true);

        Map<String, Object> out = reset("a@x.com", "123456", "New-pass-1!");

        assertEquals(false, out.get("success"));
        assertEquals("oldhash", user.getPassword());
        verify(trustedDeviceRepository, never()).deleteAllForUser(any());
    }

    @Test
    void repeatedWrongGuessesLockTheAddressOutEvenForTheRightCode() {
        String code = issueCode();
        String wrong = wrongCodeFor(code);
        for (int i = 0; i < 5; i++) reset("a@x.com", wrong, "New-pass-1!");

        Map<String, Object> out = reset("a@x.com", code, "New-pass-1!");

        assertEquals(false, out.get("success"));
        assertTrue(out.get("message").toString().startsWith("Too many attempts"), out.get("message").toString());
        assertEquals("oldhash", user.getPassword());
    }

    @Test
    void aMalformedCodeIsRejectedWithoutCountingAsAGuess() {
        String code = issueCode();

        for (int i = 0; i < 10; i++) {
            Map<String, Object> out = reset("a@x.com", "12ab", "New-pass-1!");
            assertEquals(false, out.get("success"));
            assertTrue(out.get("message").toString().contains("6-digit"));
        }

        assertEquals(true, reset("a@x.com", code, "New-pass-1!").get("success"), "no lockout from malformed input");
    }

    @Test
    void requiresEveryField() {
        assertEquals(false, reset("a@x.com", "123456", "").get("success"));
        assertEquals(false, reset("a@x.com", "", "New-pass-1!").get("success"));
        assertEquals(false, reset("", "123456", "New-pass-1!").get("success"));
    }
}
