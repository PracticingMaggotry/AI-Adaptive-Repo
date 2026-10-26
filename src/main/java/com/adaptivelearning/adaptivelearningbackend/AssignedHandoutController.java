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
 *  - Admin uploads a handout for chosen participants. By default (prepare=true) it is sent to Anthropic ONCE,
 *    in the background, to build a baseline quiz for the first participant; that quiz is then copied to every
 *    other assigned participant, so they all start from identical questions and see the quiz straight away.
 *  - If preparation is switched off or fails, the row stays PENDING and the participant views the handout
 *    (re-typeset text) and presses "send" on the quiz page, which runs the same pipeline as a normal upload
 *    (see MaterialController#processUploadedContent).
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

    /** One preparation job at a time, so simultaneous assignments never fire parallel Claude calls. */
    private static final java.util.concurrent.ExecutorService prepareExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "assigned-handout-prepare");
                t.setDaemon(true);
                return t;
            });

    /** A PREPARING row older than this is treated as PENDING (e.g. the server restarted mid-job). */
    private static final long PREPARING_TIMEOUT_MINUTES = 15;

    private String effectiveStatus(AssignedHandout h) {
        if ("PREPARING".equals(h.getStatus()) && h.getAssignedAt() != null
                && h.getAssignedAt().isBefore(LocalDateTime.now().minusMinutes(PREPARING_TIMEOUT_MINUTES))) {
            return "PENDING";
        }
        return h.getStatus();
    }

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
            m.put("status", effectiveStatus(h));
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
            @RequestParam(value = "prepare", defaultValue = "true") boolean prepare,
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
        final String contentType = file.getContentType();   // read now: the upload is gone once the request ends
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
        List<Long> assignedIds = new ArrayList<>();
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
            AssignedHandout row = new AssignedHandout(user.getEmail(), cleanedTopic, safeName, storedName,
                    file.getContentType(), file.getSize(), adminEmail);
            if (prepare) row.setStatus("PREPARING");
            assignedIds.add(repo.save(row).getId());
            assigned.add(user.getEmail());
        }

        if (assigned.isEmpty()) {
            fileStorageService.delete(FileStorageService.assignedKey(storedName));
            return ResponseEntity.badRequest().body(err("Nobody was assigned. " + String.join("; ", skipped)));
        }

        adminActivityLogRepository.save(new AdminActivityLog("assign-handout",
                "Assigned handout \"" + cleanedTopic + "\" to " + assigned.size() + " participant(s)",
                safeName, adminEmail));

        if (prepare) {
            final List<Long> ids = List.copyOf(assignedIds);
            prepareExecutor.submit(() -> prepareBaselineQuiz(ids, cleanedTopic, safeName, contentType, bytes, adminEmail));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("assigned", assigned.size());
        out.put("skipped", skipped);
        out.put("message", prepare
                ? "Assigned to " + assigned.size() + " participant(s). The handout is being sent to Anthropic once to build "
                  + "one baseline quiz that is copied to everyone assigned. This usually takes 1-2 minutes: refresh the list "
                  + "and each row turns Processed when its quiz is ready."
                : "Assigned to " + assigned.size() + " participant(s). Nothing has been sent to Anthropic yet.");
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

    /** Puts a PROCESSED (or stuck PREPARING) assignment back to PENDING so the participant can send it themselves. */
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
            m.put("status", effectiveStatus(h));
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
        out.put("status", effectiveStatus(h));
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
        if ("PREPARING".equals(effectiveStatus(h))) {
            uploadProgressService.complete(rid, false, "Your quiz is still being prepared.");
            return ResponseEntity.status(409).body(err("Your quiz is still being prepared. Please check the Quiz Hub again in a minute."));
        }
        if (!"PENDING".equals(effectiveStatus(h))) {
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

    // ───────────────────── baseline preparation ─────────────────────

    /**
     * Background job started by assign(): sends the handout through the normal pipeline ONCE (as the first
     * assigned participant), then copies that participant's quiz to everyone else. Anything that does not
     * finish goes back to PENDING, which falls back to the participant pressing "send" themselves.
     */
    private void prepareBaselineQuiz(List<Long> ids, String topic, String filename, String contentType,
                                     byte[] bytes, String adminEmail) {
        List<AssignedHandout> rows = new ArrayList<>(repo.findAllById(ids));
        if (rows.isEmpty()) return;
        String generator = rows.get(0).getStudentEmail();
        String detail = filename;
        try {
            ResponseEntity<Map<String, Object>> resp = materialController.processUploadedContent(
                    generator, topic, filename, contentType, bytes.length, bytes, null);
            Map<String, Object> out = resp.getBody();
            boolean ok = out != null && Boolean.TRUE.equals(out.get("success"));
            int generated = (ok && out.get("generatedQuestions") instanceof Number n) ? n.intValue() : 0;

            if (generated > 0) {
                setProcessed(rows.get(0).getId());
                int copied = 0;
                for (int i = 1; i < rows.size(); i++) {
                    AssignedHandout row = rows.get(i);
                    if (materialController.cloneBaselineQuiz(generator, row.getStudentEmail(), topic) > 0) {
                        setProcessed(row.getId());
                        copied++;
                    }
                }
                adminActivityLogRepository.save(new AdminActivityLog("prepare-handout",
                        "Prepared baseline quiz for \"" + topic + "\": " + generated + " questions, copied to "
                                + copied + " other participant(s)", detail, adminEmail));
            } else {
                adminActivityLogRepository.save(new AdminActivityLog("prepare-handout-failed",
                        "Could not prepare baseline quiz for \"" + topic + "\" (no usable questions). "
                                + "Participants can still send it themselves.", detail, adminEmail));
            }
        } catch (Exception e) {
            System.err.println("Baseline quiz preparation failed for \"" + topic + "\": " + e.getMessage());
            adminActivityLogRepository.save(new AdminActivityLog("prepare-handout-failed",
                    "Could not prepare baseline quiz for \"" + topic + "\": " + e.getMessage(), detail, adminEmail));
        } finally {
            // Whatever did not finish goes back to PENDING so the participant can still send it themselves.
            for (AssignedHandout row : repo.findAllById(ids)) {
                if ("PREPARING".equals(row.getStatus())) {
                    row.setStatus("PENDING");
                    repo.save(row);
                }
            }
        }
    }

    /** Re-reads the row first, so an assignment the admin deleted meanwhile is never re-created. */
    private void setProcessed(Long id) {
        repo.findById(id).ifPresent(r -> {
            r.setStatus("PROCESSED");
            r.setProcessedAt(LocalDateTime.now());
            repo.save(r);
        });
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
