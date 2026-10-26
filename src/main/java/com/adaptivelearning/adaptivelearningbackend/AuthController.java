package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;


@Controller
public class AuthController {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private LoginRateLimiter loginRateLimiter;
    @Autowired private EmailVerificationStore verificationStore;
    @Autowired private EmailService emailService;
    @Autowired private RegistrationRateLimiter registrationRateLimiter;
    @Autowired private OtpVerifyRateLimiter otpVerifyRateLimiter;
    @Autowired
    private PasswordPolicy passwordPolicy;
    @Autowired private AccountAccessService accountAccessService;
    @Autowired private TrustedDeviceRepository trustedDeviceRepository;
    @Autowired private ActiveSessionRegistry activeSessions;

    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    private static final String TRUSTED_COOKIE = "td";
    private static final int TRUSTED_DAYS = 30;

    /** Server-side secret required to create an admin account. Unset = admin self-registration disabled. */
    @Value("${admin.signup.key:}")
    private String adminSignupKey;

    /** Set login.otp.enabled=false (e.g. local dev without a Resend key) to skip the emailed login code. */
    @Value("${login.otp.enabled:true}")
    private boolean loginOtpEnabled;

    private static final long LOGIN_OTP_EXPIRY_SECONDS = 300;
    private static final String PENDING_EMAIL   = "pendingLoginEmail";
    private static final String PENDING_OTP     = "pendingLoginOtp";
    private static final String PENDING_EXPIRES = "pendingLoginExpires";

    private final SecureRandom secureRandom = new SecureRandom();

    // ── Step 1: validate, hash password, send OTP (no User row written yet) ──

    @PostMapping("/register")
    @ResponseBody
    public Map<String, Object> registerUser(@RequestParam String fullName,
                                            @RequestParam String email,
                                            @RequestParam String password,
                                            @RequestParam(required = false) String adminKey,
                                            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (fullName == null || fullName.isBlank() || email == null || email.isBlank()
                || password == null || password.isBlank()) {
            response.put("success", false);
            response.put("message", "Please complete all required fields.");
            return response;
        }

        String clientIp = ClientIpResolver.extractClientIp(request);
        String normalizedEmail = email.trim();

        AccountAccessService.AccessResult access = accountAccessService.checkEmail(normalizedEmail);
        if (access.blocked) {
            response.put("success", false);
            response.put("message", access.reason);
            return response;
        }

        // Checked before hashing/lookup/send — otherwise this endpoint could be spammed
        // with a target's email to burn the Resend quota and harass their inbox.
        RegistrationRateLimiter.CheckResult regRateCheck = registrationRateLimiter.check(clientIp, normalizedEmail);
        if (!regRateCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many registration attempts. Please try again in "
                    + formatWait(regRateCheck.retryAfterSeconds()) + ".");
            return response;
        }

        String passwordIssue = passwordPolicy.validate(password);
        if (passwordIssue != null) {
            response.put("success", false);
            response.put("message", passwordIssue);
            return response;
        }

        Optional<User> existingUser = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser.isPresent()) {
            response.put("success", false);
            response.put("message", "Email already exists. Please use another email.");
            return response;
        }

        boolean requestedAdmin = adminKey != null && !adminKey.isBlank();
        boolean adminAccount = requestedAdmin
                && adminSignupKey != null
                && !adminSignupKey.isBlank()
                && adminSignupKey.equals(adminKey);

        if (requestedAdmin && !adminAccount) {
            response.put("success", false);
            response.put("message", "Invalid admin signup key.");
            return response;
        }

        // Hashed once here so Step 2 can create the User row without re-running BCrypt.
        String encodedPassword = passwordEncoder.encode(password);
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));

        EmailVerificationStore.PendingRegistration pending =
                new EmailVerificationStore.PendingRegistration(
                        normalizedEmail.toLowerCase(Locale.ROOT),
                        encodedPassword,
                        fullName.trim(),
                        adminAccount,
                        otp
                );
        verificationStore.put(pending);

        // Counts toward the cap regardless of send success or later verification.
        registrationRateLimiter.recordAttempt(clientIp, normalizedEmail);

        try {
            emailService.sendVerificationOtp(normalizedEmail, otp, fullName.trim());
        } catch (RuntimeException e) {
            verificationStore.remove(normalizedEmail);
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }

        response.put("success", true);
        response.put("requiresVerification", true);
        response.put("email", normalizedEmail.toLowerCase(Locale.ROOT));
        response.put("message", "A 6-digit verification code has been sent to " + normalizedEmail + ". Enter it below to complete your registration.");
        return response;
    }

    // ── Step 2: verify OTP, create the real User row ──

    @PostMapping("/verify-email")
    @ResponseBody
    public Map<String, Object> verifyEmail(@RequestParam String email,
                                           @RequestParam String otp,
                                           HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (email == null || email.isBlank() || otp == null || otp.isBlank()) {
            response.put("success", false);
            response.put("message", "Email and verification code are required.");
            return response;
        }

        String clientIp = ClientIpResolver.extractClientIp(request);

        OtpVerifyRateLimiter.CheckResult otpRateCheck = otpVerifyRateLimiter.check(clientIp, email);
        if (!otpRateCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many verification attempts. Please try again in "
                    + formatWait(otpRateCheck.retryAfterSeconds()) + ".");
            return response;
        }

        EmailVerificationStore.PendingRegistration pending = verificationStore.get(email.trim());

        if (pending == null) {
            response.put("success", false);
            response.put("expired", true);
            response.put("message", "Verification code expired or not found. Please register again to get a new code.");
            return response;
        }

        if (!constantTimeEquals(pending.otp, otp.trim())) {
            otpVerifyRateLimiter.recordFailure(clientIp, email);
            response.put("success", false);
            response.put("message", "Incorrect verification code. Please try again.");
            return response;
        }

        otpVerifyRateLimiter.recordSuccess(clientIp, email);

        // Double-check the email wasn't registered by a request that raced in between steps.
        if (userRepository.findByEmailIgnoreCase(pending.email).isPresent()) {
            verificationStore.remove(email.trim());
            response.put("success", false);
            response.put("message", "This email was registered by another request. Please log in.");
            return response;
        }

        try {
            User user = new User(pending.email, pending.encodedPassword, pending.fullName);
            user.setAdmin(pending.adminAccount);
            userRepository.save(user);
            verificationStore.remove(email.trim());

            response.put("success", true);
            response.put("message", pending.adminAccount
                    ? "Admin account created! You can now log in."
                    : "Account created! You can now log in.");
        } catch (DataIntegrityViolationException ex) {
            System.err.println("Verify-email DataIntegrityViolationException: " + ex.getMessage());
            verificationStore.remove(email.trim());
            response.put("success", false);
            response.put("message", "Registration failed due to a database error. Please contact support.");
        }

        return response;
    }

    // No dedicated /resend-otp endpoint — the frontend just re-submits the registration
    // form on "Resend Code", which overwrites the pending entry with a fresh OTP/window.

    // ── Login ──
    // Admins: password only. Everyone else: password, then a 6-digit code emailed to the account
    // address. The pending code lives in the caller's own HTTP session, so only the browser that
    // entered the correct password can complete the login, and no one without inbox access can.

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> loginUser(@RequestParam String email, @RequestParam String password,
                                         HttpSession session, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        String clientIp = ClientIpResolver.extractClientIp(request);

        LoginRateLimiter.CheckResult rateCheck = loginRateLimiter.check(clientIp, email);
        if (!rateCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many login attempts. Please try again in "
                    + formatWait(rateCheck.retryAfterSeconds()) + ".");
            return response;
        }

        AccountAccessService.AccessResult access = accountAccessService.checkEmail(email.trim());
        if (access.blocked) {
            response.put("success", false);
            response.put("message", access.reason);
            return response;
        }

        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(email.trim());
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            if (passwordEncoder.matches(password, user.getPassword())) {

                // Skip the emailed code for admins, for study accounts whose address can't receive mail
                // (*.invalid), and for browsers that already completed a code within the last 30 days.
                if (user.isAdmin() || !loginOtpEnabled
                        || user.getEmail().toLowerCase(Locale.ROOT).endsWith(".invalid")
                        || isTrustedDevice(user, request)) {
                    loginRateLimiter.recordSuccess(clientIp, email);
                    return completeLogin(user, clientIp, session, request);
                }

                // Password is correct, but the login isn't finished until the emailed code is entered.
                // Password failures keep counting against the limiter until then.
                String sendKey = "login:" + user.getEmail().toLowerCase(Locale.ROOT);
                RegistrationRateLimiter.CheckResult sendCheck = registrationRateLimiter.check(clientIp, sendKey);
                if (!sendCheck.allowed()) {
                    response.put("success", false);
                    response.put("message", "Too many login codes requested. Please try again in "
                            + formatWait(sendCheck.retryAfterSeconds()) + ".");
                    return response;
                }

                // Don't leave a stale authenticated state around if this session was already logged in.
                clearPendingLogin(session);
                String sendError = issueLoginOtp(user, session, clientIp);
                if (sendError != null) {
                    response.put("success", false);
                    response.put("message", sendError);
                    return response;
                }

                response.put("success", true);
                response.put("requiresVerification", true);
                response.put("email", user.getEmail());
                response.put("message", "A 6-digit login code has been sent to " + maskEmail(user.getEmail()) + ".");
                return response;
            }
        }

        loginRateLimiter.recordFailure(clientIp, email);
        response.put("success", false);
        response.put("message", "Invalid email or password.");
        return response;
    }

    @PostMapping("/login/verify")
    @ResponseBody
    public Map<String, Object> verifyLogin(@RequestParam String otp,
                                           HttpSession session, HttpServletRequest request,
                                           HttpServletResponse httpResponse) {
        Map<String, Object> response = new HashMap<>();
        String clientIp = ClientIpResolver.extractClientIp(request);

        String pendingEmail = (String) session.getAttribute(PENDING_EMAIL);
        String pendingOtp = (String) session.getAttribute(PENDING_OTP);
        Long expires = (Long) session.getAttribute(PENDING_EXPIRES);

        if (pendingEmail == null || pendingOtp == null || expires == null
                || System.currentTimeMillis() > expires) {
            clearPendingLogin(session);
            response.put("success", false);
            response.put("expired", true);
            response.put("message", "Login code expired or not found. Please log in again.");
            return response;
        }

        String limiterKey = "login:" + pendingEmail.toLowerCase(Locale.ROOT);
        OtpVerifyRateLimiter.CheckResult otpRateCheck = otpVerifyRateLimiter.check(clientIp, limiterKey);
        if (!otpRateCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many verification attempts. Please try again in "
                    + formatWait(otpRateCheck.retryAfterSeconds()) + ".");
            return response;
        }

        if (otp == null || !constantTimeEquals(pendingOtp, otp.trim())) {
            otpVerifyRateLimiter.recordFailure(clientIp, limiterKey);
            response.put("success", false);
            response.put("message", "Incorrect login code. Please try again.");
            return response;
        }

        otpVerifyRateLimiter.recordSuccess(clientIp, limiterKey);
        clearPendingLogin(session);

        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(pendingEmail);
        if (userOptional.isEmpty()) {
            response.put("success", false);
            response.put("message", "Account not found. Please log in again.");
            return response;
        }

        AccountAccessService.AccessResult access = accountAccessService.checkEmail(pendingEmail);
        if (access.blocked) {
            response.put("success", false);
            response.put("message", access.reason);
            return response;
        }

        loginRateLimiter.recordSuccess(clientIp, pendingEmail);
        rememberDevice(userOptional.get(), httpResponse);
        return completeLogin(userOptional.get(), clientIp, session, request);
    }

    @PostMapping("/login/resend")
    @ResponseBody
    public Map<String, Object> resendLoginCode(HttpSession session, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        String clientIp = ClientIpResolver.extractClientIp(request);

        String pendingEmail = (String) session.getAttribute(PENDING_EMAIL);
        if (pendingEmail == null) {
            response.put("success", false);
            response.put("expired", true);
            response.put("message", "Login session expired. Please log in again.");
            return response;
        }

        String sendKey = "login:" + pendingEmail.toLowerCase(Locale.ROOT);
        RegistrationRateLimiter.CheckResult sendCheck = registrationRateLimiter.check(clientIp, sendKey);
        if (!sendCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many login codes requested. Please try again in "
                    + formatWait(sendCheck.retryAfterSeconds()) + ".");
            return response;
        }

        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(pendingEmail);
        if (userOptional.isEmpty()) {
            clearPendingLogin(session);
            response.put("success", false);
            response.put("expired", true);
            response.put("message", "Login session expired. Please log in again.");
            return response;
        }

        String sendError = issueLoginOtp(userOptional.get(), session, clientIp);
        if (sendError != null) {
            response.put("success", false);
            response.put("message", sendError);
            return response;
        }

        response.put("success", true);
        response.put("message", "A new login code has been sent to " + maskEmail(pendingEmail) + ".");
        return response;
    }

    /** Generates a code, stores it in the session, emails it. Returns an error message, or null on success. */
    private String issueLoginOtp(User user, HttpSession session, String clientIp) {
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        session.setAttribute(PENDING_EMAIL, user.getEmail());
        session.setAttribute(PENDING_OTP, otp);
        session.setAttribute(PENDING_EXPIRES, System.currentTimeMillis() + LOGIN_OTP_EXPIRY_SECONDS * 1000);

        // Counts toward the send cap whether or not the send succeeds.
        registrationRateLimiter.recordAttempt(clientIp, "login:" + user.getEmail().toLowerCase(Locale.ROOT));

        try {
            emailService.sendLoginOtp(user.getEmail(), otp, user.getFullName());
        } catch (RuntimeException e) {
            clearPendingLogin(session);
            return e.getMessage();
        }
        return null;
    }

    private void clearPendingLogin(HttpSession session) {
        session.removeAttribute(PENDING_EMAIL);
        session.removeAttribute(PENDING_OTP);
        session.removeAttribute(PENDING_EXPIRES);
    }

    /** Establishes the authenticated session. Shared by the admin/no-OTP path and OTP verification. */
    private Map<String, Object> completeLogin(User user, String clientIp,
                                              HttpSession session, HttpServletRequest request) {
        // Session fixation defence: issue a fresh session ID before writing any state.
        request.changeSessionId();

        session.setAttribute("loggedInUserEmail", user.getEmail());
        session.setAttribute("loggedInUserName", user.getFullName());
        session.setAttribute("isAdmin", user.isAdmin());
        if (!user.isAdmin()) activeSessions.register(user.getEmail(), session.getId());

        try {
            user.setLastKnownIp(clientIp);
            userRepository.save(user);
        } catch (Exception e) {
            System.err.println("Could not record login IP (non-fatal): " + e.getMessage());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Login successful.");
        response.put("redirect", "/dashboard.html");
        response.put("name", user.getFullName());
        response.put("email", user.getEmail());
        response.put("isAdmin", user.isAdmin());
        return response;
    }

    // ── Trusted devices ──

    private static String sha256(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isTrustedDevice(User user, HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie c : cookies) {
            if (!TRUSTED_COOKIE.equals(c.getName()) || c.getValue() == null || c.getValue().isBlank()) continue;
            try {
                Optional<TrustedDevice> td = trustedDeviceRepository.findByUserEmailAndTokenHash(
                        user.getEmail().toLowerCase(Locale.ROOT), sha256(c.getValue()));
                if (td.isEmpty()) return false;
                if (td.get().isExpired()) {
                    trustedDeviceRepository.delete(td.get());
                    return false;
                }
                td.get().touch();
                trustedDeviceRepository.save(td.get());
                return true;
            } catch (Exception e) {
                System.err.println("Trusted-device lookup failed (falling back to code): " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    /** After a correct code: mark this browser trusted for this account. Failure is non-fatal. */
    private void rememberDevice(User user, HttpServletResponse response) {
        try {
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            trustedDeviceRepository.save(new TrustedDevice(user.getEmail().toLowerCase(Locale.ROOT),
                    sha256(token), LocalDateTime.now().plusDays(TRUSTED_DAYS)));
            response.addHeader("Set-Cookie", TRUSTED_COOKIE + "=" + token
                    + "; Path=/; Max-Age=" + (TRUSTED_DAYS * 24 * 3600)
                    + "; HttpOnly; SameSite=Lax" + (secureCookies ? "; Secure" : ""));
        } catch (Exception e) {
            System.err.println("Could not remember device (non-fatal): " + e.getMessage());
        }
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }

    private String formatWait(long seconds) {
        if (seconds >= 60) {
            long minutes = (seconds + 59) / 60;
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        }
        return seconds + " second" + (seconds == 1 ? "" : "s");
    }

    @PostMapping("/logout")
    @ResponseBody
    public Map<String, Object> logout(HttpSession session) {
        activeSessions.release((String) session.getAttribute("loggedInUserEmail"), session.getId());
        session.invalidate();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Logged out successfully.");
        return response;
    }

    /** Constant-time comparison to avoid a timing oracle on the OTP. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < ab.length; i++) diff |= (ab[i] ^ bb[i]);
        return diff == 0;
    }
}