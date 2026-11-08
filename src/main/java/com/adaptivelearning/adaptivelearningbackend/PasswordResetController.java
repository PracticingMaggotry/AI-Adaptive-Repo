package com.adaptivelearning.adaptivelearningbackend;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * "Forgot password" for student accounts, using the same emailed 6-digit code the login and registration flows use.
 *
 *   POST /password/forgot  email                       -> emails a code if the account can be reset
 *   POST /password/reset   email, otp, newPassword     -> checks the code, sets the new password
 *
 * Design points:
 *   - No account enumeration. /password/forgot answers identically whether or not the address belongs to an
 *     account, and the email is sent on a background thread so response time does not give it away either.
 *     /password/reset answers a wrong code, an expired code and an unknown address with the same message.
 *   - Admin accounts are never reset here. Their second factor and recovery codes live in AdminMfaController;
 *     an emailed code must not become a way around them. Suspended/banned accounts and the *.invalid study
 *     accounts (no mailbox) are skipped too.
 *   - Guess protection has three layers: a per-account/per-IP lockout ({@link OtpVerifyRateLimiter}), a cap of
 *     {@link PasswordResetStore#MAX_WRONG_GUESSES} wrong guesses per code, and the 5-minute expiry.
 *   - A successful reset behaves like a password change: every remembered browser is forgotten and every
 *     session that signed in earlier is ended. It does NOT log the user in; they sign in normally afterwards.
 *   - The limiters here are this controller's own instances, so reset traffic cannot use up (or be used to
 *     lock) the login or registration allowances that share a client IP, such as a school network.
 */
@Controller
public class PasswordResetController {

    private static final String GENERIC_SENT =
            "If an account exists for that email, a 6-digit reset code is on its way. It expires in 5 minutes.";
    private static final String BAD_CODE =
            "That code is invalid or has expired. Request a new code and try again.";
    private static final int MAX_EMAIL_LENGTH = 254;

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PasswordPolicy passwordPolicy;
    @Autowired private AccountAccessService accountAccessService;
    @Autowired private TrustedDeviceRepository trustedDeviceRepository;
    @Autowired private ActiveSessionRegistry activeSessions;
    @Autowired private EmailService emailService;
    @Autowired private PasswordResetStore store;

    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    /** Code requests: 3 per 10 minutes per address and per IP, escalating lockouts. */
    private final RegistrationRateLimiter sendLimiter = new RegistrationRateLimiter();
    /** Code guesses: 5 wrong per 5 minutes per address and per IP, escalating lockouts. */
    private final OtpVerifyRateLimiter verifyLimiter = new OtpVerifyRateLimiter();

    private final SecureRandom secureRandom = new SecureRandom();

    private final ThreadPoolExecutor mailPool = buildMailPool();
    /** Where emails are sent from. A field (not mailPool directly) so tests can run sends inline. */
    private Executor mailer = mailPool;

    private static ThreadPoolExecutor buildMailPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "password-reset-mailer");
                    t.setDaemon(true);
                    return t;
                },
                // Queue full: drop the send rather than block the request. The user can request a new code.
                (r, ex) -> System.err.println("Password-reset mail queue is full; one send was dropped."));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void shutdownMailer() {
        mailPool.shutdown();
    }

    // ── Step 1: request a code ──

    @PostMapping("/password/forgot")
    @ResponseBody
    public Map<String, Object> forgotPassword(@RequestParam String email, HttpServletRequest request) {
        String normalized = normalize(email);
        if (normalized.isEmpty() || normalized.length() > MAX_EMAIL_LENGTH || !normalized.contains("@")) {
            return fail("Enter a valid email address.");
        }

        String ip = ClientIpResolver.extractClientIp(request);

        RegistrationRateLimiter.CheckResult rate = sendLimiter.check(ip, normalized);
        if (!rate.allowed()) {
            return fail("Too many reset requests. Please try again in " + formatWait(rate.retryAfterSeconds()) + ".");
        }
        // Counted for every address, real or not, so the limit itself reveals nothing about which exist.
        sendLimiter.recordAttempt(ip, normalized);

        // Both lookups always run, whatever the address, so the work done does not depend on whether it exists.
        boolean blocked = accountAccessService.checkEmail(normalized).blocked;
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalized);

        if (!blocked && userOpt.isPresent() && canReset(userOpt.get())) {
            User user = userOpt.get();
            String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
            store.put(normalized, otp);
            sendInBackground(user.getEmail(), otp, user.getFullName());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("email", normalized);
        response.put("message", GENERIC_SENT);
        return response;
    }

    // ── Step 2: submit the code and the new password ──

    @PostMapping("/password/reset")
    @ResponseBody
    public Map<String, Object> resetPassword(@RequestParam String email,
                                             @RequestParam String otp,
                                             @RequestParam String newPassword,
                                             HttpServletRequest request,
                                             HttpServletResponse httpResponse) {
        String normalized = normalize(email);
        String code = otp == null ? "" : otp.trim();

        if (normalized.isEmpty() || code.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            return fail("Email, code and new password are required.");
        }
        if (normalized.length() > MAX_EMAIL_LENGTH) {
            return fail(BAD_CODE);
        }
        if (!code.matches("\\d{6}")) {
            return fail("Enter the 6-digit code from the email.");
        }

        String ip = ClientIpResolver.extractClientIp(request);

        OtpVerifyRateLimiter.CheckResult rate = verifyLimiter.check(ip, normalized);
        if (!rate.allowed()) {
            return fail("Too many attempts. Please try again in " + formatWait(rate.retryAfterSeconds()) + ".");
        }

        // A missing/expired code and a wrong code are indistinguishable to the caller and both count as a failure.
        if (store.check(normalized, code) != PasswordResetStore.Verdict.OK) {
            verifyLimiter.recordFailure(ip, normalized);
            return fail(BAD_CODE);
        }

        // The code is right, so the caller owns the mailbox. A weak password does not burn the code.
        String problem = passwordPolicy.validate(newPassword);
        if (problem != null) {
            return fail(problem);
        }

        boolean blocked = accountAccessService.checkEmail(normalized).blocked;
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalized);
        if (blocked || userOpt.isEmpty() || !canReset(userOpt.get())) {
            // Suspended, banned, deleted or promoted to admin since the code was issued.
            store.remove(normalized);
            return fail(BAD_CODE);
        }
        User user = userOpt.get();

        // Forget remembered browsers first: if anything below fails, the safe outcome is "devices forgotten,
        // password unchanged", never "password changed, old devices still trusted" (same order as change-password).
        trustedDeviceRepository.deleteAllForUser(user.getEmail().toLowerCase(Locale.ROOT));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        store.remove(normalized);
        verifyLimiter.recordSuccess(ip, normalized);

        // End every session that signed in before now, including one an intruder may be holding.
        activeSessions.revokeAll(user.getEmail(), System.currentTimeMillis());

        httpResponse.addHeader("Set-Cookie", AuthController.TRUSTED_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                + (secureCookies ? "; Secure" : ""));

        System.out.println("Password reset completed for " + LogMask.email(user.getEmail()));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Password reset. Log in with your new password.");
        return response;
    }

    // ── Helpers ──

    /** Students with a real mailbox only. Suspension and bans are checked separately via AccountAccessService. */
    private static boolean canReset(User user) {
        if (user.isAdmin() || user.isArchived() || user.isDeletionRequested()) return false;
        String email = user.getEmail();
        return email != null && !email.toLowerCase(Locale.ROOT).endsWith(".invalid");
    }

    private void sendInBackground(String toEmail, String otp, String fullName) {
        mailer.execute(() -> {
            try {
                emailService.sendPasswordResetOtp(toEmail, otp, fullName);
            } catch (RuntimeException e) {
                // Deliberately not reported to the caller: that would reveal that the account exists.
                System.err.println("Password-reset email failed for " + LogMask.email(toEmail) + ": " + LogMask.scrub(e));
            }
        });
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }

    private static String formatWait(long seconds) {
        if (seconds >= 60) {
            long minutes = (seconds + 59) / 60;
            return minutes + " minute" + (minutes == 1 ? "" : "s");
        }
        return seconds + " second" + (seconds == 1 ? "" : "s");
    }
}
