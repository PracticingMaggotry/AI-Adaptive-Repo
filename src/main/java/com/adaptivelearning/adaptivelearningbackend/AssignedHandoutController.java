package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Admin-assigned handouts for study participants.
 *
 *  - Admin uploads a handout for chosen participants (stored only, NOT sent to Anthropic).
 *  - A participant views it (re-typeset text) and presses "send" on the quiz page, which runs the
 *    exact same pipeline as a normal upload (see MaterialController#processUploadedContent).
 *  - Enrolled participants are blocked from /api/materials/upload (see MaterialController).
 */
@RestController
public class AssignedHandoutController {

    @Autowired private AssignedHandoutRepository repo;
    @Autowired private UserRepository userRepository;
    @Autowired private ConsentService consentService;
    @Autowired private ParticipantController participantController;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private ConfigurationService configurationService;
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;
    @Autowired private MaterialController materialController;
    @Autowired private UploadProgressService uploadProgressService;

    /** Handouts currently being processed, so a double-click can't run the pipeline twice. */
    private static final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    // ───────────────────────── Admin ─────────────────────────

    @GetMapping("/api/admin/assigned-handouts")
    public ResponseEntity<Map<String, Object>> adminList(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        Map<String, String> codes = participantController.codeByEmail();

        List<Map<String, Object>> items = repo.findAllByOrderByAssignedAtDesc().stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("participantCode", codes.get(lower(h.getStudentEmail())));
            m.put("studentEmail", h.getStudentEmail());
            m.put("topic", h.getTopic());
            m.put("filename", h.getOriginalFilename());
            m.put("sizeBytes", h.getSizeBytes());
            m.put("status", h.getStatus());
            m.put("assignedBy", h.getAssignedBy());
            m.put("assignedAt", h.getAssignedAt() == null ? null : h.getAssignedAt().toString());
            m.put("processedAt", h.getProcessedAt() == null ? null : h.getProcessedAt().toString());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "handouts", items));
    }

    /** multipart: topic, file, emails (repeated). Stores the file once and creates one PENDING row per participant. */
    @PostMapping("/api/admin/assigned-handouts")
    public ResponseEntity<Map<String, Object>> assign(
            @RequestParam String topic,
            @RequestParam("file") MultipartFile file,
            @RequestParam("emails") List<String> emails,
            HttpSession session) throws IOException {
        if (!isAdmin(session)) return forbidden();
        String adminEmail = (String) session.getAttribute("loggedInUserEmail");

        if (topic == null || topic.isBlank())
            return ResponseEntity.badRequest().body(err("Please enter a topic name."));
        if (topic.trim().length() > configurationService.getMaxTopicLength())
            return ResponseEntity.badRequest().body(err("Topic name is too long."));
        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(err("Please choose a file."));
        if (emails == null || emails.stream().allMatch(e -> e == null || e.isBlank()))
            return ResponseEntity.badRequest().body(err("Pick at least one participant."));

        String originalName = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        boolean okExt = false;
        for (String ext : configurationService.getAllowedFileExtensions().split(",")) {
            if (originalName.endsWith("." + ext.trim())) { okExt = true; break; }
        }
        if (!okExt)
            return ResponseEntity.badRequest().body(err("Unsupported file type. Please upload a PDF, TXT, CSV, DOC, or DOCX file."));
        if (file.getSize() > configurationService.getMaxUploadBytes())
            return ResponseEntity.badRequest().body(err("File is too large."));

        byte[] bytes = file.getBytes();
        String text = DocumentTextExtractor.extractText(file.getOriginalFilename(), file.getContentType(), bytes);
        if (text == null || text.isBlank())
            return ResponseEntity.badRequest().body(err(
                    "No readable text could be extracted from this file (scanned image-only PDF?). Try a text-based file."));

        String cleanedTopic = toTitleCase(topic.trim());
        String safeName = Optional.ofNullable(file.getOriginalFilename())
                .orElse("handout.txt").replaceAll("[^a-zA-Z0-9._() -]", "_");
        String storedName = System.currentTimeMillis() + "_" + safeName.replaceAll("\\s+", "_");
        fileStorageService.storeBytes(FileStorageService.assignedKey(storedName), bytes,
                file.getContentType() != null ? file.getContentType() : "application/octet-stream");

        List<String> assigned = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String raw : new LinkedHashSet<>(emails)) {
            String e = raw == null ? "" : raw.trim();
            if (e.isEmpty()) continue;
            Optional<User> u = userRepository.findByEmailIgnoreCase(e);
            if (u.isEmpty()) { skipped.add(e + " (not registered)"); continue; }
            User user = u.get();
            if (user.isAdmin()) { skipped.add(e + " (admin)"); continue; }
            if (!consentService.isParticipant(user.getEmail())) { skipped.add(e + " (not an enrolled participant)"); continue; }
            if (!repo.findByStudentEmailIgnoreCaseAndTopicIgnoreCase(user.getEmail(), cleanedTopic).isEmpty()) {
                skipped.add(e + " (already has a handout for this topic)");
                continue;
            }
            repo.save(new AssignedHandout(user.getEmail(), cleanedTopic, safeName, storedName,
                    file.getContentType(), file.getSize(), adminEmail));
            assigned.add(user.getEmail());
        }

        if (assigned.isEmpty()) {
            fileStorageService.delete(FileStorageService.assignedKey(storedName));
            return ResponseEntity.badRequest().body(err("Nobody was assigned. " + String.join("; ", skipped)));
        }

        adminActivityLogRepository.save(new AdminActivityLog("assign-handout",
                "Assigned handout \"" + cleanedTopic + "\" to " + assigned.size() + " participant(s)",
                safeName, adminEmail));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("assigned", assigned.size());
        out.put("skipped", skipped);
        out.put("message", "Assigned to " + assigned.size() + " participant(s). Nothing has been sent to Anthropic yet.");
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/api/admin/assigned-handouts/{id}")
    public ResponseEntity<Map<String, Object>> adminDelete(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        Optional<AssignedHandout> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body(err("Assignment not found."));
        AssignedHandout h = opt.get();
        repo.delete(h);
        if (h.getStoredFilename() != null && !repo.existsByStoredFilename(h.getStoredFilename())) {
            fileStorageService.delete(FileStorageService.assignedKey(h.getStoredFilename()));
        }
        adminActivityLogRepository.save(new AdminActivityLog("unassign-handout",
                "Removed assigned handout \"" + h.getTopic() + "\"", null,
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Puts a PROCESSED assignment back to PENDING so the participant can send it again (e.g. after deleting the topic). */
    @PostMapping("/api/admin/assigned-handouts/{id}/reset")
    public ResponseEntity<Map<String, Object>> adminReset(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        Optional<AssignedHandout> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body(err("Assignment not found."));
        AssignedHandout h = opt.get();
        h.setStatus("PENDING");
        h.setProcessedAt(null);
        repo.save(h);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ─────────────────────── Participant ───────────────────────

    @GetMapping("/api/assigned-handouts")
    public ResponseEntity<Map<String, Object>> mine(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) return ResponseEntity.status(401).body(err("Please log in first."));

        List<Map<String, Object>> items = repo.findByStudentEmailIgnoreCaseOrderByAssignedAtDesc(email).stream().map(h -> {
            String enc = URLEncoder.encode(h.getTopic(), StandardCharsets.UTF_8);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("topic", h.getTopic());
            m.put("filename", h.getOriginalFilename());
            m.put("sizeBytes", h.getSizeBytes());
            m.put("status", h.getStatus());
            m.put("assignedAt", h.getAssignedAt() == null ? null : h.getAssignedAt().toString());
            m.put("openUrl", "/quizpage.html?topic=" + enc + "&handout=" + h.getId());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("success", true, "handouts", items));
    }

    /** Re-typeset text of the assigned handout. The raw file bytes are never served. */
    @GetMapping("/api/assigned-handouts/{id}/content")
    public ResponseEntity<Map<String, Object>> content(@PathVariable Long id, HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) return ResponseEntity.status(401).body(err("Please log in first."));

        Optional<AssignedHandout> opt = repo.findById(id);
        if (opt.isEmpty() || !email.equalsIgnoreCase(opt.get().getStudentEmail()))
            return ResponseEntity.status(404).body(err("Handout not found."));
        AssignedHandout h = opt.get();

        byte[] bytes = h.getStoredFilename() == null ? null
                : fileStorageService.load(FileStorageService.assignedKey(h.getStoredFilename()));
        if (bytes == null)
            return ResponseEntity.status(404).body(err("The handout file is missing. Please tell your researcher."));

        List<Map<String, String>> blocks = new ArrayList<>();
        for (DocumentTextExtractor.TextBlock b : DocumentTextExtractor.extractFormattedText(
                h.getOriginalFilename(), h.getContentType(), bytes)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("kind", b.kind.name());
            m.put("text", b.text);
            blocks.add(m);
        }
        String fullText = blocks.isEmpty()
                ? DocumentTextExtractor.extractText(h.getOriginalFilename(), h.getContentType(), bytes) : "";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("topic", h.getTopic());
        out.put("filename", h.getOriginalFilename());
        out.put("status", h.getStatus());
        out.put("blocks", blocks);
        out.put("fullText", fullText);
        return ResponseEntity.ok(out);
    }

    /**
     * Sends the assigned handout through the normal upload pipeline (text -> Claude knowledge extraction,
     * summary, categorization, quiz questions). Body: { "requestId": "<uuid for the SSE progress stream>" }.
     * Consent is enforced by AuthInterceptor for this path.
     */
    @PostMapping("/api/assigned-handouts/{id}/process")
    public ResponseEntity<Map<String, Object>> process(
            @PathVariable Long id,
            @RequestBody(required = false) ProcessRequest body,
            HttpSession session) {
        final String rid = body == null ? null : body.requestId;
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            uploadProgressService.complete(rid, false, "Please log in first.");
            return ResponseEntity.status(401).body(err("Please log in first."));
        }

        Optional<AssignedHandout> opt = repo.findById(id);
        if (opt.isEmpty() || !email.equalsIgnoreCase(opt.get().getStudentEmail())) {
            uploadProgressService.complete(rid, false, "Handout not found.");
            return ResponseEntity.status(404).body(err("Handout not found."));
        }
        AssignedHandout h = opt.get();
        if (!"PENDING".equals(h.getStatus())) {
            uploadProgressService.complete(rid, false, "This handout was already sent.");
            return ResponseEntity.status(409).body(err("This handout was already sent. Open it from the Quiz Hub."));
        }
        if (!inFlight.add(id)) {
            uploadProgressService.complete(rid, false, "Already processing.");
            return ResponseEntity.status(409).body(err("This handout is already being processed."));
        }

        try {
            byte[] bytes = h.getStoredFilename() == null ? null
                    : fileStorageService.load(FileStorageService.assignedKey(h.getStoredFilename()));
            if (bytes == null) {
                uploadProgressService.complete(rid, false, "Handout file is missing.");
                return ResponseEntity.status(404).body(err("The handout file is missing. Please tell your researcher."));
            }

            ResponseEntity<Map<String, Object>> resp = materialController.processUploadedContent(
                    email, h.getTopic(), h.getOriginalFilename(), h.getContentType(), bytes.length, bytes, rid);

            Map<String, Object> out = resp.getBody();
            boolean ok = out != null && Boolean.TRUE.equals(out.get("success"));
            int generated = (ok && out.get("generatedQuestions") instanceof Number n) ? n.intValue() : 0;

            if (ok && generated > 0) {
                h.setStatus("PROCESSED");
                h.setProcessedAt(LocalDateTime.now());
                repo.save(h);
                return resp;
            }
            if (ok) {
                // Material saved but Claude produced no usable questions: keep it PENDING so they can retry.
                return ResponseEntity.ok(err("Claude could not produce usable questions this time. Please try again."));
            }
            return resp;
        } finally {
            inFlight.remove(id);
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private boolean isAdmin(HttpSession s) { return Boolean.TRUE.equals(s.getAttribute("isAdmin")); }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(err("Admin access required."));
    }

    private Map<String, Object> err(String msg) { return Map.of("success", false, "message", msg); }

    private static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    private static String toTitleCase(String input) {
        if (input == null || input.isBlank()) return input;
        StringBuilder result = new StringBuilder();
        for (String word : input.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(" ");
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    public static class ProcessRequest {
        public String topic;
        public String requestId;
    }
}
