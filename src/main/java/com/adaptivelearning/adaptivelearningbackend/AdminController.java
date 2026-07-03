package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

/**
 * Admin-only endpoints for the admin panel (admin.html).
 *
 * File I/O previously used local {@code Paths.get("uploads", ...)} calls.
 * All file operations now go through {@link FileStorageService}, which reads
 * from and writes to Cloudflare R2. The {@code java.nio.file.*} imports are
 * gone; {@link FileStorageService} is injected instead.
 *
 * Behaviour visible to admins is identical — the only change is where the
 * bytes live (R2 instead of the ephemeral Railway container filesystem).
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
    @Autowired private FileStorageService fileStorageService;
    // Refcount-aware release of shared material content — see MaterialContent /
    // MaterialContentService. The AI Content Testing sandbox creates real
    // Material rows under the admin's own account; cleanup here must go
    // through the same shared-content refcounting as student topic deletion,
    // in case an admin's test upload happened to match content a real
    // student also has attached to one of their topics.
    @Autowired private MaterialContentService materialContentService;
    // Server-side per-topic notepad — see TopicNote's javadoc. The AI
    // Content Testing sandbox writes real Question/Material rows under the
    // admin's own account, so its cleanup path must clear any notes the
    // admin left on a sandbox topic too, the same way it already clears
    // LessonCache/QuestionPerformance/FirstQuizResult below.
    @Autowired private TopicNoteRepository topicNoteRepository;

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

    @DeleteMapping("/users/{email}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable String email,
            @RequestBody(required = false) DeleteUserRequest request,
            HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        String callerEmail = (String) session.getAttribute("loggedInUserEmail");
        if (email != null && email.equalsIgnoreCase(callerEmail)) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "You can't delete your own account."));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false,
                    "message", "No user found with that email."));
        }

        User user = userOpt.get();
        if (user.isAdmin()) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "Admin accounts can't be deleted from this panel."));
        }

        String reason = (request == null || request.reason == null || request.reason.isBlank())
                ? "Admin decision" : request.reason.trim();

        userRepository.delete(user);
        recordActivity("delete",
                "Deleted account: " + user.getFullName() + " (" + user.getEmail() + ")",
                reason, session);

        return ResponseEntity.ok(Map.of("success", true,
                "message", user.getFullName() + "'s account has been deleted. The email is now free to re-register."));
    }

    // ── AI Content Testing cleanup ───────────────────────────────────────

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

        questionRepository.deleteByOwnerAndTopicIgnoreCase(adminEmail, topic);

        List<Attempt> attempts = attemptRepository.findByStudentIdAndTopicIgnoreCase(adminEmail, topic);
        attemptRepository.deleteAll(attempts);

        // Refcount-aware: only releases the shared R2 bytes / MaterialContent
        // row (and only for hashes with zero remaining references anywhere —
        // including real student uploads that happen to match) rather than
        // always deleting the file directly. See MaterialContentService.
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

    // ── IP Blocking ──────────────────────────────────────────────────────

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
    public ResponseEntity<Map<String, Object>> blockIp(
            @RequestBody BlockIpRequest request,
            HttpSession session,
            HttpServletRequest httpRequest) {
        if (!isAdmin(session)) return forbidden();

        if (request == null || request.ip == null || request.ip.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "IP address is required."));
        }
        String ip = request.ip.trim();

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

        String detail = request.reason
                + (request.email != null && !request.email.isBlank() ? " (user: " + request.email + ")" : "");
        recordActivity("block-ip", "Blocked IP: " + ip, detail, session);

        return ResponseEntity.ok(Map.of("success", true, "message", "Blocked IP " + ip + ".", "id", blocked.getId()));
    }

    @DeleteMapping("/block-ip")
    public ResponseEntity<Map<String, Object>> unblockIp(
            @RequestParam String ip, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        blockedIpRepository.deleteByIp(ip);
        ipBlockFilter.refresh();
        recordActivity("unblock", "Unblocked IP: " + ip, null, session);

        return ResponseEntity.ok(Map.of("success", true, "message", "Unblocked IP " + ip + "."));
    }

    // ── Material Content Review ──────────────────────────────────────────
    //
    // Files now live in R2. extractFullText() and extractFormattedBlocks()
    // download from R2 on demand; the result is still never sent to the
    // browser as raw file bytes — only the already-extracted text and the
    // diagram PNG (as base64) are returned, same as before.

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

        // Download file bytes from R2 once; reuse for both full-text and
        // formatted-blocks extraction so we only make one network call.
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

        // Diagram image: download from R2 and encode as base64
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

        // Single R2 download; reused by both extractFullText and extractFormattedBlocks
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

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Downloads a material's handout bytes from R2.
     * Returns {@code null} if the object is not found (material was deleted
     * from R2 while the DB row survived — equivalent to the old
     * {@code Files.exists()} returning false).
     */
    private byte[] loadHandoutBytes(Material m) {
        if (m.getStoredFilename() == null) return null;
        try {
            return fileStorageService.load(FileStorageService.handoutKey(m.getStoredFilename()));
        } catch (Exception e) {
            System.err.println("Admin content review: R2 download failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts full plain text from the given bytes (downloaded from R2).
     * Falls back to the stored extractedPreview if bytes are null or
     * extraction yields nothing — same fallback the old path-based version used.
     */
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

    /**
     * Extracts classified TextBlocks from the given bytes.
     * Returns an empty list if bytes are null (file not in R2).
     */
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

    // ── Request body inner classes ────────────────────────────────────────

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