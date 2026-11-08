package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Self-service account deletion, matching how most real platforms handle it: requesting
 * deletion never erases anything immediately. It signs the user out, suspends (archives)
 * the account so it can't be logged into or used, and flags it for admin review — actual
 * permanent removal is a separate, deliberate admin action (see AdminController's
 * self-deletion-requests endpoints), mirroring the existing admin-initiated dual-control
 * deletion flow (AccountDeletionRequest / AccountDeletionApproval).
 *
 * The email is freed for re-registration right away: User.email is unique, so the deleted
 * row's email is rewritten to a non-loginable internal placeholder and the real address is
 * kept in originalEmail for admins. A user who deletes their account and later signs up
 * again with the same email gets a brand-new User row — the old one and all its data
 * (materials, attempts, questions, etc.) is left completely untouched until an admin
 * actually executes the permanent deletion via AdminController.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    @Autowired private UserRepository userRepository;
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PasswordPolicy passwordPolicy;
    @Autowired private LoginRateLimiter loginRateLimiter;
    @Autowired private TrustedDeviceRepository trustedDeviceRepository;
    @Autowired private ActiveSessionRegistry activeSessions;

    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    /** Student-side settings. Admins have none: the warning only exists on the student side. */
    @GetMapping("/preferences")
    public ResponseEntity<Map<String, Object>> getPreferences(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }
        if (Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            return ResponseEntity.ok(Map.of("success", true, "warnBeforeReplace", false));
        }
        return userRepository.findByEmailIgnoreCase(email)
                .<ResponseEntity<Map<String, Object>>>map(u -> ResponseEntity.ok(
                        Map.of("success", true, "warnBeforeReplace", u.isWarnBeforeReplace())))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("success", false, "message", "Account not found.")));
    }

    @PostMapping("/preferences")
    public ResponseEntity<Map<String, Object>> setPreferences(
            @RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }
        if (Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin accounts have no student settings."));
        }
        Object raw = body == null ? null : body.get("warnBeforeReplace");
        if (!(raw instanceof Boolean warn)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "warnBeforeReplace must be true or false."));
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Account not found."));
        }
        User user = userOpt.get();
        user.setWarnBeforeReplace(warn);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("success", true, "warnBeforeReplace", warn));
    }

    /**
     * Student password change. Needs the current password (so a stolen session alone is not enough), applies the
     * same policy as registration, and on success:
     *   - forgets every remembered browser, so the next login on each asks for the emailed code again;
     *   - ends every other session of this account (the current one gets a fresh session ID and carries on);
     *   - clears this browser's remembered-device cookie.
     * Admin accounts are excluded: their credentials sit behind MFA and step-up and are not changed from here.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response, HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }
        if (Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin accounts can't change their password here."));
        }

        Object rawCurrent = body == null ? null : body.get("currentPassword");
        Object rawNew = body == null ? null : body.get("newPassword");
        if (!(rawCurrent instanceof String current) || current.isEmpty()
                || !(rawNew instanceof String next) || next.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Enter your current password and a new one."));
        }

        // Guessing the current password from a hijacked session is a login-style brute force: same limiter,
        // but its own counters ("chpw:" prefix) so it can't lock the real owner out of /login.
        String ip = ClientIpResolver.extractClientIp(request);
        String limiterKey = "chpw:" + email.trim().toLowerCase(Locale.ROOT);
        LoginRateLimiter.CheckResult rate = loginRateLimiter.check(ip, limiterKey);
        if (!rate.allowed()) {
            long minutes = Math.max(1, (rate.retryAfterSeconds() + 59) / 60);
            return ResponseEntity.status(429).body(Map.of("success", false,
                    "message", "Too many attempts. Please try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + "."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Account not found."));
        }
        User user = userOpt.get();

        if (!passwordEncoder.matches(current, user.getPassword())) {
            loginRateLimiter.recordFailure(ip, limiterKey);
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Your current password is incorrect."));
        }
        String problem = passwordPolicy.validate(next);
        if (problem != null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", problem));
        }
        if (passwordEncoder.matches(next, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Choose a password different from your current one."));
        }

        // Revoke trusted devices first: if anything below fails, the safe outcome is "devices forgotten,
        // password unchanged", never "password changed, old devices still trusted".
        trustedDeviceRepository.deleteAllForUser(user.getEmail().toLowerCase(Locale.ROOT));
        user.setPassword(passwordEncoder.encode(next));
        userRepository.save(user);
        loginRateLimiter.recordSuccess(ip, limiterKey);

        // New session ID after a credential change (OWASP Session Management), then end all older sessions.
        request.changeSessionId();
        long now = System.currentTimeMillis();
        session.setAttribute("authAt", now);
        activeSessions.revokeAllExcept(user.getEmail(), session.getId(), now);

        response.addHeader("Set-Cookie", AuthController.TRUSTED_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
                + (secureCookies ? "; Secure" : ""));

        return ResponseEntity.ok(Map.of("success", true,
                "message", "Password changed. Your other sessions and remembered browsers were signed out."));
    }

    @PostMapping("/request-deletion")
    public ResponseEntity<Map<String, Object>> requestDeletion(
            @RequestBody(required = false) DeletionRequestBody body, HttpSession session) {

        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }

        if (Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Admin accounts can't be deleted from this page. Ask another admin to remove this account if needed."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Account not found."));
        }
        User user = userOpt.get();

        if (user.isDeletionRequested()) {
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "A deletion request is already pending for this account."));
        }

        String confirm = body != null ? body.confirm : null;
        if (confirm == null || !confirm.trim().equalsIgnoreCase("DELETE")) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Please type DELETE to confirm."));
        }

        String realEmail = user.getEmail();
        String placeholder = "deleted+" + user.getId() + "+" + System.currentTimeMillis() + "@deleted.invalid";

        user.setOriginalEmail(realEmail);
        user.setEmail(placeholder);
        user.setDeletionRequested(true);
        user.setDeletionRequestedAt(LocalDateTime.now());
        user.setArchived(true);
        user.setArchiveReason("Account deletion requested by the user — suspended pending admin review. "
                + "No data has been permanently deleted.");
        user.setArchivedAt(LocalDateTime.now());
        user.setArchivedBy(realEmail);
        userRepository.save(user);

        adminActivityLogRepository.save(new AdminActivityLog(
                "request-deletion",
                "User requested deletion of their own account: " + user.getFullName(),
                "Original email: " + realEmail + " — awaiting admin review",
                realEmail
        ));

        // Sign out immediately; the archived account would be rejected on the next request anyway.
        session.invalidate();

        return ResponseEntity.ok(Map.of("success", true,
                "message", "Your account has been suspended and a deletion request sent for admin review."));
    }

    public static class DeletionRequestBody {
        public String confirm;
    }
}