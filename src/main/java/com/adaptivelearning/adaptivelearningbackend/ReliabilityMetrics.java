package com.adaptivelearning.adaptivelearningbackend;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Aggregates {@link ReliabilityEvent} rows into the figures shown on Admin &gt; System &gt; Reliability.
 *
 * Pure Java on purpose (no Spring, no JPA): the controller maps entities to {@link Row} and this class does the
 * arithmetic, so every number on the page can be unit-tested without a database.
 *
 * Definitions (also shown to the admin on the page, from {@link #thresholds()}):
 *  - An "AI call" is one API_CALL event. Automatic retries (HTTP 429 / 5xx, up to two) are logged as separate calls.
 *  - Success rate = successful calls / all calls.
 *  - Latency percentiles use successful calls only (a call that fails instantly or times out would distort them).
 *    Percentiles use the nearest-rank method.
 *  - Question yield = questions kept / (kept + dropped) across QUESTION_YIELD events.
 *  - Pipeline failure rate = PIPELINE_FAILURE events / (QUESTION_YIELD events + PIPELINE_FAILURE events).
 *  - Limit blocks (LIMIT_BLOCK) are a policy outcome, not a fault: they are reported but never change health.
 *  - A metric with too few events is reported as UNKNOWN instead of being judged on a tiny sample.
 */
public final class ReliabilityMetrics {

    private ReliabilityMetrics() {}

    public static final int    MIN_API_SAMPLE      = 5;
    public static final double SUCCESS_WARN        = 0.98;
    public static final double SUCCESS_CRIT        = 0.90;
    public static final long   P95_WARN_MS         = 30_000;
    public static final long   P95_CRIT_MS         = 60_000;
    public static final int    MIN_YIELD_SAMPLE    = 3;
    public static final double YIELD_WARN          = 0.80;
    public static final double YIELD_CRIT          = 0.50;
    public static final int    MIN_PIPELINE_SAMPLE = 5;
    public static final double PIPELINE_WARN       = 0.05;
    public static final double PIPELINE_CRIT       = 0.15;
    public static final int    STREAK_CRIT         = 3;
    public static final int    MAX_INCIDENTS       = 30;

    public static final String OK = "OK", WARN = "WARN", CRIT = "CRIT", UNKNOWN = "UNKNOWN";


    /** Entity-free copy of one reliability_log row. */
    public record Row(LocalDateTime at, String kind, String task, Long latencyMs, Boolean success,
                      Integer httpStatus, Integer inTokens, Integer outTokens,
                      Integer kept, Integer dropped, String detail) {}

    public enum Range {
        H24("24h", 60, 24), D7("7d", 360, 28), D30("30d", 1440, 30), ALL("all", 0, 0);

        public final String key; final int stepMinutes; final int buckets;
        Range(String key, int stepMinutes, int buckets) { this.key = key; this.stepMinutes = stepMinutes; this.buckets = buckets; }

        public static Range parse(String s) {
            if (s == null) return null;
            for (Range r : values()) if (r.key.equalsIgnoreCase(s.trim())) return r;
            return null;
        }
    }

    /** The reporting window [from, to) split into buckets of stepMinutes; previousFrom is null for ALL. */
    public record Plan(Range range, LocalDateTime from, LocalDateTime to, int stepMinutes, LocalDateTime previousFrom) {
        public LocalDateTime loadFrom() { return previousFrom != null ? previousFrom : from; }
        int bucketCount() { return (int) (ChronoUnit.MINUTES.between(from, to) / stepMinutes); }
    }

    public static Plan plan(Range range, LocalDateTime now, LocalDateTime firstEvent) {
        if (range != Range.ALL) {
            int step = range.stepMinutes;
            LocalDateTime to = floor(now, step).plusMinutes(step);
            long span = (long) step * range.buckets;
            LocalDateTime from = to.minusMinutes(span);
            return new Plan(range, from, to, step, from.minusMinutes(span));
        }
        LocalDateTime start = firstEvent == null ? now : firstEvent;
        long spanMin = Math.max(0, ChronoUnit.MINUTES.between(start, now));
        int step = spanMin <= 48L * 60 ? 60 : spanMin <= 120L * 24 * 60 ? 1440 : 10080;
        return new Plan(range, floor(start, step), floor(now, step).plusMinutes(step), step, null);
    }

    static LocalDateTime floor(LocalDateTime t, int stepMinutes) {
        return switch (stepMinutes) {
            case 60    -> t.truncatedTo(ChronoUnit.HOURS);
            case 360   -> t.truncatedTo(ChronoUnit.HOURS).withHour(t.getHour() - t.getHour() % 6);
            case 1440  -> t.toLocalDate().atStartOfDay();
            case 10080 -> t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            default    -> throw new IllegalArgumentException("Unsupported step: " + stepMinutes);
        };
    }


    private static final class TaskAgg {
        int calls, failures; final List<Long> okLatencies = new ArrayList<>(); long tokens;
        int batches, kept, dropped, pipelineFailures;
    }

    private static final class Stats {
        int events, calls, ok, failures, truncatedReplies;
        long tokensIn, tokensOut;
        final List<Long> okLatencies = new ArrayList<>();
        final Map<String, Integer> causes = new LinkedHashMap<>();
        final Map<String, Integer> causeCodes = new HashMap<>();
        int batches, kept, dropped, emptyBatches, pipelineFailures, limitBlocks;
        final Map<String, Integer> limitByAction = new HashMap<>();
        final Map<String, TaskAgg> tasks = new HashMap<>();

        void add(Row r) {
            events++;
            String kind = r.kind() == null ? "" : r.kind();
            String task = r.task() == null || r.task().isBlank() ? "(unnamed)" : r.task();
            switch (kind) {
                case "API_CALL" -> {
                    calls++;
                    TaskAgg t = tasks.computeIfAbsent(task, k -> new TaskAgg());
                    t.calls++;
                    long tok = nz(r.inTokens()) + nz(r.outTokens());
                    tokensIn += nz(r.inTokens()); tokensOut += nz(r.outTokens()); t.tokens += tok;
                    if (Boolean.TRUE.equals(r.success())) {
                        ok++;
                        if (r.latencyMs() != null) { okLatencies.add(r.latencyMs()); t.okLatencies.add(r.latencyMs()); }
                        if (r.detail() != null && r.detail().startsWith("truncated")) truncatedReplies++;
                    } else {
                        failures++; t.failures++;
                        String label = causeLabel(r.httpStatus());
                        causes.merge(label, 1, Integer::sum);
                        causeCodes.putIfAbsent(label, r.httpStatus() == null ? 0 : r.httpStatus());
                    }
                }
                case "QUESTION_YIELD" -> {
                    batches++;
                    int k = (int) nz(r.kept()), d = (int) nz(r.dropped());
                    kept += k; dropped += d; if (k == 0) emptyBatches++;
                    TaskAgg t = tasks.computeIfAbsent(task, x -> new TaskAgg());
                    t.batches++; t.kept += k; t.dropped += d;
                }
                case "PIPELINE_FAILURE" -> {
                    pipelineFailures++;
                    tasks.computeIfAbsent(task, x -> new TaskAgg()).pipelineFailures++;
                }
                case "LIMIT_BLOCK" -> { limitBlocks++; limitByAction.merge(task, 1, Integer::sum); }
                default -> { /* unknown kinds are counted in events only */ }
            }
        }

        Double successRate()  { return calls == 0 ? null : (double) ok / calls; }
        Double yieldRate()    { return kept + dropped == 0 ? null : (double) kept / (kept + dropped); }
        Double pipelineRate() { int n = batches + pipelineFailures; return n == 0 ? null : (double) pipelineFailures / n; }
    }

    private static long nz(Integer v) { return v == null ? 0 : v; }

    public static String causeLabel(Integer status) {
        int s = status == null ? 0 : status;
        if (s == 0)   return "No response (network or timeout)";
        if (s == 429) return "Rate limited (HTTP 429)";
        if (s == 529) return "Provider overloaded (HTTP 529)";
        if (s == 503) return "Provider unavailable (HTTP 503)";
        if (s == 401 || s == 403) return "Authentication error (HTTP " + s + ")";
        if (s >= 500) return "Provider server error (HTTP " + s + ")";
        if (s >= 400) return "Request rejected (HTTP " + s + ")";
        if (s >= 200 && s < 300) return "Unusable response (HTTP " + s + ")";
        return "Other (HTTP " + s + ")";
    }

    /** Nearest-rank percentile of a list of values; null when empty. */
    static Long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static Double average(List<Long> values) {
        if (values.isEmpty()) return null;
        long sum = 0; for (long v : values) sum += v;
        return (double) sum / values.size();
    }

    private static Double round(Double v, int places) {
        if (v == null) return null;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    private static String pct(double v) { return String.format(Locale.ROOT, "%.1f%%", v * 100); }
    private static String secs(long ms) { return String.format(Locale.ROOT, "%.1f s", ms / 1000.0); }
    private static String iso(LocalDateTime t) { return t == null ? null : t.toString(); }


    static String successLevel(int calls, Double rate) {
        if (calls < MIN_API_SAMPLE || rate == null) return UNKNOWN;
        return rate < SUCCESS_CRIT ? CRIT : rate < SUCCESS_WARN ? WARN : OK;
    }
    static String latencyLevel(int okCalls, Long p95) {
        if (okCalls < MIN_API_SAMPLE || p95 == null) return UNKNOWN;
        return p95 > P95_CRIT_MS ? CRIT : p95 > P95_WARN_MS ? WARN : OK;
    }
    static String yieldLevel(int batches, Double rate) {
        if (batches < MIN_YIELD_SAMPLE || rate == null) return UNKNOWN;
        return rate < YIELD_CRIT ? CRIT : rate < YIELD_WARN ? WARN : OK;
    }
    static String pipelineLevel(int runs, Double rate) {
        if (runs < MIN_PIPELINE_SAMPLE || rate == null) return UNKNOWN;
        return rate > PIPELINE_CRIT ? CRIT : rate > PIPELINE_WARN ? WARN : OK;
    }
    private static int rank(String level) {
        return switch (level) { case CRIT -> 3; case WARN -> 2; case OK -> 1; default -> 0; };
    }
    private static String worst(String a, String b) { return rank(a) >= rank(b) ? a : b; }

    public static Map<String, Object> thresholds() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("minApiSample", MIN_API_SAMPLE);
        t.put("successWarn", SUCCESS_WARN);   t.put("successCrit", SUCCESS_CRIT);
        t.put("p95WarnMs", P95_WARN_MS);      t.put("p95CritMs", P95_CRIT_MS);
        t.put("minYieldSample", MIN_YIELD_SAMPLE);
        t.put("yieldWarn", YIELD_WARN);       t.put("yieldCrit", YIELD_CRIT);
        t.put("minPipelineSample", MIN_PIPELINE_SAMPLE);
        t.put("pipelineWarn", PIPELINE_WARN); t.put("pipelineCrit", PIPELINE_CRIT);
        t.put("streakCrit", STREAK_CRIT);
        return t;
    }


    /**
     * @param rows       events loaded from plan.loadFrom() onwards (any order)
     * @param truncated  true when the loader hit its row cap, so the oldest events of the window are missing
     */
    public static Map<String, Object> summarize(Plan plan, List<Row> rows, LocalDateTime now, boolean truncated) {
        List<Row> current = new ArrayList<>(), previous = new ArrayList<>();
        splitByWindow(plan, rows, current, previous);
        current.sort(Comparator.comparing(Row::at));

        Stats cur = new Stats();
        for (Row r : current) cur.add(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("range", plan.range().key);
        out.put("generatedAt", iso(now));
        out.put("truncated", truncated);
        out.put("events", cur.events);
        out.put("window", windowSection(plan));

        Live live = liveStatus(current);
        out.put("live", liveSection(live));
        out.put("health", healthSection(cur, live));
        out.put("api", apiSection(cur));
        out.put("limits", limitsSection(cur));
        out.put("yield", yieldSection(cur));
        out.put("pipeline", pipelineSection(cur));
        out.put("previous", previousSection(plan, previous, truncated));
        out.put("tasks", tasksSection(cur));
        out.put("series", seriesSection(plan, current));
        out.put("incidents", incidentsSection(current));
        return out;
    }

    /** Sorts each usable row into the current window [from, to) or the comparison window before it. */
    private static void splitByWindow(Plan plan, List<Row> rows, List<Row> current, List<Row> previous) {
        for (Row r : rows) {
            if (r == null || r.at() == null) continue;
            if (!r.at().isBefore(plan.from()) && r.at().isBefore(plan.to())) current.add(r);
            else if (inPreviousWindow(plan, r.at())) previous.add(r);
        }
    }

    private static boolean inPreviousWindow(Plan plan, LocalDateTime at) {
        return plan.previousFrom() != null && !at.isBefore(plan.previousFrom()) && at.isBefore(plan.from());
    }

    private static Map<String, Object> windowSection(Plan plan) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", iso(plan.from())); window.put("to", iso(plan.to()));
        window.put("stepMinutes", plan.stepMinutes()); window.put("previousFrom", iso(plan.previousFrom()));
        return window;
    }

    // ---- live status (streak over API calls, newest first) ----

    private record Streak(String type, int count, LocalDateTime since) {}

    private record Live(LocalDateTime lastEvent, LocalDateTime lastOk, LocalDateTime lastFail, Streak streak) {
        /** A live outage: the newest run of API calls is all failures and long enough to be critical. */
        boolean outage() { return "failing".equals(streak.type()) && streak.count() >= STREAK_CRIT; }
    }

    private static Live liveStatus(List<Row> current) {
        LocalDateTime lastEvent = current.isEmpty() ? null : current.get(current.size() - 1).at();
        return new Live(lastEvent, lastApiCallAt(current, true), lastApiCallAt(current, false), streakOf(current));
    }

    /** Time of the newest API call with the given outcome, or null. */
    private static LocalDateTime lastApiCallAt(List<Row> current, boolean success) {
        for (int i = current.size() - 1; i >= 0; i--) {
            Row r = current.get(i);
            if ("API_CALL".equals(r.kind()) && Boolean.TRUE.equals(r.success()) == success) return r.at();
        }
        return null;
    }

    /** The newest unbroken run of API calls with the same outcome ("ok" or "failing"). */
    private static Streak streakOf(List<Row> current) {
        String type = null; int count = 0; LocalDateTime since = null;
        for (int i = current.size() - 1; i >= 0; i--) {
            Row r = current.get(i);
            if (!"API_CALL".equals(r.kind())) continue;
            boolean good = Boolean.TRUE.equals(r.success());
            if (type == null) type = good ? "ok" : "failing";
            if (type.equals("ok") != good) break;
            count++; since = r.at();
        }
        return new Streak(type, count, since);
    }

    private static Map<String, Object> liveSection(Live live) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastEventAt", iso(live.lastEvent())); m.put("lastSuccessAt", iso(live.lastOk())); m.put("lastFailureAt", iso(live.lastFail()));
        m.put("streakType", live.streak().type()); m.put("streakCount", live.streak().count()); m.put("streakSince", iso(live.streak().since()));
        return m;
    }

    // ---- health ----

    private static Map<String, Object> healthSection(Stats cur, Live live) {
        Long p95 = percentile(cur.okLatencies, 0.95);
        String sLevel = successLevel(cur.calls, cur.successRate());
        String lLevel = latencyLevel(cur.okLatencies.size(), p95);
        String yLevel = yieldLevel(cur.batches, cur.yieldRate());
        String pLevel = pipelineLevel(cur.batches + cur.pipelineFailures, cur.pipelineRate());

        String overall = UNKNOWN;
        for (String lv : List.of(sLevel, lLevel, yLevel, pLevel)) overall = worst(overall, lv);
        if (live.outage()) overall = CRIT;

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", statusLabel(cur.events, overall));
        health.put("reasons", healthReasons(cur, live, sLevel, lLevel, yLevel, pLevel, p95));
        health.put("levels", Map.of("success", sLevel, "latency", lLevel, "yield", yLevel, "pipeline", pLevel));
        health.put("thresholds", thresholds());
        return health;
    }

    private static String statusLabel(int events, String overall) {
        if (events == 0) return "NO_DATA";
        return switch (overall) {
            case UNKNOWN -> "INSUFFICIENT_DATA";
            case CRIT    -> "CRITICAL";
            case WARN    -> "DEGRADED";
            default      -> "HEALTHY";
        };
    }

    private static boolean unhealthy(String level) { return level.equals(WARN) || level.equals(CRIT); }

    private static List<Map<String, Object>> healthReasons(Stats cur, Live live, String sLevel, String lLevel,
                                                           String yLevel, String pLevel, Long p95) {
        List<Map<String, Object>> reasons = new ArrayList<>();
        // First on purpose: a live outage is the most urgent thing an admin can be told.
        if (live.outage()) reasons.add(reason("streak", CRIT,
                "The last " + live.streak().count() + " AI calls in a row failed" + (live.lastOk() == null ? " (no successful call in this period)." : ".")));
        if (unhealthy(sLevel)) reasons.add(reason("success", sLevel,
                "AI call success rate is " + pct(cur.successRate()) + " (healthy at " + pct(SUCCESS_WARN) + " or above, critical below " + pct(SUCCESS_CRIT) + ")."));
        if (unhealthy(lLevel)) reasons.add(reason("latency", lLevel,
                "95th-percentile AI latency is " + secs(p95) + " (degraded above " + secs(P95_WARN_MS) + ", critical above " + secs(P95_CRIT_MS) + ")."));
        if (unhealthy(yLevel)) reasons.add(reason("yield", yLevel,
                "Only " + pct(cur.yieldRate()) + " of generated questions passed validation (healthy at " + pct(YIELD_WARN) + " or above, critical below " + pct(YIELD_CRIT) + ")."));
        if (unhealthy(pLevel)) reasons.add(reason("pipeline", pLevel,
                pct(cur.pipelineRate()) + " of quiz-generation runs failed outright (degraded above " + pct(PIPELINE_WARN) + ", critical above " + pct(PIPELINE_CRIT) + ")."));
        return reasons;
    }

    // ---- headline sections ----

    private static Map<String, Object> apiSection(Stats cur) {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("calls", cur.calls); api.put("successes", cur.ok); api.put("failures", cur.failures);
        api.put("successRate", round(cur.successRate(), 4));
        api.put("latencyMs", latencySection(cur));
        api.put("tokens", tokensSection(cur));
        api.put("truncatedReplies", cur.truncatedReplies);
        api.put("failureCauses", failureCauses(cur));
        return api;
    }

    private static Map<String, Object> latencySection(Stats cur) {
        Map<String, Object> lat = new LinkedHashMap<>();
        lat.put("p50", percentile(cur.okLatencies, 0.50)); lat.put("p95", percentile(cur.okLatencies, 0.95)); lat.put("p99", percentile(cur.okLatencies, 0.99));
        lat.put("avg", cur.okLatencies.isEmpty() ? null : (Object) Math.round(average(cur.okLatencies)));
        lat.put("max", cur.okLatencies.isEmpty() ? null : Collections.max(cur.okLatencies));
        return lat;
    }

    private static Map<String, Object> tokensSection(Stats cur) {
        Map<String, Object> tok = new LinkedHashMap<>();
        tok.put("input", cur.tokensIn); tok.put("output", cur.tokensOut);
        tok.put("avgPerCall", cur.calls == 0 ? null : (Object) Math.round((double) (cur.tokensIn + cur.tokensOut) / cur.calls));
        return tok;
    }

    private static List<Map<String, Object>> failureCauses(Stats cur) {
        List<Map<String, Object>> causes = new ArrayList<>();
        cur.causes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("label", e.getKey()); c.put("code", cur.causeCodes.get(e.getKey())); c.put("count", e.getValue());
            causes.add(c);
        });
        return causes;
    }

    private static Map<String, Object> limitsSection(Stats cur) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("blocks", cur.limitBlocks);
        List<Map<String, Object>> byAction = new ArrayList<>();
        cur.limitByAction.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> byAction.add(Map.of("action", e.getKey(), "count", e.getValue())));
        limits.put("byAction", byAction);
        return limits;
    }

    private static Map<String, Object> yieldSection(Stats cur) {
        Map<String, Object> yieldOut = new LinkedHashMap<>();
        yieldOut.put("batches", cur.batches); yieldOut.put("kept", cur.kept); yieldOut.put("dropped", cur.dropped);
        yieldOut.put("rate", round(cur.yieldRate(), 4)); yieldOut.put("emptyBatches", cur.emptyBatches);
        return yieldOut;
    }

    private static Map<String, Object> pipelineSection(Stats cur) {
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("failures", cur.pipelineFailures); pipeline.put("runs", cur.batches + cur.pipelineFailures);
        pipeline.put("rate", round(cur.pipelineRate(), 4));
        return pipeline;
    }

    /** Previous window (comparison). Not offered when the loader truncated or for ALL. */
    private static Map<String, Object> previousSection(Plan plan, List<Row> previous, boolean truncated) {
        Map<String, Object> prev = new LinkedHashMap<>();
        if (plan.previousFrom() == null || truncated) {
            prev.put("available", false);
            return prev;
        }
        Stats p = new Stats();
        for (Row r : previous) p.add(r);
        prev.put("available", true);
        prev.put("calls", p.calls); prev.put("failures", p.failures);
        prev.put("successRate", round(p.successRate(), 4));
        prev.put("p95", percentile(p.okLatencies, 0.95));
        prev.put("yieldRate", round(p.yieldRate(), 4));
        prev.put("pipelineFailures", p.pipelineFailures); prev.put("limitBlocks", p.limitBlocks);
        prev.put("batches", p.batches); prev.put("from", iso(plan.previousFrom())); prev.put("to", iso(plan.from()));
        return prev;
    }

    // ---- per-task table ----

    private static List<Map<String, Object>> tasksSection(Stats cur) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Map.Entry<String, TaskAgg> e : cur.tasks.entrySet()) tasks.add(taskRow(e.getKey(), e.getValue()));
        tasks.sort(ReliabilityMetrics::compareTaskRows);
        return tasks;
    }

    private static Map<String, Object> taskRow(String task, TaskAgg t) {
        Double sr = t.calls == 0 ? null : (double) (t.calls - t.failures) / t.calls;
        Double yr = t.kept + t.dropped == 0 ? null : (double) t.kept / (t.kept + t.dropped);
        Long tp95 = percentile(t.okLatencies, 0.95);
        int runs = t.batches + t.pipelineFailures;
        Double pr = runs == 0 ? null : (double) t.pipelineFailures / runs;
        String level = worst(worst(successLevel(t.calls, sr), latencyLevel(t.okLatencies.size(), tp95)),
                             worst(yieldLevel(t.batches, yr), pipelineLevel(runs, pr)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", task); m.put("level", level);
        m.put("calls", t.calls); m.put("failures", t.failures); m.put("successRate", round(sr, 4)); m.put("p95", tp95);
        m.put("avgTokens", t.calls == 0 ? null : (Object) Math.round((double) t.tokens / t.calls));
        m.put("batches", t.batches); m.put("kept", t.kept); m.put("dropped", t.dropped); m.put("yieldRate", round(yr, 4));
        m.put("pipelineFailures", t.pipelineFailures);
        return m;
    }

    /** Worst level first, then most failures, then most calls. */
    private static int compareTaskRows(Map<String, Object> a, Map<String, Object> b) {
        int c = Integer.compare(rank((String) b.get("level")), rank((String) a.get("level")));
        if (c != 0) return c;
        c = Integer.compare((int) b.get("failures") + (int) b.get("pipelineFailures"), (int) a.get("failures") + (int) a.get("pipelineFailures"));
        return c != 0 ? c : Integer.compare((int) b.get("calls"), (int) a.get("calls"));
    }

    // ---- time series ----

    /** Per-bucket tallies for the time-series chart. */
    private static final class Buckets {
        final List<List<Long>> latencies = new ArrayList<>();
        final int[] calls, fails, limit, pipe, kept, dropped;

        Buckets(int n) {
            calls = new int[n]; fails = new int[n]; limit = new int[n]; pipe = new int[n]; kept = new int[n]; dropped = new int[n];
            for (int i = 0; i < n; i++) latencies.add(new ArrayList<>());
        }

        void add(int i, Row r) {
            switch (r.kind() == null ? "" : r.kind()) {
                case "API_CALL" -> addApiCall(i, r);
                case "LIMIT_BLOCK" -> limit[i]++;
                case "PIPELINE_FAILURE" -> pipe[i]++;
                case "QUESTION_YIELD" -> { kept[i] += (int) nz(r.kept()); dropped[i] += (int) nz(r.dropped()); }
                default -> { }
            }
        }

        private void addApiCall(int i, Row r) {
            calls[i]++;
            if (!Boolean.TRUE.equals(r.success())) fails[i]++;
            else if (r.latencyMs() != null) latencies.get(i).add(r.latencyMs());
        }

        Map<String, Object> point(Plan plan, int i) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("start", iso(plan.from().plusMinutes((long) i * plan.stepMinutes())));
            b.put("calls", calls[i]); b.put("failures", fails[i]);
            b.put("p50", percentile(latencies.get(i), 0.50)); b.put("p95", percentile(latencies.get(i), 0.95));
            b.put("limitBlocks", limit[i]); b.put("pipelineFailures", pipe[i]);
            b.put("kept", kept[i]); b.put("dropped", dropped[i]);
            return b;
        }
    }

    private static List<Map<String, Object>> seriesSection(Plan plan, List<Row> current) {
        int n = plan.bucketCount();
        Buckets buckets = new Buckets(n);
        for (Row r : current) {
            int i = (int) (ChronoUnit.MINUTES.between(plan.from(), r.at()) / plan.stepMinutes());
            if (i >= 0 && i < n) buckets.add(i, r);
        }
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 0; i < n; i++) series.add(buckets.point(plan, i));
        return series;
    }

    // ---- incidents ----

    /** Failed AI calls and pipeline failures, newest first. */
    private static List<Map<String, Object>> incidentsSection(List<Row> current) {
        List<Map<String, Object>> incidents = new ArrayList<>();
        for (int i = current.size() - 1; i >= 0 && incidents.size() < MAX_INCIDENTS; i--) {
            Row r = current.get(i);
            boolean apiFail = "API_CALL".equals(r.kind()) && !Boolean.TRUE.equals(r.success());
            if (apiFail || "PIPELINE_FAILURE".equals(r.kind())) incidents.add(incidentRow(r, apiFail));
        }
        return incidents;
    }

    private static Map<String, Object> incidentRow(Row r, boolean apiFail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", iso(r.at())); m.put("kind", r.kind()); m.put("task", r.task());
        m.put("httpStatus", r.httpStatus());
        m.put("cause", apiFail ? causeLabel(r.httpStatus()) : "Quiz generation failed");
        m.put("detail", r.detail());
        return m;
    }

    private static Map<String, Object> reason(String metric, String level, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric); m.put("level", level); m.put("message", message);
        return m;
    }
}
