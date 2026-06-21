package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

/**
 * Admin-only endpoints for the admin panel (admin.html).
 *
 * This app has no Spring Security setup — every other controller gates
 * access purely through HttpSession attributes (see AuthController /
 * DashboardController etc.), so this controller follows the same pattern:
 * every method checks the "isAdmin" session attribute (set at login time)
 * and returns 403 if the caller isn't an admin.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired private UserRepository userRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private AttemptRepository attemptRepository;
    @Autowired private MaterialRepository materialRepository;

    private boolean isAdmin(HttpSession session) {
        Object flag = session.getAttribute("isAdmin");
        return flag instanceof Boolean && (Boolean) flag;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin access required."));
    }

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Map<String, Object>> users = userRepository.findAll().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("fullName", u.getFullName());
            m.put("email", u.getEmail());
            m.put("isAdmin", u.isAdmin());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "users", users));
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("totalTopics", questionRepository.findDistinctTopicBy().size());
        stats.put("totalQuestions", questionRepository.count());
        stats.put("totalAttempts", attemptRepository.count());
        stats.put("totalMaterials", materialRepository.count());

        return ResponseEntity.ok(Map.of("success", true, "stats", stats));
    }

    /**
     * Platform-wide recent quiz activity for the admin dashboard. Unlike
     * /api/dashboard (which is scoped to whatever account is currently logged
     * in), this returns attempts from EVERY student.
     */
    @GetMapping("/quiz-activity")
    public ResponseEntity<Map<String, Object>> quizActivity(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Attempt> attempts = attemptRepository.findAllByOrderByTimestampDesc();

        List<Map<String, Object>> history = attempts.stream().map(a -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("studentId", a.getStudentId());
            item.put("topic", a.getTopic());
            item.put("difficulty", a.getDifficulty());
            item.put("score", a.getPerformanceScore());
            item.put("date", a.getTimestamp() == null ? "Recently" : a.getTimestamp().toLocalDate().toString());
            return item;
        }).collect(Collectors.toList());

        double avgScore = attempts.isEmpty() ? 0 :
                attempts.stream().mapToDouble(Attempt::getPerformanceScore).average().orElse(0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("quizHistory", history);
        response.put("avgScore", Math.round(avgScore));
        return ResponseEntity.ok(response);
    }

    /**
     * Promotes an existing user to admin. Admin-only (enforced server-side
     * via the same isAdmin() session check every other method here uses) —
     * this is the safe replacement for sharing admin.signup.key around.
     * Only flips the flag on an account that already exists; it never
     * creates accounts, so it can't be used to conjure up new identities.
     */
    @PostMapping("/promote")
    public ResponseEntity<Map<String, Object>> promoteToAdmin(@RequestBody PromoteRequest request, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        if (request == null || request.email == null || request.email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required."));
        }

        Optional<User> userOpt = userRepository.findByEmail(request.email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "No user found with that email."));
        }

        User user = userOpt.get();
        if (user.isAdmin()) {
            return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + " is already an admin."));
        }

        user.setAdmin(true);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + " (" + user.getEmail() + ") has been promoted to admin."));
    }

    public static class PromoteRequest {
        public String email;
    }
}