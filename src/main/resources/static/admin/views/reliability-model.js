/**
 * Reliability view: pure model (no DOM, no network). Import-safe in Node so it can be tested with fixtures.
 *
 * The server (ReliabilityMetrics.java) does all the counting and decides every health level. This file only turns
 * its answer into display values: it never recomputes a rate, never invents a threshold and never turns a missing
 * value into 0 (a value the server could not compute is shown as "—", not as a healthy-looking zero).
 */

export const SUMMARY_PATH = (range) => `/api/admin/reliability/summary?range=${encodeURIComponent(range)}`;
export const CSV_PATH = "/api/admin/export/reliability.csv";
export const DEFAULT_RANGE = "7d";
export const REFRESH_MS = 60_000;

export const RANGES = Object.freeze([
  { key: "24h", label: "24 hours", compare: "previous 24 hours" },
  { key: "7d", label: "7 days", compare: "previous 7 days" },
  { key: "30d", label: "30 days", compare: "previous 30 days" },
  { key: "all", label: "All time", compare: null }
]);
export const rangeMeta = (key) => RANGES.find((r) => r.key === key) || RANGES[1];

/** Overall status. Text and icon always accompany the colour. */
export const STATUS = Object.freeze({
  HEALTHY: { label: "Healthy", icon: "✓", tone: "ok" },
  DEGRADED: { label: "Degraded", icon: "!", tone: "warn" },
  CRITICAL: { label: "Critical", icon: "✕", tone: "crit" },
  INSUFFICIENT_DATA: { label: "Not enough data", icon: "?", tone: "unknown" },
  NO_DATA: { label: "No data", icon: "–", tone: "unknown" }
});
export const statusMeta = (key) => STATUS[key] || STATUS.NO_DATA;

/** Per-measure level (what the server calls OK / WARN / CRIT / UNKNOWN). */
export const LEVEL = Object.freeze({
  OK: { label: "OK", icon: "✓", tone: "ok", rank: 1 },
  WARN: { label: "Degraded", icon: "!", tone: "warn", rank: 2 },
  CRIT: { label: "Critical", icon: "✕", tone: "crit", rank: 3 },
  UNKNOWN: { label: "Too few events", icon: "?", tone: "unknown", rank: 0 }
});
export const levelMeta = (key) => LEVEL[key] || LEVEL.UNKNOWN;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const isNum = (v) => typeof v === "number" && Number.isFinite(v);
const pad2 = (n) => String(n).padStart(2, "0");

export function fmtInt(n) { return isNum(n) ? n.toLocaleString("en-US") : "—"; }

/** 0..1 -> "97.4%". Never rounds a non-perfect rate up to 100%, nor a non-zero rate down to 0%. */
export function fmtPercent(rate, digits = 1) {
  if (!isNum(rate)) return "—";
  const step = 10 ** -digits;
  let v = rate * 100;
  if (rate > 0 && v < step) return `<${step.toFixed(digits)}%`;
  let s = v.toFixed(digits);
  if (rate < 1 && Number(s) >= 100) s = (100 - step).toFixed(digits);
  return `${s}%`;
}

export function fmtMs(ms) {
  if (!isNum(ms)) return "—";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const total = Math.round(ms / 1000);
  return `${Math.floor(total / 60)} min ${total % 60} s`;
}

export function fmtTokens(n) {
  if (!isNum(n)) return "—";
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 10_000) return `${Math.round(n / 1000)}k`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`;
  return String(n);
}

export function parseTime(s) {
  if (!s) return null;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function fmtClock(date) {
  return date ? `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}` : "—";
}

export function fmtDateTime(date) {
  return date ? `${MONTHS[date.getMonth()]} ${date.getDate()}, ${pad2(date.getHours())}:${pad2(date.getMinutes())}` : "—";
}

export function timeAgo(date, now = new Date()) {
  if (!date) return "—";
  const sec = Math.round((now.getTime() - date.getTime()) / 1000);
  if (sec < 45) return "just now";
  const min = Math.round(sec / 60);
  if (min < 60) return `${min} min ago`;
  const hr = Math.round(min / 60);
  if (hr < 48) return `${hr} h ago`;
  return `${Math.round(hr / 24)} d ago`;
}

/** Short axis label for the start of a bucket. */
export function bucketLabel(startIso, stepMinutes) {
  const d = parseTime(startIso);
  if (!d) return "";
  if (stepMinutes < 360) return `${pad2(d.getHours())}:00`;
  if (stepMinutes < 1440) return `${MONTHS[d.getMonth()]} ${d.getDate()} ${pad2(d.getHours())}:00`;
  if (stepMinutes < 10080) return `${MONTHS[d.getMonth()]} ${d.getDate()}`;
  return `Wk ${MONTHS[d.getMonth()]} ${d.getDate()}`;
}

/** Full description of a bucket, for tooltips and the data table. */
export function bucketTitle(startIso, stepMinutes) {
  const d = parseTime(startIso);
  if (!d) return "";
  if (stepMinutes >= 10080) return `Week of ${MONTHS[d.getMonth()]} ${d.getDate()}`;
  if (stepMinutes >= 1440) return `${MONTHS[d.getMonth()]} ${d.getDate()}`;
  const end = new Date(d.getTime() + stepMinutes * 60_000);
  return `${MONTHS[d.getMonth()]} ${d.getDate()}, ${pad2(d.getHours())}:00–${pad2(end.getHours())}:00`;
}

/** A malformed body is an error, never "no data". */
export function validateSummary(d) {
  return Boolean(d && typeof d === "object" && d.success !== false
    && typeof d.range === "string"
    && d.health && typeof d.health.status === "string" && Array.isArray(d.health.reasons) && d.health.levels && d.health.thresholds
    && d.api && d.limits && d.yield && d.pipeline && d.previous && d.live && d.window
    && Array.isArray(d.series) && Array.isArray(d.tasks) && Array.isArray(d.incidents));
}

export function healthHeadline(summary) {
  const { status, reasons, thresholds: t } = summary.health;
  const meta = statusMeta(status);
  let headline = null;
  if (status === "HEALTHY") headline = "Every monitored measure is within its target.";
  else if (status === "NO_DATA") headline = "No reliability events were recorded in this period.";
  else if (status === "INSUFFICIENT_DATA") {
    headline = `Too few events to judge reliably yet (needs at least ${t.minApiSample} AI calls, ${t.minYieldSample} question batches or ${t.minPipelineSample} generation runs).`;
  }
  // Most severe first (stable, so the server's own order breaks ties).
  const ordered = reasons.map((r, i) => ({ r, i })).sort((a, b) => levelMeta(b.r.level).rank - levelMeta(a.r.level).rank || a.i - b.i).map((x) => x.r);
  if (!headline) headline = ordered.length ? ordered[0].message : `Status is ${meta.label.toLowerCase()}.`;
  const more = status === "DEGRADED" || status === "CRITICAL" ? ordered.slice(1) : [];
  return { meta, headline, more };
}

export function liveLines(summary, now = new Date()) {
  const live = summary.live;
  const lines = [];
  const since = parseTime(live.streakSince);
  if (live.streakType === "failing") lines.push({ tone: "crit", text: `The last ${live.streakCount} AI call${live.streakCount === 1 ? "" : "s"} in a row failed (since ${fmtClock(since)}).` });
  else if (live.streakType === "ok") lines.push({ tone: "ok", text: `${fmtInt(live.streakCount)} successful AI call${live.streakCount === 1 ? "" : "s"} in a row.` });
  const ok = parseTime(live.lastSuccessAt);
  const fail = parseTime(live.lastFailureAt);
  lines.push({ tone: "neutral", text: ok ? `Last successful AI call: ${timeAgo(ok, now)} (${fmtDateTime(ok)}).` : "No successful AI call in this period." });
  lines.push({ tone: "neutral", text: fail ? `Last failed AI call: ${timeAgo(fail, now)} (${fmtDateTime(fail)}).` : "No failed AI call in this period." });
  return lines;
}

/**
 * Change versus the previous window. Returns null when there is nothing trustworthy to compare.
 * kind: "rate" (0..1, shown in points), "ms" (relative %), "count" (absolute).
 */
export function deltaInfo(current, previous, { kind, higherIsBetter, neutral = false }) {
  if (!isNum(current) || !isNum(previous)) return null;
  let diff, flat, text;
  if (kind === "rate") {
    diff = (current - previous) * 100;
    flat = Math.abs(diff) < 0.1;
    text = `${Math.abs(diff).toFixed(1)} pts`;
  } else if (kind === "ms") {
    if (previous === 0) return null;
    diff = (current - previous) / previous;
    flat = Math.abs(diff) < 0.05;
    text = `${Math.abs(Math.round(diff * 100))}%`;
  } else {
    if (current === 0 && previous === 0) return null;   // 0 -> 0 is noise, not information
    diff = current - previous;
    flat = diff === 0;
    text = fmtInt(Math.abs(diff));
  }
  if (flat) return { direction: "flat", arrow: "→", text: "No change", tone: "neutral", sr: "no change" };
  const up = diff > 0;
  const better = up === higherIsBetter;
  return {
    direction: up ? "up" : "down",
    arrow: up ? "▲" : "▼",
    text,
    tone: neutral ? "neutral" : better ? "better" : "worse",
    sr: `${up ? "up" : "down"} ${text}${neutral ? "" : better ? ", better" : ", worse"}`
  };
}

export function kpiCards(summary) {
  const { api, yield: y, pipeline, limits, previous: prev, health } = summary;
  const t = health.thresholds;
  const lv = health.levels;
  const canCompare = prev.available === true;
  const enough = (n, min) => isNum(n) && n >= min;

  const cards = [];
  cards.push({
    id: "success", label: "AI call success", value: fmtPercent(api.successRate), level: lv.success,
    sub: `${fmtInt(api.calls)} call${api.calls === 1 ? "" : "s"} · ${fmtInt(api.failures)} failed`,
    hint: `Healthy at ${fmtPercent(t.successWarn, 0)} or above; critical below ${fmtPercent(t.successCrit, 0)}. Each retry counts as its own call.`,
    delta: canCompare && enough(api.calls, t.minApiSample) && enough(prev.calls, t.minApiSample)
      ? deltaInfo(api.successRate, prev.successRate, { kind: "rate", higherIsBetter: true }) : null
  });
  cards.push({
    id: "latency", label: "AI latency (p95)", value: fmtMs(api.latencyMs.p95), level: lv.latency,
    sub: `Median ${fmtMs(api.latencyMs.p50)} · slowest ${fmtMs(api.latencyMs.max)}`,
    hint: `95% of successful calls finish at or under this time. Degraded above ${fmtMs(t.p95WarnMs)}; critical above ${fmtMs(t.p95CritMs)}.`,
    delta: canCompare && enough(api.successes, t.minApiSample) && isNum(prev.p95)
      ? deltaInfo(api.latencyMs.p95, prev.p95, { kind: "ms", higherIsBetter: false }) : null
  });
  const emptyRuns = y.emptyBatches > 0 ? ` · ${fmtInt(y.emptyBatches)} run${y.emptyBatches === 1 ? "" : "s"} kept nothing` : "";
  cards.push({
    id: "yield", label: "Question yield", value: fmtPercent(y.rate), level: lv.yield,
    sub: `${fmtInt(y.kept)} kept · ${fmtInt(y.dropped)} dropped${emptyRuns}`,
    hint: `Share of generated questions that passed validation. Healthy at ${fmtPercent(t.yieldWarn, 0)} or above; critical below ${fmtPercent(t.yieldCrit, 0)}.`,
    delta: canCompare && enough(y.batches, t.minYieldSample) && enough(prev.batches, t.minYieldSample)
      ? deltaInfo(y.rate, prev.yieldRate, { kind: "rate", higherIsBetter: true }) : null
  });
  cards.push({
    id: "pipeline", label: "Generation failures", value: fmtInt(pipeline.failures), level: lv.pipeline,
    sub: pipeline.runs > 0 ? `${fmtPercent(pipeline.rate)} of ${fmtInt(pipeline.runs)} generation run${pipeline.runs === 1 ? "" : "s"}` : "No generation runs",
    hint: `Quiz-generation runs that failed outright. Degraded above ${fmtPercent(t.pipelineWarn, 0)} of runs; critical above ${fmtPercent(t.pipelineCrit, 0)}.`,
    delta: canCompare ? deltaInfo(pipeline.failures, prev.pipelineFailures, { kind: "count", higherIsBetter: false }) : null
  });
  cards.push({
    id: "limits", label: "Daily-limit blocks", value: fmtInt(limits.blocks), level: null,
    sub: "Actions stopped by a daily limit",
    hint: "A policy outcome, not a fault. Shown for context; it never changes the overall status.",
    delta: canCompare ? deltaInfo(limits.blocks, prev.limitBlocks, { kind: "count", higherIsBetter: false, neutral: true }) : null
  });
  cards.push({
    id: "truncated", label: "Cut-off replies", value: fmtInt(api.truncatedReplies), level: null,
    sub: `of ${fmtInt(api.successes)} successful repl${api.successes === 1 ? "y" : "ies"}`,
    hint: "Replies that hit the output token limit. Shown for context; it never changes the overall status.",
    delta: null
  });
  return cards.map((c) => ({ ...c, meta: c.level ? levelMeta(c.level) : null }));
}

export const TASK_COLUMNS = Object.freeze([
  { key: "task", label: "Task", numeric: false },
  { key: "level", label: "Status", numeric: false },
  { key: "calls", label: "AI calls", numeric: true },
  { key: "successRate", label: "Success", numeric: true },
  { key: "p95", label: "p95 latency", numeric: true },
  { key: "yieldRate", label: "Yield", numeric: true },
  { key: "pipelineFailures", label: "Gen. failures", numeric: true }
]);

/** Sorts without mutating. Missing values always sort last, whichever direction is chosen. */
export function sortTasks(tasks, key, dir = "desc") {
  const sign = dir === "asc" ? 1 : -1;
  const value = (t) => (key === "level" ? levelMeta(t.level).rank : t[key]);
  return tasks.map((t, i) => ({ t, i })).sort((a, b) => {
    const va = value(a.t), vb = value(b.t);
    const na = va == null, nb = vb == null;
    if (na || nb) return na === nb ? a.i - b.i : na ? 1 : -1;
    const c = typeof va === "string" ? va.localeCompare(vb) : va - vb;
    return c !== 0 ? c * sign : a.i - b.i;
  }).map((x) => x.t);
}

export const INCIDENT_FILTERS = Object.freeze([
  { key: "all", label: "All incidents" },
  { key: "api", label: "AI call failures" },
  { key: "pipeline", label: "Generation failures" }
]);

export function filterIncidents(incidents, filter) {
  if (filter === "api") return incidents.filter((i) => i.kind === "API_CALL");
  if (filter === "pipeline") return incidents.filter((i) => i.kind === "PIPELINE_FAILURE");
  return incidents.slice();
}

export const incidentKindLabel = (kind) => (kind === "API_CALL" ? "AI call" : kind === "PIPELINE_FAILURE" ? "Generation" : kind || "Event");

const ACTION_LABELS = Object.freeze({
  "quiz-submit": "Quiz submissions", "adapted-quiz": "Adapted quizzes", "targeted-quiz": "Targeted quizzes",
  "material-upload": "Material uploads", "lesson-regeneration": "Lesson regenerations", "question-report": "Question reports"
});
export const actionLabel = (action) => ACTION_LABELS[action] || action || "(unnamed)";

/**
 * "Now" according to the server: its generatedAt plus the time elapsed since the response arrived.
 * Event times are server-local timestamps, so relative times ("3 min ago") must be measured against the
 * server's clock, not the browser's, or a clock or time-zone difference would skew every one of them.
 */
export function serverNow(generatedAt, fetchedAtMs, nowMs = Date.now()) {
  const base = parseTime(generatedAt);
  return base ? new Date(base.getTime() + Math.max(0, nowMs - fetchedAtMs)) : new Date(nowMs);
}

/** Failure causes / limit actions as proportional rows. The bar is decoration; the count is always printed. */
export function barRows(rows, labelKey) {
  const max = Math.max(1, ...rows.map((r) => r.count));
  const total = rows.reduce((s, r) => s + r.count, 0) || 1;
  return rows.map((r) => ({ label: r[labelKey], count: r.count, widthPct: Math.max(3, Math.round((r.count / max) * 100)), sharePct: Math.round((r.count / total) * 100) }));
}

export function chartData(summary) {
  const step = summary.window.stepMinutes;
  const s = summary.series;
  const sec = (ms) => (isNum(ms) ? Math.round(ms) / 1000 : null);
  const data = {
    labels: s.map((b) => bucketLabel(b.start, step)),
    titles: s.map((b) => bucketTitle(b.start, step)),
    callsOk: s.map((b) => b.calls - b.failures),
    callsFailed: s.map((b) => b.failures),
    successRate: s.map((b) => (b.calls > 0 ? (b.calls - b.failures) / b.calls : null)),
    p50: s.map((b) => sec(b.p50)),
    p95: s.map((b) => sec(b.p95)),
    warnLine: s.map(() => summary.health.thresholds.p95WarnMs / 1000),
    kept: s.map((b) => b.kept),
    dropped: s.map((b) => b.dropped),
    limitBlocks: s.map((b) => b.limitBlocks),
    pipelineFailures: s.map((b) => b.pipelineFailures)
  };
  const any = (arr) => arr.some((v) => isNum(v) && v > 0);
  data.has = {
    calls: any(data.callsOk) || any(data.callsFailed),
    latency: data.p95.some(isNum),
    yield: any(data.kept) || any(data.dropped),
    blocks: any(data.limitBlocks) || any(data.pipelineFailures)
  };
  return data;
}

/** One-sentence text alternative for each chart (read by screen readers in place of the canvas). */
export function chartSummaries(summary) {
  const { api, yield: y, limits, pipeline } = summary;
  const t = summary.health.thresholds;
  return {
    calls: `AI calls per period: ${fmtInt(api.calls)} calls in total, ${fmtInt(api.failures)} failed.`,
    latency: `AI latency per period: median ${fmtMs(api.latencyMs.p50)}, 95th percentile ${fmtMs(api.latencyMs.p95)}, degraded above ${fmtMs(t.p95WarnMs)}.`,
    yield: `Questions per period: ${fmtInt(y.kept)} kept and ${fmtInt(y.dropped)} dropped.`,
    blocks: `Daily-limit blocks and generation failures per period: ${fmtInt(limits.blocks)} limit blocks and ${fmtInt(pipeline.failures)} generation failures.`
  };
}

export function thresholdLines(t) {
  const p = (v) => fmtPercent(v, 0);
  return [
    `AI call success: healthy at ${p(t.successWarn)} or above, degraded below that, critical below ${p(t.successCrit)} (judged once there are ${t.minApiSample} calls).`,
    `AI latency (p95, successful calls only): degraded above ${fmtMs(t.p95WarnMs)}, critical above ${fmtMs(t.p95CritMs)}.`,
    `Question yield: healthy at ${p(t.yieldWarn)} or above, critical below ${p(t.yieldCrit)} (judged once there are ${t.minYieldSample} question batches).`,
    `Generation failures: degraded above ${p(t.pipelineWarn)} of runs, critical above ${p(t.pipelineCrit)} (judged once there are ${t.minPipelineSample} runs).`,
    `Outage check: the last ${t.streakCrit} AI calls in a row failing makes the status critical, whatever the period's average says.`,
    "Daily-limit blocks and cut-off replies are shown for context only and never change the status.",
    "Overall status is the worst of the measures that have enough events. A measure with too few events is marked and left out."
  ];
}
