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
    // Same per-IP + per-account escalating-lockout pattern as LoginRateLimiter,
    // reused (not reinvented) for two different abuse surfaces: spamming
    // registration OTP emails, and brute-forcing a pending OTP. See each
    // class's javadoc for the specific threat model it addresses.
    @Autowired private RegistrationRateLimiter registrationRateLimiter;
    @Autowired private OtpVerifyRateLimiter otpVerifyRateLimiter;

    /**
     * Server-side secret required to create an admin account. Set this in
     * application.properties (admin.signup.key=...) or as an environment
     * variable — never hardcode a real value here or commit one to source
     * control. If left unset, admin self-registration is impossible, which
     * is the safe default.
     */
    @Value("${admin.signup.key:}")
    private String adminSignupKey;

    private final SecureRandom secureRandom = new SecureRandom();

    // ── Step 1: Validate fields, send OTP ─────────────────────────────────
    //
    // Registration is now a two-step flow:
    //
    //   POST /register        → validate + hash password + send OTP
    //                           (no User row is written yet)
    //   POST /verify-email    → check OTP + create the real User row
    //
    // This guarantees every account in the `users` table has a confirmed
    // email address. Nothing in the unique email index is occupied until
    // the address is proven real.

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

        // Abuse gate — checked BEFORE any password hashing, duplicate-email
        // lookup, or (most importantly) sending a real OTP email via Resend.
        // Anyone could otherwise spam this endpoint with a target's email to
        // burn through the Resend quota and harass their inbox with codes
        // they never asked for. See RegistrationRateLimiter's javadoc.
        RegistrationRateLimiter.CheckResult regRateCheck = registrationRateLimiter.check(clientIp, normalizedEmail);
        if (!regRateCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many registration attempts. Please try again in "
                    + formatWait(regRateCheck.retryAfterSeconds()) + ".");
            return response;
        }

        // Server-side password strength enforcement (see PasswordPolicy).
        String passwordIssue = PasswordPolicy.validate(password);
        if (passwordIssue != null) {
            response.put("success", false);
            response.put("message", passwordIssue);
            return response;
        }

        // Reject if the email is already registered to a confirmed account.
        Optional<User> existingUser = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser.isPresent()) {
            response.put("success", false);
            response.put("message", "Email already exists. Please use another email.");
            return response;
        }

        // Admin key validation.
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

        // Hash the password once here so Step 2 can create the User row
        // instantly without re-running the expensive BCrypt operation.
        String encodedPassword = passwordEncoder.encode(password);

        // Generate a 6-digit OTP.
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));

        // Store the pending registration (replaces any prior attempt for this email).
        EmailVerificationStore.PendingRegistration pending =
                new EmailVerificationStore.PendingRegistration(
                        normalizedEmail.toLowerCase(Locale.ROOT),
                        encodedPassword,
                        fullName.trim(),
                        adminAccount,
                        otp
                );
        verificationStore.put(pending);

        // Every real send attempt counts toward the cap from here on,
        // regardless of whether the Resend API call below succeeds or the
        // person ever completes verification — spamming this endpoint with
        // valid-looking requests is exactly the abuse being throttled.
        registrationRateLimiter.recordAttempt(clientIp, normalizedEmail);

        // Send the OTP. If the mail send fails we return an error — the
        // pending entry stays in the store, so the user can retry (the
        // next POST /register call will overwrite it with a fresh OTP,
        // subject to the rate limit above).
        try {
            emailService.sendVerificationOtp(normalizedEmail, otp, fullName.trim());
        } catch (RuntimeException e) {
            // Remove the pending entry so the next attempt starts clean.
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

    // ── Step 2: Verify OTP, create User row ───────────────────────────────

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

        // Brute-force gate on OTP guessing — checked BEFORE touching the
        // verification store. Without this, the 6-digit code had no cap on
        // how many times it could be guessed within its 5-minute window.
        // See OtpVerifyRateLimiter's javadoc.
        OtpVerifyRateLimiter.CheckResult otpRateCheck = otpVerifyRateLimiter.check(clientIp, email);
        if (!otpRateCheck.allowed()) {
            response.put("success", false);
            response.put("message", "Too many verification attempts. Please try again in "
                    + formatWait(otpRateCheck.retryAfterSeconds()) + ".");
            return response;
        }

        EmailVerificationStore.PendingRegistration pending = verificationStore.get(email.trim());

        if (pending == null) {
            // Either never existed, already used, or expired.
            response.put("success", false);
            response.put("expired", true);
            response.put("message", "Verification code expired or not found. Please register again to get a new code.");
            return response;
        }

        // Constant-time comparison to prevent timing oracle on the OTP.
        if (!constantTimeEquals(pending.otp, otp.trim())) {
            otpVerifyRateLimiter.recordFailure(clientIp, email);
            response.put("success", false);
            response.put("message", "Incorrect verification code. Please try again.");
            return response;
        }

        // Correct code — clear any prior failure history for this IP/account
        // (same rule LoginRateLimiter.recordSuccess() follows on a correct password).
        otpVerifyRateLimiter.recordSuccess(clientIp, email);

        // OTP is correct — create the real User row now.
        // Double-check the email hasn't been registered by another request
        // that snuck in between Step 1 and Step 2.
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

    // ── Resend OTP ─────────────────────────────────────────────────────────
    //
    // The user may request a fresh OTP if theirs expired or they didn't
    // receive it. This requires them to re-submit their registration form
    // data (same POST /register endpoint) — the store entry is simply
    // overwritten with a new OTP and a fresh 5-minute window, and each
    // resend still counts against RegistrationRateLimiter the same way a
    // first-time registration attempt does.
    //
    // A dedicated /resend-otp endpoint is therefore NOT necessary: the
    // existing POST /register path already handles it (it replaces any prior
    // pending entry for the same email). The frontend just re-submits the
    // form when the user clicks "Resend Code".

    // ── Login ─────────────────────────────────────────────────────────────

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> loginUser(@RequestParam String email, @RequestParam String password,
                                         HttpSession session, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        String clientIp = IpBlockFilter.extractClientIp(request);

        // Brute-force gate: checked BEFORE touching the database or running BCrypt.
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

                // Session fixation defence: issue a brand-new session ID at
                // the moment of authentication before writing any session state.
                request.changeSessionId();

                session.setAttribute("loggedInUserEmail", user.getEmail());
                session.setAttribute("loggedInUserName", user.getFullName());
                session.setAttribute("isAdmin", user.isAdmin());

                // Record last known IP for admin panel's Block IP convenience.
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

    /** Renders a wait duration as a friendly "X minute(s)" / "X second(s)" string. */
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

    /**
     * Constant-time string comparison to prevent timing-based OTP oracle.
     * Returns false if either argument is null or they differ in length.
     */
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