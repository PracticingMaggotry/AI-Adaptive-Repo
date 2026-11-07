package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ReliabilityMetrics.Plan;
import com.adaptivelearning.adaptivelearningbackend.ReliabilityMetrics.Range;
import com.adaptivelearning.adaptivelearningbackend.ReliabilityMetrics.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReliabilityMetricsTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-07T14:35:00");

    private static Row call(LocalDateTime at, String task, long latency, boolean ok, int status) {
        return new Row(at, "API_CALL", task, latency, ok, status, 100, 50, null, null, ok ? null : "boom");
    }
    private static Row yieldEvent(LocalDateTime at, String task, int kept, int dropped) {
        return new Row(at, "QUESTION_YIELD", task, null, kept > 0, null, null, null, kept, dropped, null);
    }
    private static Row pipelineFailure(LocalDateTime at, String task) {
        return new Row(at, "PIPELINE_FAILURE", task, null, false, null, null, null, null, null, "failed");
    }
    private static Row limitBlock(LocalDateTime at, String action) {
        return new Row(at, "LIMIT_BLOCK", action, null, false, null, null, null, null, null, "limit=3");
    }

    private static Plan plan24h() { return ReliabilityMetrics.plan(Range.H24, NOW, null); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) { return (List<Map<String, Object>>) o; }

    private static String status(Map<String, Object> summary) { return (String) map(summary.get("health")).get("status"); }

    private static List<Row> calls(int ok, int failed, long latency) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < ok; i++) rows.add(call(NOW.minusMinutes(300 - i), "lesson", latency, true, 200));
        for (int i = 0; i < failed; i++) rows.add(call(NOW.minusMinutes(100 - i), "lesson", 900, false, 529));
        return rows;
    }

    @Test
    void emptyWindowIsNoDataButStillHasEveryBucket() {
        Map<String, Object> s = ReliabilityMetrics.summarize(plan24h(), List.of(), NOW, false);
        assertEquals("NO_DATA", status(s));
        assertEquals(24, list(s.get("series")).size());
        assertEquals(0, map(s.get("api")).get("calls"));
        assertNull(map(s.get("api")).get("successRate"));
    }

    @Test
    void windowsAreAlignedToBucketBoundaries() {
        Plan h = ReliabilityMetrics.plan(Range.H24, NOW, null);
        assertEquals(LocalDateTime.parse("2026-11-07T15:00:00"), h.to());
        assertEquals(LocalDateTime.parse("2026-11-06T15:00:00"), h.from());
        assertEquals(LocalDateTime.parse("2026-11-05T15:00:00"), h.previousFrom());

        Plan d = ReliabilityMetrics.plan(Range.D7, NOW, null);
        assertEquals(LocalDateTime.parse("2026-11-07T18:00:00"), d.to());
        assertEquals(28, d.bucketCount());
        assertEquals(360, d.stepMinutes());

        Plan all = ReliabilityMetrics.plan(Range.ALL, NOW, NOW.minusDays(3));
        assertEquals(1440, all.stepMinutes());
        assertNull(all.previousFrom());
        assertEquals(60, ReliabilityMetrics.plan(Range.ALL, NOW, NOW.minusHours(5)).stepMinutes());
        assertEquals(10080, ReliabilityMetrics.plan(Range.ALL, NOW, NOW.minusDays(200)).stepMinutes());
    }

    @Test
    void healthyWhenEverythingSucceeds() {
        Map<String, Object> s = ReliabilityMetrics.summarize(plan24h(), calls(20, 0, 5_000), NOW, false);
        assertEquals("HEALTHY", status(s));
        assertEquals(1.0, (Double) map(s.get("api")).get("successRate"), 1e-9);
        assertTrue(list(map(s.get("health")).get("reasons")).isEmpty());
    }

    @Test
    void successRateThresholds() {
        // 18/20 = 90% -> not below the critical line, but below the healthy line
        assertEquals("DEGRADED", status(ReliabilityMetrics.summarize(plan24h(), calls(18, 2, 5_000), NOW, false)));
        // 17/20 = 85% -> critical
        assertEquals("CRITICAL", status(ReliabilityMetrics.summarize(plan24h(), calls(17, 3, 5_000), NOW, false)));
    }

    @Test
    void tooFewCallsAreNotJudged() {
        Map<String, Object> s = ReliabilityMetrics.summarize(plan24h(), calls(2, 2, 5_000), NOW, false);
        assertEquals("INSUFFICIENT_DATA", status(s));
        assertEquals("UNKNOWN", map(map(s.get("health")).get("levels")).get("success"));
    }

    @Test
    void failingStreakIsCriticalEvenWhenTheWindowRateLooksFine() {
        // 100 successes then 3 failures in a row: 97% overall (degraded by rate), but a live outage.
        Map<String, Object> s = ReliabilityMetrics.summarize(plan24h(), calls(100, 3, 5_000), NOW, false);
        assertEquals("CRITICAL", status(s));
        Map<String, Object> live = map(s.get("live"));
        assertEquals("failing", live.get("streakType"));
        assertEquals(3, live.get("streakCount"));
        assertNotNull(live.get("lastSuccessAt"));
    }

    @Test
    void latencyPercentilesUseNearestRankOnSuccessfulCallsOnly() {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) rows.add(call(NOW.minusMinutes(200 - i), "lesson", i * 100L, true, 200));
        rows.add(call(NOW.minusMinutes(5), "lesson", 999_999, false, 0)); // failed call must not skew latency
        Map<String, Object> lat = map(map(ReliabilityMetrics.summarize(plan24h(), rows, NOW, false).get("api")).get("latencyMs"));
        assertEquals(5_000L, lat.get("p50"));
        assertEquals(9_500L, lat.get("p95"));
        assertEquals(9_900L, lat.get("p99"));
        assertEquals(10_000L, lat.get("max"));
    }

    @Test
    void slowP95RaisesHealthLevels() {
        assertEquals("DEGRADED", status(ReliabilityMetrics.summarize(plan24h(), calls(20, 0, 40_000), NOW, false)));
        assertEquals("CRITICAL", status(ReliabilityMetrics.summarize(plan24h(), calls(20, 0, 70_000), NOW, false)));
    }

    @Test
    void failureCausesAreGroupedAndRankedWithPlainLabels() {
        List<Row> rows = new ArrayList<>(calls(20, 0, 1_000));
        rows.add(call(NOW.minusMinutes(10), "lesson", 10, false, 429));
        rows.add(call(NOW.minusMinutes(9), "lesson", 10, false, 429));
        rows.add(call(NOW.minusMinutes(8), "lesson", 10, false, 0));
        List<Map<String, Object>> causes = list(map(ReliabilityMetrics.summarize(plan24h(), rows, NOW, false).get("api")).get("failureCauses"));
        assertEquals(2, causes.size());
        assertEquals("Rate limited (HTTP 429)", causes.get(0).get("label"));
        assertEquals(2, causes.get(0).get("count"));
        assertEquals("No response (network or timeout)", causes.get(1).get("label"));
        assertEquals("Authentication error (HTTP 401)", ReliabilityMetrics.causeLabel(401));
        assertEquals("Provider overloaded (HTTP 529)", ReliabilityMetrics.causeLabel(529));
    }

    @Test
    void yieldCountsKeptAndDroppedAndEmptyBatches() {
        List<Row> rows = List.of(
                yieldEvent(NOW.minusMinutes(50), "adapted-quiz", 8, 2),
                yieldEvent(NOW.minusMinutes(40), "adapted-quiz", 0, 5),
                yieldEvent(NOW.minusMinutes(30), "targeted-quiz", 10, 0));
        Map<String, Object> y = map(ReliabilityMetrics.summarize(plan24h(), rows, NOW, false).get("yield"));
        assertEquals(3, y.get("batches"));
        assertEquals(18, y.get("kept"));
        assertEquals(7, y.get("dropped"));
        assertEquals(1, y.get("emptyBatches"));
        assertEquals(0.72, (Double) y.get("rate"), 1e-9);
    }

    @Test
    void limitBlocksAreReportedButNeverAffectHealth() {
        List<Row> rows = new ArrayList<>(calls(10, 0, 2_000));
        for (int i = 0; i < 50; i++) rows.add(limitBlock(NOW.minusMinutes(i + 1), i % 2 == 0 ? "quiz-submit" : "adapted-quiz"));
        Map<String, Object> s = ReliabilityMetrics.summarize(plan24h(), rows, NOW, false);
        assertEquals("HEALTHY", status(s));
        assertEquals(50, map(s.get("limits")).get("blocks"));
        assertEquals(2, list(map(s.get("limits")).get("byAction")).size());
    }

    @Test
    void pipelineFailureRateDrivesHealth() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(yieldEvent(NOW.minusMinutes(60 - i), "adapted-quiz", 10, 0));
        for (int i = 0; i < 2; i++) rows.add(pipelineFailure(NOW.minusMinutes(30 - i), "adapted-quiz"));
        // 2 of 8 runs = 25% -> critical
        assertEquals("CRITICAL", status(ReliabilityMetrics.summarize(plan24h(), rows, NOW, false)));
    }

    @Test
    void previousWindowIsExposedForComparisonAndDisabledWhenTruncated() {
        Plan p = plan24h();
        List<Row> rows = new ArrayList<>(calls(20, 0, 1_000));                       // current window
        for (int i = 0; i < 10; i++) rows.add(call(p.from().minusHours(5).plusMinutes(i), "lesson", 1_000, i < 5, i < 5 ? 200 : 529)); // previous: 50%
        Map<String, Object> prev = map(ReliabilityMetrics.summarize(p, rows, NOW, false).get("previous"));
        assertEquals(true, prev.get("available"));
        assertEquals(10, prev.get("calls"));
        assertEquals(0.5, (Double) prev.get("successRate"), 1e-9);
        assertEquals(false, map(ReliabilityMetrics.summarize(p, rows, NOW, true).get("previous")).get("available"));
        // the previous window must not leak into current figures
        assertEquals(20, map(ReliabilityMetrics.summarize(p, rows, NOW, false).get("api")).get("calls"));
    }

    @Test
    void eventsLandInTheRightBucket() {
        Plan p = plan24h();
        Row r = call(p.from().plusMinutes(90), "lesson", 1_000, false, 529);
        List<Map<String, Object>> series = list(ReliabilityMetrics.summarize(p, List.of(r), NOW, false).get("series"));
        assertEquals(1, series.get(1).get("calls"));
        assertEquals(1, series.get(1).get("failures"));
        assertEquals(0, series.get(0).get("calls"));
        assertNull(series.get(1).get("p95"));       // the only call failed, so no latency sample
    }

    @Test
    void incidentsListFailuresNewestFirstAndSkipLimitBlocks() {
        List<Row> rows = List.of(
                call(NOW.minusMinutes(30), "lesson", 10, false, 429),
                pipelineFailure(NOW.minusMinutes(20), "adapted-quiz"),
                limitBlock(NOW.minusMinutes(10), "quiz-submit"),
                call(NOW.minusMinutes(5), "lesson", 10, true, 200));
        List<Map<String, Object>> inc = list(ReliabilityMetrics.summarize(plan24h(), rows, NOW, false).get("incidents"));
        assertEquals(2, inc.size());
        assertEquals("PIPELINE_FAILURE", inc.get(0).get("kind"));
        assertEquals("API_CALL", inc.get(1).get("kind"));
    }

    @Test
    void rangeParsingIsStrict() {
        assertEquals(Range.D30, Range.parse("30D"));
        assertNull(Range.parse("365d"));
        assertNull(Range.parse(null));
    }
}
