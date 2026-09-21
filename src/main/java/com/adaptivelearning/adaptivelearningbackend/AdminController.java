package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;

/** Admin-only endpoints for the admin panel (admin.html). File I/O goes through FileStorageService (R2). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired private UserRepository userRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private AttemptRepository attemptRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private BannedEmailRepository bannedEmailRepository;
    @Autowired private AccountDeletionRequestRepository deletionRequestRepository;
    @Autowired private AccountDeletionApprovalRepository deletionApprovalRepository;
    @Autowired private LessonCacheRepository lessonCacheRepository;
    @Autowired private QuestionPerformanceRepository questionPerformanceRepository;
    @Autowired private FirstQuizResultRepository firstQuizResultRepository;
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;
    @Autowired private FileStorageService fileStorageService;
    // Refcount-aware release of shared material content — see MaterialContent / MaterialContentService.
    @Autowired private MaterialContentService materialContentService;
    @Autowired private TopicNoteRepository topicNoteRepository;
    @Autowired private QuestionReportRepository questionReportRepository;

    @Autowired
    private MaterialCategoryRepository categoryRepository;

    private boolean isAdmin(HttpSession session) {
        Object flag = session.getAttribute("isAdmin");
        return flag instanceof Boolean && (Boolean) flag;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin access required."));
    }

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
            m.put("lastKnownIp", u.getLastKnownIp());
            m.put("flagged", u.isFlagged());
            m.put("flagReason", u.getFlagReason());
            m.put("archived", u.isArchived());
            m.put("archiveReason", u.getArchiveReason());
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

    @GetMapping("/topics")
    public ResponseEntity<?> listTopics(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        java.util.LinkedHashMap<String, String> byLowerCase = new java.util.LinkedHashMap<>();
        for (String t : materialRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }
        for (String t : questionRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }
        for (String t : attemptRepository.queryAllDistinctTopics()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }
        return ResponseEntity.ok(new java.util.ArrayList<>(byLowerCase.values()));
    }

    private long countDistinctTopics() {
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String t : materialRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) lower.add(t.toLowerCase());
        }
        for (String t : questionRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) lower.add(t.toLowerCase());
        }
        for (String t : attemptRepository.queryAllDistinctTopics()) {
            if (t != null && !t.isBlank()) lower.add(t.toLowerCase());
        }
        return lower.size();
    }

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

    @GetMapping("/material-categories")
    public ResponseEntity<Map<String, Object>> materialCategories(
            @RequestParam(name = "range", defaultValue = "all") String range,
            HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Material> allMaterials = materialRepository.findAll();
        List<Material> materials = filterByRange(allMaterials, range);

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        for (String c : categoryRepository.findByActiveTrueOrderByNameAsc().stream().map(MaterialCategory::getName).toList()) categoryCounts.put(c, 0L);

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

    @PostMapping("/promote")
    public ResponseEntity<Map<String, Object>> promoteToAdmin(
            @RequestBody PromoteRequest request, HttpSession session) {
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

        return ResponseEntity.ok(Map.of("success", true,
                "message", user.getFullName() + " (" + user.getEmail() + ") has been promoted to admin."));
    }

    @PostMapping("/flag-user")
    public ResponseEntity<Map<String, Object>> flagUser(
            @RequestBody FlagUserRequest request, HttpSession session) {
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

        return ResponseEntity.ok(Map.of("success", true,
                "message", user.getFullName() + " has been flagged for review."));
    }

    @PostMapping("/unflag-user")
    public ResponseEntity<Map<String, Object>> unflagUser(
            @RequestBody FlagUserRequest request, HttpSession session) {
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

        return ResponseEntity.ok(Map.of("success", true,
                "message", user.getFullName() + " has been unflagged."));
    }

    @DeleteMapping("/ai-test-data/{topic}")
    public ResponseEntity<Map<String, Object>> deleteAiTestData(
            @PathVariable String topic, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        String adminEmail = (String) session.getAttribute("loggedInUserEmail");
        if (adminEmail == null || adminEmail.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false,
                    "message", "Session expired — please log in again."));
        }
        if (topic == null || topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Topic is required."));
        }

        List<Long> questionIdsBeingDeleted = questionRepository
                .findByOwnerAndTopicIgnoreCase(adminEmail, topic)
                .stream().map(Question::getId).toList();

        questionRepository.deleteByOwnerAndTopicIgnoreCase(adminEmail, topic);

        if (!questionIdsBeingDeleted.isEmpty()) {
            questionReportRepository.deleteByQuestionIdIn(questionIdsBeingDeleted);
        }

        List<Attempt> attempts = attemptRepository.findByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        attemptRepository.deleteAll(attempts);

        List<Material> materials = materialRepository.findByUploadedByAndTopicIgnoreCase(adminEmail, topic);
        List<String> hashes = materials.stream().map(Material::getContentHash).toList();
        materialRepository.deleteAll(materials);
        hashes.forEach(materialContentService::releaseIfOrphaned);

        lessonCacheRepository.deleteByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        questionPerformanceRepository.deleteByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        firstQuizResultRepository.deleteByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        topicNoteRepository.deleteByStudentIdAndTopicIgnoreCase(adminEmail, topic);

        return ResponseEntity.ok(Map.of("success", true,
                "message", "Cleared test data for \"" + topic + "\"."));
    }

    // ── Banned emails (replaces IP blocking) ──

    @GetMapping("/banned-emails")
    public ResponseEntity<Map<String, Object>> listBannedEmails(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Map<String, Object>> items = bannedEmailRepository.findAll().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("email", b.getEmail());
            m.put("reason", b.getReason());
            m.put("bannedBy", b.getBannedBy());
            m.put("bannedAt", b.getBannedAt() == null ? null : b.getBannedAt().toString());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "bannedEmails", items));
    }

    @PostMapping("/banned-emails")
    public ResponseEntity<Map<String, Object>> banEmail(@RequestBody BanEmailRequest req, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        if (req == null || req.email == null || req.email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required."));
        }
        String email = req.email.trim();
        String callerEmail = (String) session.getAttribute("loggedInUserEmail");

        if (email.equalsIgnoreCase(callerEmail)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "You can't ban your own email."));
        }
        Optional<User> targetOpt = userRepository.findByEmailIgnoreCase(email);
        if (targetOpt.isPresent() && targetOpt.get().isAdmin()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Admin accounts can't be banned."));
        }
        if (bannedEmailRepository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This email is already banned."));
        }

        BannedEmail banned = new BannedEmail(email, req.reason, callerEmail);
        bannedEmailRepository.save(banned);
        recordActivity("ban-email", "Banned email: " + email, req.reason, session);

        return ResponseEntity.ok(Map.of("success", true,
                "message", "Banned " + email + ". Any active session for this account will be signed out on its next request.",
                "id", banned.getId()));
    }

    @DeleteMapping("/banned-emails")
    public ResponseEntity<Map<String, Object>> unbanEmail(@RequestParam String email, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        bannedEmailRepository.deleteByEmailIgnoreCase(email.trim());
        recordActivity("unban-email", "Unbanned email: " + email, null, session);

        return ResponseEntity.ok(Map.of("success", true, "message", "Unbanned " + email + "."));
    }

    // ── Suspension (archive) — reversible access lock ──

    @PostMapping("/archive-user")
    public ResponseEntity<Map<String, Object>> archiveUser(@RequestBody ArchiveUserRequest req, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        if (req == null || req.email == null || req.email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required."));
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(req.email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found."));
        }
        User user = userOpt.get();
        if (user.isAdmin()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Admin accounts can't be suspended."));
        }

        String reason = (req.reason == null || req.reason.isBlank()) ? "Policy violation" : req.reason.trim();
        user.setArchived(true);
        user.setArchiveReason(reason);
        user.setArchivedAt(LocalDateTime.now());
        user.setArchivedBy((String) session.getAttribute("loggedInUserEmail"));
        userRepository.save(user);
        recordActivity("archive", "Suspended account: " + user.getFullName(), reason, session);

        return ResponseEntity.ok(Map.of("success", true,
                "message", user.getFullName() + " has been suspended — signed out on their next request and blocked from logging back in until restored."));
    }

    @PostMapping("/unarchive-user")
    public ResponseEntity<Map<String, Object>> unarchiveUser(@RequestBody ArchiveUserRequest req, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        if (req == null || req.email == null || req.email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required."));
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(req.email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found."));
        }
        User user = userOpt.get();
        user.setArchived(false);
        user.setArchiveReason(null);
        user.setArchivedAt(null);
        user.setArchivedBy(null);
        userRepository.save(user);
        recordActivity("unarchive", "Restored account: " + user.getFullName(), null, session);

        return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + "'s account has been restored."));
    }

    // ── Account deletion — requires unanimous admin approval (dual control) ──

    /** Emails (lower-cased) of every current admin. */
    private Set<String> currentAdminEmails() {
        return userRepository.findAll().stream()
                .filter(User::isAdmin)
                .map(u -> u.getEmail().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /** APPROVE votes on a request that were cast by someone who is still an admin. */
    private long countValidApprovals(Long requestId, Set<String> adminEmails) {
        return deletionApprovalRepository.findByRequestId(requestId).stream()
                .filter(v -> "APPROVE".equals(v.getDecision()))
                .filter(v -> adminEmails.contains(v.getAdminEmail().toLowerCase(Locale.ROOT)))
                .count();
    }

    @GetMapping("/deletion-requests")
    public ResponseEntity<Map<String, Object>> listDeletionRequests(
            @RequestParam(defaultValue = "ALL") String status, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<AccountDeletionRequest> raw = status.equalsIgnoreCase("ALL")
                ? deletionRequestRepository.findAllByOrderByRequestedAtDesc()
                : deletionRequestRepository.findByStatusOrderByRequestedAtDesc(status.toUpperCase(Locale.ROOT));

        Set<String> adminEmails = currentAdminEmails();
        String callerEmail = (String) session.getAttribute("loggedInUserEmail");

        List<Map<String, Object>> items = raw.stream().map(r -> {
            List<AccountDeletionApproval> votes = deletionApprovalRepository.findByRequestId(r.getId());
            boolean callerVoted = votes.stream().anyMatch(v -> v.getAdminEmail().equalsIgnoreCase(callerEmail));

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("targetEmail", r.getTargetEmail());
            m.put("targetName", r.getTargetName());
            m.put("reason", r.getReason());
            m.put("requestedBy", r.getRequestedBy());
            m.put("requestedAt", r.getRequestedAt() == null ? null : r.getRequestedAt().toString());
            m.put("status", r.getStatus());
            m.put("executedBy", r.getExecutedBy());
            m.put("executedAt", r.getExecutedAt() == null ? null : r.getExecutedAt().toString());
            m.put("approveCount", countValidApprovals(r.getId(), adminEmails));
            m.put("requiredCount", adminEmails.size());
            m.put("callerVoted", callerVoted);
            m.put("votes", votes.stream().map(v -> {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("adminEmail", v.getAdminEmail());
                vm.put("decision", v.getDecision());
                vm.put("note", v.getNote());
                vm.put("decidedAt", v.getDecidedAt() == null ? null : v.getDecidedAt().toString());
                return vm;
            }).toList());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "requests", items));
    }

    @PostMapping("/deletion-requests")
    public ResponseEntity<Map<String, Object>> createDeletionRequest(
            @RequestBody DeletionRequestBody body, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        String callerEmail = (String) session.getAttribute("loggedInUserEmail");
        if (body == null || body.email == null || body.email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email is required."));
        }
        if (body.reason == null || body.reason.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "A reason is required — deletion requests must document why, for audit purposes."));
        }
        if (body.email.trim().equalsIgnoreCase(callerEmail)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "You can't request deletion of your own account."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(body.email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "User not found."));
        }
        User user = userOpt.get();
        if (user.isAdmin()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Admin accounts can't be deleted."));
        }
        if (deletionRequestRepository.findByTargetEmailIgnoreCaseAndStatus(user.getEmail(), "PENDING").isPresent()
                || deletionRequestRepository.findByTargetEmailIgnoreCaseAndStatus(user.getEmail(), "APPROVED").isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "A deletion request for this account is already open."));
        }

        AccountDeletionRequest req = new AccountDeletionRequest(
                user.getEmail(), user.getFullName(), body.reason.trim(), callerEmail);
        deletionRequestRepository.save(req);
        // The requester's own vote counts as the first approval.
        deletionApprovalRepository.save(new AccountDeletionApproval(req.getId(), callerEmail, "APPROVE", "Requested deletion"));
        recordActivity("request-deletion",
                "Requested deletion of: " + user.getFullName() + " (" + user.getEmail() + ")", body.reason.trim(), session);

        // If the requester is the only admin there is nobody else to vote, so the request is already unanimous.
        Set<String> adminEmails = currentAdminEmails();
        if (countValidApprovals(req.getId(), adminEmails) >= adminEmails.size()) {
            req.setStatus("APPROVED");
            deletionRequestRepository.save(req);
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "Deletion requested. You are the only admin, so it is already fully approved and can be executed from the Deletion Requests tab.",
                    "id", req.getId()));
        }

        return ResponseEntity.ok(Map.of("success", true,
                "message", "Deletion requested. Every other admin must approve before this account can be deleted.",
                "id", req.getId()));
    }

    @PostMapping("/deletion-requests/{id}/vote")
    public ResponseEntity<Map<String, Object>> voteOnDeletionRequest(
            @PathVariable Long id, @RequestBody VoteRequest body, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Optional<AccountDeletionRequest> reqOpt = deletionRequestRepository.findById(id);
        if (reqOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Request not found."));
        }
        AccountDeletionRequest req = reqOpt.get();
        if (!"PENDING".equals(req.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This request is no longer pending."));
        }

        String decision = body == null || body.decision == null ? "" : body.decision.trim().toUpperCase(Locale.ROOT);
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "decision must be APPROVE or REJECT."));
        }

        String callerEmail = (String) session.getAttribute("loggedInUserEmail");
        if (deletionApprovalRepository.findByRequestIdAndAdminEmailIgnoreCase(id, callerEmail).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "You've already voted on this request."));
        }

        deletionApprovalRepository.save(new AccountDeletionApproval(id, callerEmail, decision, body.note));

        // Any single rejection vetoes the request.
        if (decision.equals("REJECT")) {
            req.setStatus("REJECTED");
            deletionRequestRepository.save(req);
            recordActivity("deletion-rejected", "Rejected deletion request for " + req.getTargetEmail(), body.note, session);
            return ResponseEntity.ok(Map.of("success", true, "message", "Request rejected — the account will not be deleted."));
        }

        Set<String> adminEmails = currentAdminEmails();
        long approveCount = countValidApprovals(id, adminEmails);

        if (approveCount >= adminEmails.size()) {
            req.setStatus("APPROVED");
            deletionRequestRepository.save(req);
            recordActivity("deletion-approved", "All admins approved deletion of " + req.getTargetEmail(), null, session);
            return ResponseEntity.ok(Map.of("success", true, "message", "All admins have approved. This request can now be executed."));
        }
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Vote recorded (" + approveCount + " of " + adminEmails.size() + " admins approved so far)."));
    }

    @PostMapping("/deletion-requests/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelDeletionRequest(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Optional<AccountDeletionRequest> reqOpt = deletionRequestRepository.findById(id);
        if (reqOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Request not found."));
        }
        AccountDeletionRequest req = reqOpt.get();
        if (!"PENDING".equals(req.getStatus()) && !"APPROVED".equals(req.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This request can no longer be cancelled."));
        }
        req.setStatus("CANCELLED");
        deletionRequestRepository.save(req);
        recordActivity("deletion-cancelled", "Cancelled deletion request for " + req.getTargetEmail(), null, session);

        return ResponseEntity.ok(Map.of("success", true, "message", "Deletion request cancelled."));
    }

    /** Deliberately a separate click from the final vote — deletion is never a side effect of approving. */
    @PostMapping("/deletion-requests/{id}/execute")
    public ResponseEntity<Map<String, Object>> executeDeletionRequest(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Optional<AccountDeletionRequest> reqOpt = deletionRequestRepository.findById(id);
        if (reqOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Request not found."));
        }
        AccountDeletionRequest req = reqOpt.get();
        if (!"APPROVED".equals(req.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This request has not been fully approved yet."));
        }

        // Re-verify unanimity at execution time: an admin promoted after the last vote must also approve.
        Set<String> adminEmails = currentAdminEmails();
        if (countValidApprovals(id, adminEmails) < adminEmails.size()) {
            req.setStatus("PENDING");
            deletionRequestRepository.save(req);
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "The admin roster changed since approval. The request is pending again until every current admin approves."));
        }

        String callerEmail = (String) session.getAttribute("loggedInUserEmail");
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(req.getTargetEmail());
        if (userOpt.isEmpty()) {
            req.setStatus("EXECUTED");
            req.setExecutedAt(LocalDateTime.now());
            req.setExecutedBy(callerEmail);
            deletionRequestRepository.save(req);
            return ResponseEntity.ok(Map.of("success", true, "message", "Account was already gone; request closed."));
        }

        User user = userOpt.get();
        if (user.isAdmin()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "This account is now an admin and can't be deleted."));
        }
        userRepository.delete(user);
        req.setStatus("EXECUTED");
        req.setExecutedAt(LocalDateTime.now());
        req.setExecutedBy(callerEmail);
        deletionRequestRepository.save(req);
        recordActivity("delete",
                "Deleted account: " + user.getFullName() + " (" + user.getEmail() + ") — approved by all admins",
                req.getReason(), session);

        return ResponseEntity.ok(Map.of("success", true, "message", user.getFullName() + "'s account has been permanently deleted."));
    }

    @GetMapping("/materials")
    public ResponseEntity<Map<String, Object>> listAllMaterials(HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<Map<String, Object>> items = materialRepository.findAll()
                .stream()
                .sorted((a, b) -> {
                    if (a.getUploadedAt() == null && b.getUploadedAt() == null) return 0;
                    if (a.getUploadedAt() == null) return 1;
                    if (b.getUploadedAt() == null) return -1;
                    return b.getUploadedAt().compareTo(a.getUploadedAt());
                })
                .map(m -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", m.getId());
                    item.put("topic", m.getTopic());
                    item.put("originalFilename", m.getOriginalFilename());
                    item.put("contentType", m.getContentType());
                    item.put("sizeBytes", m.getSizeBytes());
                    item.put("uploadedBy", m.getUploadedBy());
                    item.put("uploadedAt", m.getUploadedAt() == null ? null : m.getUploadedAt().toString());
                    item.put("primaryCategory", m.getPrimaryCategory());
                    item.put("subCategory", m.getSubCategory());
                    item.put("topicSummary", m.getTopicSummary());
                    item.put("hasDiagram", m.getDiagramImageFilename() != null);
                    item.put("diagramImageFilename", m.getDiagramImageFilename());
                    String preview = m.getExtractedPreview();
                    item.put("previewSnippet", preview != null && preview.length() > 200
                            ? preview.substring(0, 200) + "…" : preview);
                    return item;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "materials", items));
    }

    @GetMapping("/materials/{id}/content")
    public ResponseEntity<Map<String, Object>> getMaterialContent(
            @PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Optional<Material> matOpt = materialRepository.findById(id);
        if (matOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "Material not found."));
        }

        Material m = matOpt.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("id", m.getId());
        response.put("topic", m.getTopic());
        response.put("originalFilename", m.getOriginalFilename());
        response.put("contentType", m.getContentType());
        response.put("sizeBytes", m.getSizeBytes());
        response.put("uploadedBy", m.getUploadedBy());
        response.put("uploadedAt", m.getUploadedAt() == null ? null : m.getUploadedAt().toString());
        response.put("primaryCategory", m.getPrimaryCategory());
        response.put("subCategory", m.getSubCategory());
        response.put("topicSummary", m.getTopicSummary());

        byte[] fileBytes = loadHandoutBytes(m);
        boolean fileExists = fileBytes != null;

        String fullText = extractFullText(m, fileBytes);
        response.put("fullText", fullText);
        response.put("fileStillExists", fileExists);

        List<DocumentTextExtractor.TextBlock> rawBlocks = extractFormattedBlocks(m, fileBytes);
        List<Map<String, String>> formattedBlocks = new ArrayList<>();
        for (DocumentTextExtractor.TextBlock block : rawBlocks) {
            Map<String, String> bMap = new LinkedHashMap<>();
            bMap.put("kind", block.kind.name());
            bMap.put("text", block.text);
            formattedBlocks.add(bMap);
        }
        response.put("formattedBlocks", formattedBlocks);

        String diagramBase64 = null;
        if (m.getDiagramImageFilename() != null) {
            try {
                byte[] diagramBytes = fileStorageService.load(
                        FileStorageService.diagramKey(m.getDiagramImageFilename()));
                if (diagramBytes != null) {
                    diagramBase64 = Base64.getEncoder().encodeToString(diagramBytes);
                }
            } catch (Exception e) {
                System.err.println("Admin content review: diagram read failed (non-fatal): " + e.getMessage());
            }
        }
        response.put("diagramImageBase64", diagramBase64);

        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/materials/{id}/content.pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> getMaterialContentAsPdf(
            @PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) {
            return ResponseEntity.status(403).build();
        }

        Optional<Material> matOpt = materialRepository.findById(id);
        if (matOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Material m = matOpt.get();

        byte[] fileBytes = loadHandoutBytes(m);

        String fullText = extractFullText(m, fileBytes);
        List<DocumentTextExtractor.TextBlock> blocks = extractFormattedBlocks(m, fileBytes);

        List<String> metaLines = new ArrayList<>();
        StringBuilder line1 = new StringBuilder();
        if (m.getTopic() != null) line1.append("Topic: ").append(m.getTopic());
        if (m.getUploadedBy() != null) {
            if (line1.length() > 0) line1.append("   |   ");
            line1.append("Uploaded by: ").append(m.getUploadedBy());
        }
        if (m.getUploadedAt() != null) {
            if (line1.length() > 0) line1.append("   |   ");
            line1.append("Uploaded: ").append(m.getUploadedAt().toString());
        }
        metaLines.add(line1.toString());
        if (m.getPrimaryCategory() != null) {
            String cat = "Category: " + m.getPrimaryCategory()
                    + (m.getSubCategory() != null ? " / " + m.getSubCategory() : "");
            metaLines.add(cat);
        }
        metaLines.add("Re-typeset by the server from extracted text only — the original uploaded file is never rendered or served directly.");

        try {
            String title = m.getOriginalFilename() != null ? m.getOriginalFilename() : "Material";
            byte[] pdfBytes = !blocks.isEmpty()
                    ? PdfRenderer.renderFormatted(title, metaLines, blocks)
                    : PdfRenderer.render(title, metaLines, fullText);
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "inline; filename=\"" + sanitizeFilenameForHeader(m) + ".pdf\"")
                    .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                    .body(pdfBytes);
        } catch (Exception e) {
            System.err.println("PDF render failed for material " + id + ": " + e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /** Downloads a material's handout bytes from R2, or null if the object is missing. */
    private byte[] loadHandoutBytes(Material m) {
        if (m.getStoredFilename() == null) return null;
        try {
            return fileStorageService.load(FileStorageService.handoutKey(m.getStoredFilename()));
        } catch (Exception e) {
            System.err.println("Admin content review: R2 download failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    /** Extracts full plain text from the given bytes, falling back to the stored preview if extraction yields nothing. */
    private String extractFullText(Material m, byte[] fileBytes) {
        if (fileBytes != null) {
            try {
                String text = DocumentTextExtractor.extractText(
                        m.getOriginalFilename(), m.getContentType(), fileBytes);
                if (text != null && !text.isBlank()) return text;
            } catch (Exception e) {
                System.err.println("Admin content review: text extraction failed (non-fatal): " + e.getMessage());
            }
        }
        return m.getExtractedPreview();
    }

    /** Extracts classified text blocks from the given bytes, or an empty list if bytes are null. */
    private List<DocumentTextExtractor.TextBlock> extractFormattedBlocks(
            Material m, byte[] fileBytes) {
        if (fileBytes == null) return new ArrayList<>();
        try {
            return DocumentTextExtractor.extractFormattedText(
                    m.getOriginalFilename(), m.getContentType(), fileBytes);
        } catch (Exception e) {
            System.err.println("Formatted block extraction failed (non-fatal): " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private String sanitizeFilenameForHeader(Material m) {
        String base = m.getTopic() != null ? m.getTopic() : "material";
        return base.replaceAll("[^a-zA-Z0-9 _-]", "_");
    }

    public static class PromoteRequest {
        public String email;
    }

    public static class BanEmailRequest {
        public String email;
        public String reason;
    }

    public static class ArchiveUserRequest {
        public String email;
        public String reason;
    }

    public static class DeletionRequestBody {
        public String email;
        public String reason;
    }

    public static class VoteRequest {
        public String decision;
        public String note;
    }

    public static class FlagUserRequest {
        public String email;
        public String reason;
    }

}