package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    @Autowired private BlockedIpRepository blockedIpRepository;
    @Autowired private IpBlockFilter ipBlockFilter;
    @Autowired private LessonCacheRepository lessonCacheRepository;
    @Autowired private QuestionPerformanceRepository questionPerformanceRepository;
    @Autowired private FirstQuizResultRepository firstQuizResultRepository;
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;

    private boolean isAdmin(HttpSession session) {
        Object flag = session.getAttribute("isAdmin");
        return flag instanceof Boolean && (Boolean) flag;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin access required."));
    }

    /**
     * Persists a single admin moderation action so every admin — on any
     * browser, any machine — sees the same audit trail. This replaces the
     * old approach where Flag/Unflag/Delete/Block/Promote actions only ever
     * wrote a human-readable line into the CALLING admin's own browser
     * localStorage, which meant a second admin never saw any of it, and for
     * Flag/Delete specifically, the localStorage entry was the only trace
     * anything happened at all — nothing on the server enforced or even
     * recorded it.
     */
    private void recordActivity(String type, String message, String detail, HttpSession session) {
        String performedBy = (String) session.getAttribute("loggedInUserEmail");
        adminActivityLogRepository.save(new AdminActivityLog(type, message, detail, performedBy));
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
            // Most recently observed login IP — lets the "Block IP" modal
            // pre-fill the address instead of the admin having to look it
            // up and type it in manually. Null until the user has logged
            // in at least once since this field was added.
            m.put("lastKnownIp", u.getLastKnownIp());
            // Real, server-side review flag (see User.flagged) — shared
            // across every admin instead of one browser's localStorage.
            m.put("flagged", u.isFlagged());
            m.put("flagReason", u.getFlagReason());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "users", users));
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("totalTopics", countDistinctTopics());
        stats.put("totalQuestions", questionRepository.count());
        stats.put("totalAttempts", attemptRepository.count());
        stats.put("totalMaterials", materialRepository.count());

        return ResponseEntity.ok(Map.of("success", true, "stats", stats));
    }

    /**
     * Counts distinct topics the same way TopicController.getTopics() does —
     * union of Material-backed topics and Question-backed topics, deduped
     * case-insensitively. Previously this KPI only counted
     * questionRepository.findDistinctTopicBy().size(), which undercounted
     * any topic whose AI question generation hadn't run yet, failed, or had
     * every question rejected by QuestionValidator, even though a real
     * Material row (and a real student waiting on it) already existed for it.
     */
    private long countDistinctTopics() {
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String t : materialRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) lower.add(t.toLowerCase());
        }
        for (String t : questionRepository.findDistinctTopicBy()) {
            if (t != null && !t.isBlank()) lower.add(t.toLowerCase());
        }
        // Also count topics that only exist in the attempts table — same
        // union logic as TopicController.getTopics() so both the KPI tile
        // and the Topics & Content table always agree on what "exists".
        for (String t : attemptRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) lower.add(t.toLowerCase());
        }
        return lower.size();
    }

    /**
     * Platform-wide moderation history — flags/unflags, account deletions,
     * IP blocks/unblocks, promotions, and admin topic deletions — written by
     * the server at the moment each action takes effect (see recordActivity).
     * Capped to the most recent 200 entries; the underlying table is never
     * pruned, so the full history is always recoverable directly from the DB
     * if needed.
     */
    @GetMapping("/activity-log")
    public ResponseEntity<Map<String, Object>> activityLog(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Map<String, Object>> log = adminActivityLogRepository.findAllByOrderByTimestampDesc()
                .stream()
                .limit(200)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", e.getType());
                    m.put("message", e.getMessage());
                    m.put("detail", e.getDetail());
                    m.put("performedBy", e.getPerformedBy());
                    m.put("time", e.getTimestamp() == null ? null : e.getTimestamp().toString());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "log", log));
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

    private String normalizeRange(String range) {
        if (range == null) return "all";
        String r = range.trim().toLowerCase();
        return (r.equals("week") || r.equals("month")) ? r : "all";
    }

    private List<Material> filterByRange(List<Material> materials, String range) {
        String normalized = normalizeRange(range);
        if (normalized.equals("all")) return materials;

        LocalDateTime cutoff = normalized.equals("week")
                ? LocalDateTime.now().minusDays(7)
                : LocalDateTime.now().minusDays(30);

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

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(request.email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "No user found with that email."));
        }

        User user = userOpt.get();
        if (user.isAdmin()) {
            return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + " is already an admin."));
        }

        user.setAdmin(true);
        userRepository.save(user);
        recordActivity("promote", "Promoted to admin: " + user.getFullName(), user.getEmail(), session);

        return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + " (" + user.getEmail() + ") has been promoted to admin."));
    }

    /**
     * Flags a user account for manual review. This is a REVIEW MARKER ONLY —
     * it does not restrict the account in any way; the user can still log in
     * and use the platform normally. Replaces the old admin_flagged_users
     * localStorage array, which had zero server-side existence: a "flagged"
     * user in one admin's browser was invisible to every other admin, and
     * the flag itself had no real persistence at all.
     */
    @PostMapping("/flag-user")
    public ResponseEntity<Map<String, Object>> flagUser(@RequestBody FlagUserRequest request, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        if (request == null || request.email == null || request.email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(request.email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "No user found with that email."));
        }

        User user = userOpt.get();
        String reason = (request.reason == null || request.reason.isBlank()) ? "No reason given" : request.reason.trim();
        user.setFlagged(true);
        user.setFlagReason(reason);
        user.setFlaggedAt(LocalDateTime.now());
        userRepository.save(user);

        recordActivity("flag", "Flagged user: " + user.getFullName(), reason, session);

        return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + " has been flagged for review."));
    }

    @PostMapping("/unflag-user")
    public ResponseEntity<Map<String, Object>> unflagUser(@RequestBody FlagUserRequest request, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        if (request == null || request.email == null || request.email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(request.email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "No user found with that email."));
        }

        User user = userOpt.get();
        user.setFlagged(false);
        user.setFlagReason(null);
        user.setFlaggedAt(null);
        userRepository.save(user);

        recordActivity("unflag", "Unflagged user: " + user.getFullName(), null, session);

        return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + " has been unflagged."));
    }

    /**
     * Actually deletes the user's account. Replaces the old admin_deleted_users
     * localStorage entry, which had NO server-side effect whatsoever — the
     * "deleted" account could still log in and use the platform exactly as
     * before. This is a real delete, not a soft-delete/deactivation: Material,
     * Question, and Attempt rows are keyed by the student's email as a plain
     * string (not a foreign key to User.id), so removing the User row doesn't
     * touch any of their content — it just frees the email up. If they
     * re-register with the same email, their old quiz history and uploads are
     * still there waiting for them, matching the existing "open registration
     * policy" the admin panel already advertises.
     *
     * Admin accounts can't be deleted from this panel (mirrors the existing
     * rule that admins can't be demoted here either), and an admin can't
     * delete their own account.
     */
    @DeleteMapping("/users/{email}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String email,
                                                          @RequestBody(required = false) DeleteUserRequest request,
                                                          HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        String callerEmail = (String) session.getAttribute("loggedInUserEmail");
        if (email != null && email.equalsIgnoreCase(callerEmail)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "You can't delete your own account."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "No user found with that email."));
        }

        User user = userOpt.get();
        if (user.isAdmin()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Admin accounts can't be deleted from this panel."));
        }

        String reason = (request == null || request.reason == null || request.reason.isBlank())
                ? "Admin decision" : request.reason.trim();

        userRepository.delete(user);
        recordActivity("delete", "Deleted account: " + user.getFullName() + " (" + user.getEmail() + ")", reason, session);

        return ResponseEntity.ok(Map.of("success", true,
                "message", user.getFullName() + "'s account has been deleted. The email is now free to re-register."));
    }

    // ── AI Content Testing cleanup ───────────────────────────────────────
    //
    // The admin panel's "AI Content Testing" sandbox (admin.html, aitesting
    // tab) uploads real handouts through the normal /api/materials/upload
    // endpoint so the AI pipeline runs exactly as it would for a student.
    // That means every test run leaves behind a real Material row, its
    // generated Question rows, and (if a lesson was fetched) a LessonCache
    // row — all stamped with the ADMIN's own session email as owner, since
    // admins never have student-facing accounts of their own otherwise.
    //
    // IMPORTANT: this intentionally does NOT reuse TopicController's
    // DELETE /api/topics/{topic}, because that endpoint's admin path wipes
    // a topic name for EVERY student platform-wide — exactly the wrong
    // behavior here, since a sandbox test could collide with a real
    // student's topic name (e.g. "Data Structures") and wipe their data
    // too. This endpoint always deletes ONLY the calling admin's own rows
    // for the given topic, regardless of the isAdmin flag, mirroring
    // TopicController's per-student delete path one-for-one.

    @DeleteMapping("/ai-test-data/{topic}")
    public ResponseEntity<Map<String, Object>> deleteAiTestData(@PathVariable String topic, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        String adminEmail = (String) session.getAttribute("loggedInUserEmail");
        if (adminEmail == null || adminEmail.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Session expired — please log in again."));
        }
        if (topic == null || topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Topic is required."));
        }

        questionRepository.deleteByOwnerAndTopicIgnoreCase(adminEmail, topic);

        List<Attempt> attempts = attemptRepository.findByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        attemptRepository.deleteAll(attempts);

        List<Material> materials = materialRepository.findByUploadedByAndTopicIgnoreCase(adminEmail, topic);
        materials.forEach(this::deleteMaterialFiles);
        materialRepository.deleteAll(materials);

        lessonCacheRepository.deleteByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        questionPerformanceRepository.deleteByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        firstQuizResultRepository.deleteByStudentIdAndTopicIgnoreCase(adminEmail, topic);

        return ResponseEntity.ok(Map.of("success", true, "message", "Cleared test data for \"" + topic + "\"."));
    }

    private void deleteMaterialFiles(Material material) {
        Path uploadDir = Paths.get("uploads", "materials");
        Path diagramDir = uploadDir.resolve("diagrams");

        if (material.getStoredFilename() != null) {
            try {
                Files.deleteIfExists(uploadDir.resolve(material.getStoredFilename()));
            } catch (Exception e) {
                System.err.println("Could not delete handout file (non-fatal): " + e.getMessage());
            }
        }
        if (material.getDiagramImageFilename() != null) {
            try {
                Files.deleteIfExists(diagramDir.resolve(material.getDiagramImageFilename()));
            } catch (Exception e) {
                System.err.println("Could not delete diagram file (non-fatal): " + e.getMessage());
            }
        }
    }

    // ── IP Blocking ──────────────────────────────────────────────────────
    //
    // Real, server-enforced bans (see IpBlockFilter), backed by the
    // blocked_ips table — not the old localStorage-only bookkeeping. Every
    // write here calls ipBlockFilter.refresh() so the change is live on the
    // very next request, with no restart needed.

    @GetMapping("/blocked-ips")
    public ResponseEntity<Map<String, Object>> listBlockedIps(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Map<String, Object>> ips = blockedIpRepository.findAll().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("ip", b.getIp());
            m.put("reason", b.getReason());
            m.put("email", b.getEmail());
            m.put("name", b.getName());
            m.put("blockedAt", b.getBlockedAt() == null ? null : b.getBlockedAt().toString());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "blockedIps", ips));
    }

    @PostMapping("/block-ip")
    public ResponseEntity<Map<String, Object>> blockIp(@RequestBody BlockIpRequest request,
                                                       HttpSession session,
                                                       HttpServletRequest httpRequest) {
        if (!isAdmin(session)) return forbidden();

        if (request == null || request.ip == null || request.ip.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "IP address is required."));
        }
        String ip = request.ip.trim();

        // Refuse to let an admin block the IP they are currently making this
        // very request from — without this check, a single misclick would
        // lock the admin out of the panel that's the only place blocks can
        // be removed from, with no way back in except direct DB access.
        String callerIp = IpBlockFilter.extractClientIp(httpRequest);
        if (ip.equals(callerIp)) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "You can't block your own IP address — that would lock you out of the admin panel."));
        }

        if (blockedIpRepository.existsByIp(ip)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This IP is already blocked."));
        }

        BlockedIp blocked = new BlockedIp(ip, request.reason, request.email, request.name);
        blockedIpRepository.save(blocked);
        ipBlockFilter.refresh();

        String detail = request.reason + (request.email != null && !request.email.isBlank() ? " (user: " + request.email + ")" : "");
        recordActivity("block-ip", "Blocked IP: " + ip, detail, session);

        return ResponseEntity.ok(Map.of("success", true, "message", "Blocked IP " + ip + ".", "id", blocked.getId()));
    }

    @DeleteMapping("/block-ip")
    public ResponseEntity<Map<String, Object>> unblockIp(@RequestParam String ip, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        blockedIpRepository.deleteByIp(ip);
        ipBlockFilter.refresh();
        recordActivity("unblock", "Unblocked IP: " + ip, null, session);

        return ResponseEntity.ok(Map.of("success", true, "message", "Unblocked IP " + ip + "."));
    }

    public static class PromoteRequest {
        public String email;
    }

    public static class BlockIpRequest {
        public String ip;
        public String reason;
        public String email;
        public String name;
    }

    public static class FlagUserRequest {
        public String email;
        public String reason;
    }

    public static class DeleteUserRequest {
        public String reason;
    }
}