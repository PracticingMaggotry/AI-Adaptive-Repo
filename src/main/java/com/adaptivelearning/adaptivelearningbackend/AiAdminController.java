package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.AiScheduler;
import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.adaptivelearning.adaptivelearningbackend.ai.TierCalibration;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Admin-only visibility into the AI layer: are the difficulty tiers calibrated, what does AI cost per task and per
 * student, and is the concurrency gate healthy.
 */
@RestController
@RequestMapping("/api/admin/ai")
public class AiAdminController {

    @Autowired private QuestionResponseRepository responseRepository;
    @Autowired private AiUsageRepository usageRepository;
    @Autowired private AiGateway aiGateway;

    private static boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    /** Are Easy/Medium/Hard questions measurably different in difficulty? Raw and within-student (paired) views. */
    @GetMapping("/calibration")
    public ResponseEntity<?> calibration(@RequestParam(defaultValue = "90") int days, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 3650)));
        List<TierCalibration.Outcome> outcomes = new ArrayList<>();
        for (QuestionResponse r : responseRepository.findByAnsweredAtAfter(since)) {
            outcomes.add(new TierCalibration.Outcome(r.getStudentId(), r.getScopeId() == null ? 0L : r.getScopeId(),
                    r.getTier(), r.getQuestionType(), r.getCredit(),
                    r.getTimeSpentSeconds() == null ? 0 : r.getTimeSpentSeconds()));
        }
        return ResponseEntity.ok(TierCalibration.analyse(outcomes));
    }

    /**
     * Observed facility per demand level (1-5) next to its prior logit difficulty. Use it to re-tune
     * AiPolicy.DEMAND_LOGIT: a higher level should show a lower facility.
     */
    @GetMapping("/calibration/levels")
    public ResponseEntity<?> levelCalibration(@RequestParam(defaultValue = "90") int days, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 3650)));
        int levels = AiPolicy.DEMAND_LOGIT.length;
        int[] n = new int[levels];
        double[] credit = new double[levels];
        for (QuestionResponse r : responseRepository.findByAnsweredAtAfter(since)) {
            Integer l = r.getDemandLevel();
            if (l == null || l < 1 || l > levels) continue;
            n[l - 1]++;
            credit[l - 1] += r.getCredit();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", i + 1);
            row.put("priorLogit", AiPolicy.DEMAND_LOGIT[i]);
            row.put("responses", n[i]);
            row.put("facility", n[i] == 0 ? null : Math.round(1000.0 * credit[i] / n[i]) / 1000.0);
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** Token and cost totals per day/task/model, plus the heaviest students, for the last N days. */
    @GetMapping("/usage")
    public ResponseEntity<?> usage(@RequestParam(defaultValue = "7") int days, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        LocalDate from = AppTime.today().minusDays(Math.max(0, Math.min(days, 365)));
        List<AiUsage> rows = usageRepository.findByDayGreaterThanEqual(from);

        Map<String, long[]> byTask = new TreeMap<>();      // calls, in, out, cacheRead, micros
        Map<String, Long> byStudent = new HashMap<>();
        long total = 0;
        for (AiUsage u : rows) {
            long[] a = byTask.computeIfAbsent(u.getTask() + " / " + u.getModel(), k -> new long[5]);
            a[0] += u.getCalls(); a[1] += u.getInputTokens(); a[2] += u.getOutputTokens();
            a[3] += u.getCacheReadTokens(); a[4] += u.getCostMicros();
            byStudent.merge(u.getStudentId(), u.getCostMicros(), Long::sum);
            total += u.getCostMicros();
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        byTask.forEach((k, a) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskAndModel", k); m.put("calls", a[0]); m.put("inputTokens", a[1]); m.put("outputTokens", a[2]);
            m.put("cacheReadTokens", a[3]); m.put("costUsd", a[4] / 1_000_000.0);
            tasks.add(m);
        });
        List<Map<String, Object>> top = new ArrayList<>();
        byStudent.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
                .forEach(e -> top.add(Map.of("student", e.getKey(), "costUsd", e.getValue() / 1_000_000.0)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromDay", from.toString());
        out.put("totalCostUsd", total / 1_000_000.0);
        out.put("byTask", tasks);
        out.put("topStudents", top);
        return ResponseEntity.ok(out);
    }

    /** Live state of the concurrency gate. */
    @GetMapping("/status")
    public ResponseEntity<?> status(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        AiScheduler.Stats s = aiGateway.stats();
        return ResponseEntity.ok(s);
    }
}
