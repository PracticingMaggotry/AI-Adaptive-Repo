package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountControllerChangePasswordTest {

    @Mock UserRepository userRepository;
    @Mock AdminActivityLogRepository adminActivityLogRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock PasswordPolicy passwordPolicy;
    @Mock LoginRateLimiter loginRateLimiter;
    @Mock TrustedDeviceRepository trustedDeviceRepository;
    @Spy ActiveSessionRegistry activeSessions = new ActiveSessionRegistry();
    @InjectMocks AccountController controller;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "secureCookies", true);
        user = new User("a@x.com", "oldhash", "A User");
        when(userRepository.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(user));
        when(loginRateLimiter.check(any(), any())).thenReturn(new LoginRateLimiter.CheckResult(true, 0));
        when(passwordEncoder.matches("old-pass", "oldhash")).thenReturn(true);
        when(passwordEncoder.encode("New-pass-1!")).thenReturn("newhash");
        when(passwordPolicy.validate(anyString())).thenReturn(null);
    }

    private static MockHttpSession studentSession(long authAt) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("loggedInUserEmail", "a@x.com");
        s.setAttribute("isAdmin", false);
        s.setAttribute("authAt", authAt);
        return s;
    }

    private static MockHttpServletRequest requestFor(MockHttpSession s) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/account/change-password");
        r.setSession(s);
        return r;
    }

    private ResponseEntity<Map<String, Object>> call(MockHttpSession s, MockHttpServletResponse res,
                                                     Map<String, Object> body) {
        return controller.changePassword(body, requestFor(s), res, s);
    }

    @Test
    void changesThePasswordAndRevokesDevicesAndOtherSessions() {
        MockHttpSession s = studentSession(1_000L);
        String oldSessionId = s.getId();
        MockHttpServletResponse res = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> out = call(s, res,
                Map.of("currentPassword", "old-pass", "newPassword", "New-pass-1!"));

        assertEquals(200, out.getStatusCode().value());
        assertEquals("newhash", user.getPassword());
        verify(trustedDeviceRepository).deleteAllForUser("a@x.com");

        assertNotEquals(oldSessionId, s.getId(), "session id is renewed after a credential change");
        long stamped = (Long) s.getAttribute("authAt");
        assertFalse(activeSessions.isRevoked("a@x.com", stamped), "the session that changed it carries on");
        assertTrue(activeSessions.isRevoked("a@x.com", 1_000L), "a session that signed in earlier is ended");
        assertTrue(activeSessions.isRevoked("a@x.com", null), "so is one with no timestamp");

        String cookie = res.getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("td=;") && cookie.contains("Max-Age=0") && cookie.contains("Secure"), cookie);
    }

    @Test
    void wrongCurrentPasswordChangesNothingAndCountsAsAFailure() {
        MockHttpSession s = studentSession(1_000L);

        ResponseEntity<Map<String, Object>> out = call(s, new MockHttpServletResponse(),
                Map.of("currentPassword", "wrong", "newPassword", "New-pass-1!"));

        assertEquals(400, out.getStatusCode().value());
        assertEquals("oldhash", user.getPassword());
        verify(loginRateLimiter).recordFailure(anyString(), eq("chpw:a@x.com"));
        verify(trustedDeviceRepository, never()).deleteAllForUser(any());
        assertFalse(activeSessions.isRevoked("a@x.com", 1_000L));
    }

    @Test
    void rejectsAPasswordThePolicyRefuses() {
        when(passwordPolicy.validate("weak")).thenReturn("Password must be at least 8 characters long.");
        MockHttpSession s = studentSession(1_000L);

        ResponseEntity<Map<String, Object>> out = call(s, new MockHttpServletResponse(),
                Map.of("currentPassword", "old-pass", "newPassword", "weak"));

        assertEquals(400, out.getStatusCode().value());
        assertEquals("Password must be at least 8 characters long.", out.getBody().get("message"));
        assertEquals("oldhash", user.getPassword());
        verify(trustedDeviceRepository, never()).deleteAllForUser(any());
    }

    @Test
    void rejectsReusingTheCurrentPassword() {
        MockHttpSession s = studentSession(1_000L);

        ResponseEntity<Map<String, Object>> out = call(s, new MockHttpServletResponse(),
                Map.of("currentPassword", "old-pass", "newPassword", "old-pass"));

        assertEquals(400, out.getStatusCode().value());
        assertEquals("oldhash", user.getPassword());
    }

    @Test
    void isRateLimited() {
        when(loginRateLimiter.check(any(), any())).thenReturn(new LoginRateLimiter.CheckResult(false, 125));
        MockHttpSession s = studentSession(1_000L);

        ResponseEntity<Map<String, Object>> out = call(s, new MockHttpServletResponse(),
                Map.of("currentPassword", "old-pass", "newPassword", "New-pass-1!"));

        assertEquals(429, out.getStatusCode().value());
        assertTrue(String.valueOf(out.getBody().get("message")).contains("3 minutes"));
        assertEquals("oldhash", user.getPassword());
    }

    @Test
    void requiresAStudentSession() {
        ResponseEntity<Map<String, Object>> anonymous = call(new MockHttpSession(), new MockHttpServletResponse(),
                Map.of("currentPassword", "old-pass", "newPassword", "New-pass-1!"));
        assertEquals(401, anonymous.getStatusCode().value());

        MockHttpSession admin = studentSession(1_000L);
        admin.setAttribute("isAdmin", true);
        ResponseEntity<Map<String, Object>> asAdmin = call(admin, new MockHttpServletResponse(),
                Map.of("currentPassword", "old-pass", "newPassword", "New-pass-1!"));
        assertEquals(403, asAdmin.getStatusCode().value());
        assertEquals("oldhash", user.getPassword());
    }
}
