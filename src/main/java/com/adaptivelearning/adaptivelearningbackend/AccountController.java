package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Self-service account deletion, matching how most real platforms handle it: requesting
 * deletion never erases anything immediately. It signs the user out, suspends (archives)
 * the account so it can't be logged into or used, and flags it for admin review — actual
 * permanent removal is a separate, deliberate admin action (see AdminController's
 * self-deletion-requests endpoints), same as the existing admin-initiated dual-control flow.
 *
 * The email is freed for re-registration right away: User.email is unique, so the deleted
 * row's email is rewritten to a non-loginable internal placeholder and the real address is
 * kept in originalEmail for admins. A user who deletes their account and later signs up
 * again with the same email gets a brand-new User row — the old one and all its data
 * (materials, attempts, questions, etc.) is left completely untouched until an admin
 * actually executes the permanent deletion.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    @Autowired private UserRepository userRepository;
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;

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

        session.invalidate();

        return ResponseEntity.ok(Map.of("success", true,
                "message", "Your account has been suspended and a deletion request sent for admin review."));
    }

    public static class DeletionRequestBody {
        public String confirm;
    }
}