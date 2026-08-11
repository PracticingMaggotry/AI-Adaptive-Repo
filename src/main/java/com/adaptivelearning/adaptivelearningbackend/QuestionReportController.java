package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Handles students reporting bad quiz questions and admins reviewing those reports. */
@RestController
public class QuestionReportController {

    @Autowired private QuestionReportRepository reportRepository;
    @Autowired private QuestionRepository       questionRepository;
    @Autowired private AdminActivityLogRepository activityLogRepository;

    // ── Student: file a report ─────────────────────────────────────────

    @PostMapping("/api/quiz/report")
    public ResponseEntity<Map<String, Object>> fileReport(
            @RequestBody ReportRequest req,
            HttpSession session) {

        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank())
            return ResponseEntity.status(401).body(err("Please log in first."));

        if (req.questionId == null)
            return ResponseEntity.badRequest().body(err("questionId is required."));

        Set<String> allowed = Set.of("WRONG_ANSWER", "MISLEADING", "OUT_OF_SCOPE", "DUPLICATE", "OTHER");
        String reason = req.reason == null ? "" : req.reason.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(reason))
            return ResponseEntity.badRequest().body(err("Invalid reason. Choose one of: " + allowed));

        if (reportRepository.existsByQuestionIdAndReporterEmail(req.questionId, email)) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "alreadyReported", true,
                    "message", "You've already reported this question."
            ));
        }

        Optional<Question> qOpt = questionRepository.findById(req.questionId);
        if (qOpt.isEmpty())
            return ResponseEntity.status(404).body(err("Question not found."));

        Question q = qOpt.get();

        QuestionReport report = new QuestionReport(
                req.questionId,
                email,
                q.getTopic(),
                q.getQuestionText(),
                reason,
                req.notes
        );
        reportRepository.save(report);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Thank you — your report has been sent to an admin for review."
        ));
    }

    /** Lets the frontend show "already reported" state on re-render. */
    @GetMapping("/api/quiz/report/check")
    public ResponseEntity<Map<String, Object>> checkReported(
            @RequestParam Long questionId,
            HttpSession session) {

        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank())
            return ResponseEntity.status(401).body(err("Please log in first."));

        boolean already = reportRepository.existsByQuestionIdAndReporterEmail(questionId, email);
        return ResponseEntity.ok(Map.of("alreadyReported", already));
    }

    // ── Admin: list reports ────────────────────────────────────────────

    @GetMapping("/api/admin/reports")
    public ResponseEntity<Map<String, Object>> listReports(
            @RequestParam(defaultValue = "ALL") String status,
            HttpSession session) {

        if (!isAdmin(session)) return forbidden();

        List<QuestionReport> raw = status.equalsIgnoreCase("ALL")
                ? reportRepository.findAllByOrderByReportedAtDesc()
                : reportRepository.findByStatusOrderByReportedAtDesc(status.toUpperCase(Locale.ROOT));

        Map<Long, Long> countsByQuestion = raw.stream()
                .collect(Collectors.groupingBy(QuestionReport::getQuestionId, Collectors.counting()));

        List<Map<String, Object>> items = raw.stream()
                .map(r -> toMap(r, countsByQuestion.getOrDefault(r.getQuestionId(), 1L)))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", items.size(),
                "reports", items
        ));
    }

    @GetMapping("/api/admin/reports/pending-count")
    public ResponseEntity<Map<String, Object>> pendingCount(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ResponseEntity.ok(Map.of("count", reportRepository.countPending()));
    }

    // ── Admin: review a report ─────────────────────────────────────────

    /** Marks a report FIXED/DISMISSED/DELETED; DELETED also removes the underlying question. */
    @PostMapping("/api/admin/reports/{id}/review")
    public ResponseEntity<Map<String, Object>> reviewReport(
            @PathVariable Long id,
            @RequestBody ReviewRequest req,
            HttpSession session) {

        if (!isAdmin(session)) return forbidden();

        String adminEmail = (String) session.getAttribute("loggedInUserEmail");
        Optional<QuestionReport> reportOpt = reportRepository.findById(id);
        if (reportOpt.isEmpty())
            return ResponseEntity.status(404).body(err("Report not found."));

        QuestionReport report = reportOpt.get();

        Set<String> validActions = Set.of("FIXED", "DISMISSED", "DELETED");
        String action = req.action == null ? "" : req.action.trim().toUpperCase(Locale.ROOT);
        if (!validActions.contains(action))
            return ResponseEntity.badRequest().body(err("action must be FIXED, DISMISSED, or DELETED."));

        report.setStatus(action);
        report.setReviewedAt(LocalDateTime.now());
        report.setReviewedBy(adminEmail);
        if (req.adminNote != null && !req.adminNote.isBlank()) {
            report.setAdminNote(req.adminNote.trim());
        }
        reportRepository.save(report);

        // DELETE also removes the question and resolves every other pending report on it.
        if ("DELETED".equals(action)) {
            Long qid = report.getQuestionId();
            List<QuestionReport> siblings = reportRepository.findByQuestionIdOrderByReportedAtDesc(qid);
            for (QuestionReport sibling : siblings) {
                if (!sibling.getId().equals(id) && "PENDING".equals(sibling.getStatus())) {
                    sibling.setStatus("DELETED");
                    sibling.setReviewedAt(LocalDateTime.now());
                    sibling.setReviewedBy(adminEmail);
                    sibling.setAdminNote("Question deleted by admin.");
                }
            }
            reportRepository.saveAll(siblings);
            questionRepository.deleteById(qid);

            activityLogRepository.save(new AdminActivityLog(
                    "delete-question",
                    "Deleted reported question #" + qid + " (" + abbrev(report.getQuestionText()) + ")",
                    "Topic: " + report.getTopic() + " · via Report #" + id,
                    adminEmail
            ));
        }

        String friendlyAction = switch (action) {
            case "FIXED"     -> "marked as fixed";
            case "DISMISSED" -> "dismissed";
            case "DELETED"   -> "resolved — question deleted";
            default          -> action;
        };

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Report " + friendlyAction + "."
        ));
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Map<String, Object> toMap(QuestionReport r, long reportCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            r.getId());
        m.put("questionId",    r.getQuestionId());
        m.put("topic",         r.getTopic());
        m.put("questionText",  r.getQuestionText());
        m.put("reason",        r.getReason());
        m.put("notes",         r.getNotes());
        m.put("status",        r.getStatus());
        m.put("reporterEmail", r.getReporterEmail());
        m.put("reportedAt",    r.getReportedAt() == null ? null : r.getReportedAt().toString());
        m.put("reviewedAt",    r.getReviewedAt() == null ? null : r.getReviewedAt().toString());
        m.put("reviewedBy",    r.getReviewedBy());
        m.put("adminNote",     r.getAdminNote());
        m.put("reportCount",   reportCount);
        questionRepository.findById(r.getQuestionId()).ifPresent(q -> {
            m.put("currentQuestionText",  q.getQuestionText());
            m.put("correctAnswer",        q.getCorrectAnswer());
            m.put("type",                 q.getType());
            m.put("optionA",              q.getOptionA());
            m.put("optionB",              q.getOptionB());
            m.put("optionC",              q.getOptionC());
            m.put("optionD",              q.getOptionD());
            m.put("hint",                 q.getHint());
            m.put("explanation",          q.getExplanation());
            m.put("questionStillExists",  true);
        });
        if (!m.containsKey("questionStillExists")) {
            m.put("questionStillExists", false);
        }
        return m;
    }

    private String abbrev(String text) {
        if (text == null) return "";
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private boolean isAdmin(HttpSession session) {
        Object flag = session.getAttribute("isAdmin");
        return flag instanceof Boolean && (Boolean) flag;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(err("Admin access required."));
    }

    private Map<String, Object> err(String msg) {
        return Map.of("success", false, "message", msg);
    }

    // ── Request bodies ─────────────────────────────────────────────────

    public static class ReportRequest {
        public Long   questionId;
        public String reason;
        public String notes;
    }

    public static class ReviewRequest {
        public String action;
        public String adminNote;
    }
}