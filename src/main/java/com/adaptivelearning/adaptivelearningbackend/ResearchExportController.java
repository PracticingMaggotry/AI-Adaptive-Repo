package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Admin-only CSV exports of the study dataset. Only enrolled participants appear, identified by code. */
@RestController
@RequestMapping("/api/admin/export")
public class ResearchExportController {

    @Autowired private AttemptRepository attemptRepository;
    @Autowired private QuestionExposureLogRepository exposureRepository;
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;
    @Autowired private ParticipantController participants;
    @Autowired private ReliabilityEventRepository reliabilityRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private SusResponseRepository susRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    // ── attempts.csv: one row per submitted quiz ──
    @GetMapping("/attempts.csv")
    public ResponseEntity<byte[]> attempts(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        Map<String, String> codes = participants.codeByEmail();

        StringBuilder sb = new StringBuilder();
        sb.append(row("attempt_id", "participant_code", "topic", "attempt_no", "submitted_at",
                "quiz_difficulty", "prev_quiz_difficulty", "difficulty_changed",
                "total_items", "credit_rounded", "score_pct", "prev_score_pct", "score_change",
                "next_difficulty", "duration_seconds"));

        Map<String, Integer> counter = new HashMap<>();
        Map<String, Attempt> prev = new HashMap<>();
        for (Attempt a : enrolledAttempts(codes)) {
            String key = key(a);
            int n = counter.merge(key, 1, Integer::sum);
            Attempt p = prev.get(key);
            boolean changed = p != null && !String.valueOf(p.getDifficulty()).equalsIgnoreCase(String.valueOf(a.getDifficulty()));
            sb.append(row(a.getId(), codes.get(lower(a.getStudentId())), a.getTopic(), n, a.getTimestamp(),
                    a.getDifficulty(), p == null ? null : p.getDifficulty(), p == null ? null : changed,
                    a.getTotalItems(), a.getCorrectAnswers(), round2(a.getPerformanceScore()),
                    p == null ? null : round2(p.getPerformanceScore()),
                    p == null ? null : round2(a.getPerformanceScore() - p.getPerformanceScore()),
                    a.getNextDiff(), a.getDurationSeconds()));
            prev.put(key, a);
        }
        return csvResponse("attempts", sb, session);
    }

    // ── attempt_questions.csv: one row per question answered in each attempt ──
    @GetMapping("/attempt_questions.csv")
    public ResponseEntity<byte[]> attemptQuestions(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        Map<String, String> codes = participants.codeByEmail();

        StringBuilder sb = new StringBuilder();
        sb.append(row("attempt_id", "participant_code", "topic", "attempt_no", "quiz_difficulty", "submitted_at",
                "position", "question_id", "question_type", "skill_category", "result",
                "credit_fraction", "essay_score", "student_answer", "correct_answer"));

        Map<String, Integer> counter = new HashMap<>();
        for (Attempt a : enrolledAttempts(codes)) {
            int n = counter.merge(key(a), 1, Integer::sum);
            if (a.getDetails() == null || a.getDetails().isBlank()) continue;
            try {
                JsonNode results = MAPPER.readTree(a.getDetails());
                if (!results.isArray()) continue;
                int pos = 0;
                for (JsonNode q : results) {
                    pos++;
                    sb.append(row(a.getId(), codes.get(lower(a.getStudentId())), a.getTopic(), n, a.getDifficulty(), a.getTimestamp(),
                            pos, nodeVal(q, "questionId"), q.path("type").asText(""),
                            q.path("weaknessCategory").asText(""), q.path("result").asText(""),
                            nodeVal(q, "creditFraction"), nodeVal(q, "essayScore"),
                            q.path("yourAnswer").asText(""), nodeVal(q, "correctAnswer")));
                }
            } catch (Exception e) {
                System.err.println("Export: could not parse details for attempt " + a.getId() + ": " + e.getMessage());
            }
        }
        return csvResponse("attempt_questions", sb, session);
    }

    // ── question_exposures.csv: every question served (append-only log, item 1) ──
    @GetMapping("/question_exposures.csv")
    public ResponseEntity<byte[]> exposures(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        Map<String, String> codes = participants.codeByEmail();

        StringBuilder sb = new StringBuilder();
        sb.append(row("serve_id", "participant_code", "topic", "difficulty_requested", "difficulty_served",
                "question_id", "position", "question_type", "question_text",
                "option_a", "option_b", "option_c", "option_d", "correct_answer", "payload", "served_at"));

        List<QuestionExposureLog> all = exposureRepository.findAll();
        all.sort(Comparator.comparing(QuestionExposureLog::getServedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(QuestionExposureLog::getPositionInSet));
        for (QuestionExposureLog e : all) {
            String code = codes.get(lower(e.getStudentId()));
            if (code == null) continue;
            sb.append(row(e.getServeId(), code, e.getTopic(), e.getDifficultyRequested(),
                    e.getDifficultyServed(), e.getQuestionId(), e.getPositionInSet() + 1, e.getQuestionType(),
                    e.getQuestionText(), e.getOptionA(), e.getOptionB(), e.getOptionC(), e.getOptionD(),
                    e.getCorrectAnswer(), e.getPayload(), e.getServedAt()));
        }
        return csvResponse("question_exposures", sb, session);
    }

    // ── reliability.csv: system-level API/limit/yield events (no student identifiers) ──
    @GetMapping("/reliability.csv")
    public ResponseEntity<byte[]> reliability(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();

        StringBuilder sb = new StringBuilder();
        sb.append(row("event_id", "occurred_at", "kind", "task", "model", "latency_ms", "success",
                "http_status", "input_tokens", "output_tokens", "items_kept", "items_dropped", "detail"));

        List<ReliabilityEvent> all = reliabilityRepository.findAll();
        all.sort(Comparator.comparing(ReliabilityEvent::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder())));
        for (ReliabilityEvent e : all) {
            sb.append(row(e.getId(), e.getOccurredAt(), e.getKind(), e.getTask(), e.getModel(),
                    e.getLatencyMs(), e.getSuccess(), e.getHttpStatus(), e.getInputTokens(),
                    e.getOutputTokens(), e.getItemsKept(), e.getItemsDropped(), e.getDetail()));
        }
        return csvResponse("reliability", sb, session);
    }

    // ── sus.csv: System Usability Scale responses, one row per participant code ──
    @GetMapping("/sus.csv")
    public ResponseEntity<byte[]> sus(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        StringBuilder sb = new StringBuilder();
        sb.append(row("participant_code", "submitted_at", "q1", "q2", "q3", "q4", "q5",
                "q6", "q7", "q8", "q9", "q10", "sus_score"));
        List<SusResponse> all = new ArrayList<>(susRepository.findAll());
        all.sort(Comparator.comparing(SusResponse::getParticipantCode));
        for (SusResponse s : all) {
            int[] a = s.answers();
            sb.append(row(s.getParticipantCode(), s.getSubmittedAt(), a[0], a[1], a[2], a[3], a[4],
                    a[5], a[6], a[7], a[8], a[9], s.getSusScore()));
        }
        return csvResponse("sus", sb, session);
    }

    // ── question-quality review export (item 6) ──

    private static class SampleSet {
        final Map<String, List<QuestionExposureLog>> byHandout = new TreeMap<>(); // handout key -> sampled questions
        final Map<String, Material> material = new HashMap<>();
        final List<String> keys = new ArrayList<>();                               // sorted; index = handout number
    }

    private SampleSet buildSample(int perHandout) {
        Map<String, String> codes = participants.codeByEmail();
        List<QuestionExposureLog> all = exposureRepository.findAll();
        all.sort(Comparator.comparing(QuestionExposureLog::getServedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        Set<Long> seen = new HashSet<>();
        Map<String, Material> matCache = new HashMap<>();
        Map<String, List<QuestionExposureLog>> candidates = new TreeMap<>();
        SampleSet out = new SampleSet();

        for (QuestionExposureLog e : all) {
            if (e.getQuestionId() == null || !codes.containsKey(lower(e.getStudentId()))) continue;
            if (!seen.add(e.getQuestionId())) continue; // first exposure only
            String mk = lower(e.getStudentId()) + "|" + lower(e.getTopic());
            Material m = matCache.computeIfAbsent(mk, k ->
                    materialRepository.findByUploadedByAndTopicIgnoreCase(e.getStudentId(), e.getTopic())
                            .stream().findFirst().orElse(null));
            if (m == null) continue; // handout no longer exists
            String hk = (m.getContentHash() != null && !m.getContentHash().isBlank()) ? m.getContentHash() : "m" + m.getId();
            candidates.computeIfAbsent(hk, k -> new ArrayList<>()).add(e);
            out.material.putIfAbsent(hk, m);
        }

        int idx = 0;
        for (Map.Entry<String, List<QuestionExposureLog>> en : candidates.entrySet()) {
            out.keys.add(en.getKey());
            out.byHandout.put(en.getKey(), pick(en.getValue(), perHandout, 42L + idx++));
        }
        return out;
    }

    /** Round-robin across question types, shuffled with a fixed seed so the sample is reproducible. */
    private static List<QuestionExposureLog> pick(List<QuestionExposureLog> pool, int n, long seed) {
        Map<String, List<QuestionExposureLog>> byType = new TreeMap<>();
        for (QuestionExposureLog q : pool) byType.computeIfAbsent(String.valueOf(q.getQuestionType()), k -> new ArrayList<>()).add(q);
        Random rnd = new Random(seed);
        for (List<QuestionExposureLog> l : byType.values()) {
            l.sort(Comparator.comparing(QuestionExposureLog::getQuestionId));
            Collections.shuffle(l, rnd);
        }
        List<QuestionExposureLog> picked = new ArrayList<>();
        while (picked.size() < n) {
            boolean any = false;
            for (List<QuestionExposureLog> l : byType.values()) {
                if (!l.isEmpty() && picked.size() < n) { picked.add(l.remove(0)); any = true; }
            }
            if (!any) break;
        }
        return picked;
    }

    private static String handoutLabel(int index) { return String.format("H%02d", index + 1); }

    @GetMapping("/review_questions.csv")
    public ResponseEntity<byte[]> reviewQuestions(@RequestParam(defaultValue = "10") int perHandout, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        SampleSet s = buildSample(Math.max(1, Math.min(perHandout, 50)));

        StringBuilder sb = new StringBuilder();
        sb.append(row("review_id", "handout_id", "question_id", "question_type", "question_text",
                "option_a", "option_b", "option_c", "option_d", "answer_key", "payload_with_key",
                "rater_id", "key_is_correct_YN", "supported_by_handout_YN", "single_best_answer_YN",
                "wording_clear_YN", "difficulty_fit_TooEasy_OK_TooHard", "notes"));

        int r = 0;
        for (int i = 0; i < s.keys.size(); i++) {
            for (QuestionExposureLog q : s.byHandout.get(s.keys.get(i))) {
                sb.append(row(String.format("R%03d", ++r), handoutLabel(i), q.getQuestionId(), q.getQuestionType(),
                        q.getQuestionText(), q.getOptionA(), q.getOptionB(), q.getOptionC(), q.getOptionD(),
                        q.getCorrectAnswer(), q.getPayload(), null, null, null, null, null, null, null));
            }
        }
        return csvResponse("review_questions", sb, session);
    }

    @GetMapping("/review_sources.txt")
    public ResponseEntity<byte[]> reviewSources(@RequestParam(defaultValue = "10") int perHandout, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        SampleSet s = buildSample(Math.max(1, Math.min(perHandout, 50)));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.keys.size(); i++) {
            Material m = s.material.get(s.keys.get(i));
            sb.append("==================== ").append(handoutLabel(i)).append(" ====================\n");
            sb.append("Topic: ").append(m.getTopic()).append("\nFile: ").append(m.getOriginalFilename()).append("\n\n");
            sb.append(handoutText(m)).append("\n\n");
        }
        adminActivityLogRepository.save(new AdminActivityLog("export", "Exported question review sources", null,
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"review_sources_" + LocalDate.now() + ".txt\"")
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String handoutText(Material m) {
        try {
            if (m.getStoredFilename() != null) {
                byte[] bytes = fileStorageService.load(FileStorageService.handoutKey(m.getStoredFilename()));
                if (bytes != null) {
                    List<DocumentTextExtractor.TextBlock> blocks =
                            DocumentTextExtractor.extractFormattedText(m.getOriginalFilename(), m.getContentType(), bytes);
                    if (!blocks.isEmpty()) {
                        return blocks.stream().map(b -> b.text).collect(Collectors.joining("\n"));
                    }
                    String plain = DocumentTextExtractor.extractText(m.getOriginalFilename(), m.getContentType(), bytes);
                    if (plain != null && !plain.isBlank()) return plain;
                }
            }
        } catch (Exception e) {
            System.err.println("Review export: text extraction failed for material " + m.getId() + ": " + e.getMessage());
        }
        return "(Full text unavailable. Stored preview:)\n" + (m.getExtractedPreview() == null ? "" : m.getExtractedPreview());
    }

    // ── helpers ──

    private List<Attempt> enrolledAttempts(Map<String, String> codes) {
        return attemptRepository.findAll().stream()
                .filter(a -> codes.containsKey(lower(a.getStudentId())))
                .sorted(Comparator.comparing(Attempt::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Attempt::getId))
                .collect(Collectors.toList());
    }

    private static String key(Attempt a) { return lower(a.getStudentId()) + "|" + lower(a.getTopic()); }
    private static String lower(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static Object nodeVal(JsonNode q, String field) {
        JsonNode n = q.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        return n.isNumber() ? (Object) n.numberValue() : n.asText("");
    }

    private ResponseEntity<byte[]> csvResponse(String name, StringBuilder body, HttpSession session) {
        adminActivityLogRepository.save(new AdminActivityLog("export",
                "Exported research dataset: " + name, null, (String) session.getAttribute("loggedInUserEmail")));
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; // so Excel reads UTF-8
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + data.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(data, 0, out, bom.length, data.length);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + name + "_" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(out);
    }

    private static String row(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        return sb.append("\r\n").toString();
    }

    /** RFC 4180 quoting, plus a leading ' on text cells that a spreadsheet could read as a formula. */
    private static String cell(Object v) {
        if (v == null) return "";
        String s = (v instanceof LocalDateTime) ? v.toString() : String.valueOf(v);
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
