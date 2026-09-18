package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
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

    /** Server-side secret required to create an admin account. Unset = admin self-registration disabled. */
    @Value("${admin.signup.key:}")
    private String adminSignupKey;

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

        String clientIp = IpBlockFilter.extractClientIp(request);
        String normalizedEmail = email.trim();

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

        String clientIp = IpBlockFilter.extractClientIp(request);

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

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> loginUser(@RequestParam String email, @RequestParam String password,
                                         HttpSession session, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        String clientIp = IpBlockFilter.extractClientIp(request);

        LoginRateLimiter.CheckResult rateCheck = loginRateLimiter.check(clientIp, email);
        if (!rateCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many login attempts. Please try again in "
                    + formatWait(rateCheck.retryAfterSeconds()) + ".");
            return response;
        }

        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(email.trim());
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            if (passwordEncoder.matches(password, user.getPassword())) {
                loginRateLimiter.recordSuccess(clientIp, email);

                // Session fixation defence: issue a fresh session ID before writing any state.
                request.changeSessionId();

                session.setAttribute("loggedInUserEmail", user.getEmail());
                session.setAttribute("loggedInUserName", user.getFullName());
                session.setAttribute("isAdmin", user.isAdmin());

                try {
                    user.setLastKnownIp(clientIp);
                    userRepository.save(user);
                } catch (Exception e) {
                    System.err.println("Could not record login IP (non-fatal): " + e.getMessage());
                }

                response.put("success", true);
                response.put("message", "Login successful.");
                response.put("redirect", "/dashboard.html");
                response.put("name", user.getFullName());
                response.put("email", user.getEmail());
                response.put("isAdmin", user.isAdmin());
                return response;
            }
        }

        loginRateLimiter.recordFailure(clientIp, email);
        response.put("success", false);
        response.put("message", "Invalid email or password.");
        return response;
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