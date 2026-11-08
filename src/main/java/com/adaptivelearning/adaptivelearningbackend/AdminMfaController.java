package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Second factor for admin accounts: TOTP (RFC 6238) with single-use recovery codes.
 *
 * Flow: POST /login (AuthController) verifies the password and calls {@link #begin}, which only
 * parks the session in a "pending admin" state. No admin session exists until a valid TOTP or
 * recovery code is presented here (or enrollment is confirmed).
 *
 * Enrollment is fail-closed: it is only possible while the operator has set
 * ADMIN_MFA_ENROLLMENT_OPEN=true on the server, so someone who merely knows an admin password
 * cannot register their own authenticator for that account.
 */
@Controller
public class AdminMfaController {

    static final String P_EMAIL = "pendingAdminEmail";
    static final String P_EXPIRES = "pendingAdminExpires";
    static final String P_SECRET = "pendingAdminSecret";
    private static final long PENDING_TTL_MS = 5 * 60_000L;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 32 symbols, 5 bits each
    private static final SecureRandom RNG = new SecureRandom();
    private static final Object LOCK = new Object();

    @Value("${admin.mfa.enrollment-open:false}")
    private boolean enrollmentOpen;

    @Value("${admin.mfa.issuer:Adaptive Learning}")
    private String issuer;

    @Autowired private UserRepository userRepository;
    @Autowired private AdminSecretCipher cipher;
    @Autowired private AdminSessionGuard guard;
    @Autowired private OtpVerifyRateLimiter otpLimiter;
    @Autowired private LoginRateLimiter loginLimiter;
    @Autowired private ActiveSessionRegistry activeSessions;
    @Autowired private AdminActivityLogRepository activityLog;


    Map<String, Object> begin(User user, HttpSession session) {
        Map<String, Object> r = new HashMap<>();
        clearPending(session);
        session.removeAttribute("loggedInUserEmail");
        session.removeAttribute("isAdmin");

        if (!cipher.isConfigured()) {
            audit("ADMIN_SECURITY", "Admin sign-in refused: ADMIN_MFA_KEY is not configured", null, user.getEmail());
            r.put("success", false);
            r.put("message", "Admin sign-in is unavailable: the server's MFA key is not configured.");
            return r;
        }
        boolean enrolled = user.getAdminTotpSecretEnc() != null && !user.getAdminTotpSecretEnc().isBlank();
        if (!enrolled && !enrollmentOpen) {
            audit("ADMIN_SECURITY", "Admin sign-in refused: MFA not enrolled and enrollment is closed", null, user.getEmail());
            r.put("success", false);
            r.put("message", "This admin account has no authenticator enrolled and enrollment is closed. Contact the system operator.");
            return r;
        }
        session.setAttribute(P_EMAIL, user.getEmail());
        session.setAttribute(P_EXPIRES, System.currentTimeMillis() + PENDING_TTL_MS);
        r.put("success", true);
        r.put("adminMfa", true);
        r.put("enrollmentRequired", !enrolled);
        r.put("message", enrolled
                ? "Enter the 6-digit code from your authenticator app, or a recovery code."
                : "Set up your authenticator app to finish signing in.");
        return r;
    }


    @PostMapping("/login/admin-mfa")
    @ResponseBody
    public Map<String, Object> verifyLogin(@RequestParam String code, HttpSession session, HttpServletRequest request) {
        String ip = ClientIpResolver.extractClientIp(request);
        String email = pendingEmail(session);
        if (email == null) return expired();

        String key = "adminmfa:" + email.toLowerCase(Locale.ROOT);
        OtpVerifyRateLimiter.CheckResult rl = otpLimiter.check(ip, key);
        if (!rl.allowed()) return fail("Too many attempts. Try again in " + formatWait(rl.retryAfterSeconds()) + ".");

        Optional<User> opt = userRepository.findByEmailIgnoreCase(email);
        if (opt.isEmpty() || !opt.get().isAdmin() || opt.get().getAdminTotpSecretEnc() == null) return expired();
        User user = opt.get();

        if (!verifySecondFactor(user, code)) {
            otpLimiter.recordFailure(ip, key);
            audit("ADMIN_SECURITY", "Admin second factor failed", "ip=" + ip, user.getEmail());
            return fail("Invalid code.");
        }
        otpLimiter.recordSuccess(ip, key);
        loginLimiter.recordSuccess(ip, email);
        return finishLogin(user, ip, session, request, null);
    }


    @PostMapping("/admin/mfa/enroll/start")
    @ResponseBody
    public Map<String, Object> enrollStart(HttpSession session) {
        String email = pendingEmail(session);
        if (email == null) return expired();
        if (!enrollmentOpen) return fail("Enrollment is closed. Contact the system operator.");
        Optional<User> opt = userRepository.findByEmailIgnoreCase(email);
        if (opt.isEmpty() || !opt.get().isAdmin()) return expired();
        if (opt.get().getAdminTotpSecretEnc() != null) return fail("An authenticator is already enrolled for this account.");

        String secret = Totp.generateSecret();
        session.setAttribute(P_SECRET, secret);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("secret", secret);
        r.put("otpauthUri", Totp.otpauthUri(issuer, opt.get().getEmail(), secret));
        return r;
    }

    @PostMapping("/admin/mfa/enroll/confirm")
    @ResponseBody
    public Map<String, Object> enrollConfirm(@RequestParam String code, HttpSession session, HttpServletRequest request) {
        String ip = ClientIpResolver.extractClientIp(request);
        String email = pendingEmail(session);
        String secret = (String) session.getAttribute(P_SECRET);
        if (email == null || secret == null) return expired();
        if (!enrollmentOpen) return fail("Enrollment is closed. Contact the system operator.");

        String key = "adminmfa:" + email.toLowerCase(Locale.ROOT);
        OtpVerifyRateLimiter.CheckResult rl = otpLimiter.check(ip, key);
        if (!rl.allowed()) return fail("Too many attempts. Try again in " + formatWait(rl.retryAfterSeconds()) + ".");

        Optional<User> opt = userRepository.findByEmailIgnoreCase(email);
        if (opt.isEmpty() || !opt.get().isAdmin() || opt.get().getAdminTotpSecretEnc() != null) return expired();
        User user = opt.get();

        long step = Totp.verify(secret, code, -1, System.currentTimeMillis());
        if (step < 0) {
            otpLimiter.recordFailure(ip, key);
            return fail("Invalid code. Check the time on your phone and try again.");
        }
        otpLimiter.recordSuccess(ip, key);

        List<String> recovery = generateRecoveryCodes();
        user.setAdminTotpSecretEnc(cipher.encrypt(secret, user.getEmail()));
        user.setAdminTotpLastStep(step);
        user.setAdminRecoveryHashes(String.join(",", hashAll(recovery)));
        user.setAdminMfaEnrolledAt(LocalDateTime.now());
        userRepository.save(user);
        audit("ADMIN_SECURITY", "Admin authenticator enrolled", "ip=" + ip, user.getEmail());
        loginLimiter.recordSuccess(ip, email);
        return finishLogin(user, ip, session, request, recovery);
    }


    @PostMapping("/api/admin/stepup")
    @ResponseBody
    public Map<String, Object> stepUp(@RequestParam String code, HttpSession session, HttpServletRequest request) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) return fail("Not signed in as admin.");
        String ip = ClientIpResolver.extractClientIp(request);
        String key = "adminmfa:" + email.toLowerCase(Locale.ROOT);
        OtpVerifyRateLimiter.CheckResult rl = otpLimiter.check(ip, key);
        if (!rl.allowed()) return fail("Too many attempts. Try again in " + formatWait(rl.retryAfterSeconds()) + ".");

        Optional<User> opt = userRepository.findByEmailIgnoreCase(email);
        if (opt.isEmpty() || !opt.get().isAdmin() || opt.get().getAdminTotpSecretEnc() == null) return fail("Not available.");
        if (!verifySecondFactor(opt.get(), code)) {
            otpLimiter.recordFailure(ip, key);
            audit("ADMIN_SECURITY", "Admin step-up failed", "ip=" + ip, email);
            return fail("Invalid code.");
        }
        otpLimiter.recordSuccess(ip, key);
        guard.markStepUp(session);
        audit("ADMIN_SECURITY", "Admin step-up succeeded", null, email);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        return r;
    }


    private Map<String, Object> finishLogin(User user, String ip, HttpSession session, HttpServletRequest request,
                                            List<String> recoveryCodes) {
        request.changeSessionId(); // session-fixation defence (OWASP ASVS V3 / Session Management Cheat Sheet)
        clearPending(session);
        session.setAttribute("loggedInUserEmail", user.getEmail());
        session.setAttribute("loggedInUserName", user.getFullName());
        session.setAttribute("isAdmin", Boolean.TRUE);
        guard.stamp(session, request);
        session.setMaxInactiveInterval(guard.idleSeconds());
        activeSessions.register(user.getEmail(), session.getId());
        try {
            user.setLastKnownIp(ip);
            userRepository.save(user);
        } catch (Exception e) {
            System.err.println("Could not record admin login IP (non-fatal): " + LogMask.scrub(e.getMessage()));
        }
        audit("ADMIN_LOGIN", "Admin signed in", "ip=" + ip, user.getEmail());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", "Login successful.");
        r.put("redirect", "/admin.html#/overview");
        r.put("name", user.getFullName());
        r.put("email", user.getEmail());
        r.put("isAdmin", true);
        if (recoveryCodes != null) r.put("recoveryCodes", recoveryCodes);
        return r;
    }

    /** TOTP (6 digits, replay-protected) or a single-use recovery code. */
    private boolean verifySecondFactor(User user, String code) {
        if (code == null) return false;
        synchronized (LOCK) { // two concurrent requests must not both consume the same code
            String c = code.trim();
            if (c.replace(" ", "").matches("\\d{6}")) {
                String secret;
                try {
                    secret = cipher.decrypt(user.getAdminTotpSecretEnc(), user.getEmail());
                } catch (RuntimeException e) {
                    return false;
                }
                long last = user.getAdminTotpLastStep() == null ? -1 : user.getAdminTotpLastStep();
                long step = Totp.verify(secret, c, last, System.currentTimeMillis());
                if (step < 0) return false;
                user.setAdminTotpLastStep(step);
                userRepository.save(user);
                return true;
            }
            return consumeRecoveryCode(user, c);
        }
    }

    private boolean consumeRecoveryCode(User user, String raw) {
        String norm = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (norm.length() != 10) return false;
        String h = sha256(norm);
        String stored = user.getAdminRecoveryHashes();
        if (stored == null || stored.isBlank()) return false;
        List<String> remaining = new ArrayList<>();
        boolean found = false;
        for (String s : stored.split(",")) {
            if (!found && MessageDigest.isEqual(s.getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8))) {
                found = true;
            } else if (!s.isBlank()) {
                remaining.add(s);
            }
        }
        if (!found) return false;
        user.setAdminRecoveryHashes(String.join(",", remaining));
        userRepository.save(user);
        audit("ADMIN_SECURITY", "Admin recovery code used (" + remaining.size() + " left)", null, user.getEmail());
        return true;
    }

    private static List<String> generateRecoveryCodes() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 10; j++) sb.append(RECOVERY_ALPHABET.charAt(RNG.nextInt(RECOVERY_ALPHABET.length())));
            out.add(sb.substring(0, 5) + "-" + sb.substring(5));
        }
        return out;
    }

    private static List<String> hashAll(List<String> codes) {
        List<String> out = new ArrayList<>();
        for (String c : codes) out.add(sha256(c.replace("-", "")));
        return out;
    }

    private static String sha256(String v) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pendingEmail(HttpSession session) {
        String email = (String) session.getAttribute(P_EMAIL);
        Long exp = (Long) session.getAttribute(P_EXPIRES);
        if (email == null || exp == null || System.currentTimeMillis() > exp) {
            clearPending(session);
            return null;
        }
        return email;
    }

    private static void clearPending(HttpSession session) {
        session.removeAttribute(P_EMAIL);
        session.removeAttribute(P_EXPIRES);
        session.removeAttribute(P_SECRET);
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", message);
        return r;
    }

    private static Map<String, Object> expired() {
        Map<String, Object> r = fail("Sign-in expired. Please log in again.");
        r.put("expired", true);
        return r;
    }

    private static String formatWait(long seconds) {
        return seconds >= 60 ? ((seconds + 59) / 60) + " minute(s)" : seconds + " second(s)";
    }

    private void audit(String type, String message, String detail, String by) {
        try {
            activityLog.save(new AdminActivityLog(type, message, detail, by));
        } catch (Exception e) {
            System.err.println("Admin audit log write failed (non-fatal): " + e.getMessage());
        }
    }
}
