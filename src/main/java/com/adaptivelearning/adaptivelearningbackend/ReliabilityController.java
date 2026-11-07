package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin-only, read-only reliability summary for Admin &gt; System &gt; Reliability.
 * All arithmetic lives in {@link ReliabilityMetrics}; this class only authorises, loads and maps rows.
 * Like the CSV export, the data is system-level and contains no student identifiers.
 */
@RestController
@RequestMapping("/api/admin/reliability")
public class ReliabilityController {

    /** Safety cap on rows read per request. If it is hit, the response says so (truncated=true). */
    static final int MAX_ROWS = 50_000;

    @Autowired private ReliabilityEventRepository repository;

    private boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(defaultValue = "7d") String range, HttpSession session) {
        if (!isAdmin(session)) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin access required."));
        }
        ReliabilityMetrics.Range parsed = ReliabilityMetrics.Range.parse(range);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "range must be one of 24h, 7d, 30d, all."));
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime first = parsed == ReliabilityMetrics.Range.ALL
                ? repository.findFirstByOrderByOccurredAtAsc().map(ReliabilityEvent::getOccurredAt).orElse(null)
                : null;
        ReliabilityMetrics.Plan plan = ReliabilityMetrics.plan(parsed, now, first);

        List<ReliabilityEvent> events = repository.findByOccurredAtGreaterThanEqual(
                plan.loadFrom(), PageRequest.of(0, MAX_ROWS + 1, Sort.by(Sort.Direction.DESC, "occurredAt")));
        boolean truncated = events.size() > MAX_ROWS;
        if (truncated) events = events.subList(0, MAX_ROWS);

        List<ReliabilityMetrics.Row> rows = new ArrayList<>(events.size());
        for (ReliabilityEvent e : events) {
            rows.add(new ReliabilityMetrics.Row(e.getOccurredAt(), e.getKind(), e.getTask(), e.getLatencyMs(),
                    e.getSuccess(), e.getHttpStatus(), e.getInputTokens(), e.getOutputTokens(),
                    e.getItemsKept(), e.getItemsDropped(), e.getDetail()));
        }
        return ResponseEntity.ok(ReliabilityMetrics.summarize(plan, rows, now, truncated));
    }
}
