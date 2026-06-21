package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
     * Aggregates how many uploaded materials fall into each auto-assigned
     * category (see ClaudeService.MATERIAL_CATEGORIES / MaterialController.
     * applyCategorization) and each sub-label, for the admin Overview/Reports
     * charts. Counts every material platform-wide (not scoped to one student),
     * mirroring quiz-activity's admin-wide scope.
     *
     * Sub-category counts are capped to the top N (with the remainder bucketed
     * as "Other") since sub-labels are free-text and could otherwise produce
     * an unbounded number of slices in the chart.
     *
     * @param range one of "week", "month", or "all" (default "all"). "week"
     *              and "month" filter to materials uploaded in the trailing
     *              7 / 30 days from now; "all" is the full universal timeline
     *              with no date filtering.
     */
    @GetMapping("/material-categories")
    public ResponseEntity<Map<String, Object>> materialCategories(
            @RequestParam(name = "range", defaultValue = "all") String range,
            HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Material> allMaterials = materialRepository.findAll();
        List<Material> materials = filterByRange(allMaterials, range);

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        // Pre-seed with the fixed list so every category shows up (even at 0),
        // keeping the chart's category set stable across requests.
        for (String c : ClaudeService.MATERIAL_CATEGORIES) categoryCounts.put(c, 0L);

        Map<String, Long> subCategoryCounts = new LinkedHashMap<>();

        for (Material m : materials) {
            String cat = m.getPrimaryCategory();
            if (cat == null || cat.isBlank() || !categoryCounts.containsKey(cat)) {
                cat = "General / Other";
            }
            categoryCounts.merge(cat, 1L, Long::sum);

            String sub = m.getSubCategory();
            if (sub != null && !sub.isBlank()) {
                subCategoryCounts.merge(sub.trim(), 1L, Long::sum);
            }
        }

        List<Map<String, Object>> categoryBreakdown = categoryCounts.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .sorted((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")))
                .collect(Collectors.toList());

        // Top 8 sub-categories by count, remainder bucketed as "Other"
        List<Map.Entry<String, Long>> sortedSubs = subCategoryCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .collect(Collectors.toList());

        List<Map<String, Object>> subCategoryBreakdown = new ArrayList<>();
        long otherTotal = 0;
        for (int i = 0; i < sortedSubs.size(); i++) {
            Map.Entry<String, Long> entry = sortedSubs.get(i);
            if (i < 8) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subCategory", entry.getKey());
                m.put("count", entry.getValue());
                subCategoryBreakdown.add(m);
            } else {
                otherTotal += entry.getValue();
            }
        }
        if (otherTotal > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subCategory", "Other");
            m.put("count", otherTotal);
            subCategoryBreakdown.add(m);
        }

        String topCategory = categoryBreakdown.isEmpty() || (Long) categoryBreakdown.get(0).get("count") == 0
                ? "No materials yet"
                : (String) categoryBreakdown.get(0).get("category");
        String topSubCategory = subCategoryBreakdown.isEmpty()
                ? "No sub-categories yet"
                : (String) subCategoryBreakdown.get(0).get("subCategory");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("range", normalizeRange(range));
        response.put("totalMaterials", materials.size());
        response.put("categoryBreakdown", categoryBreakdown);
        response.put("subCategoryBreakdown", subCategoryBreakdown);
        response.put("topCategory", topCategory);
        response.put("topSubCategory", topSubCategory);
        return ResponseEntity.ok(response);
    }

    /**
     * Normalizes an arbitrary range string to one of "week"/"month"/"all",
     * defaulting unrecognized values to "all" rather than erroring — a typo'd
     * or stale query param should degrade to the universal timeline, not 500.
     */
    private String normalizeRange(String range) {
        if (range == null) return "all";
        String r = range.trim().toLowerCase();
        return (r.equals("week") || r.equals("month")) ? r : "all";
    }

    /**
     * Filters materials to the trailing 7 days ("week"), trailing 30 days
     * ("month"), or returns everything unfiltered ("all" / anything else).
     * Materials with a null uploadedAt (shouldn't happen — always set in the
     * Material constructor — but defensively handled) are excluded from
     * week/month filters since their actual upload time is unknown.
     */
    private List<Material> filterByRange(List<Material> materials, String range) {
        String normalized = normalizeRange(range);
        if (normalized.equals("all")) return materials;

        java.time.LocalDateTime cutoff = normalized.equals("week")
                ? java.time.LocalDateTime.now().minusDays(7)
                : java.time.LocalDateTime.now().minusDays(30);

        return materials.stream()
                .filter(m -> m.getUploadedAt() != null && m.getUploadedAt().isAfter(cutoff))
                .collect(Collectors.toList());
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