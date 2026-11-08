/**
 * System > Reliability  (route: /system/reliability)
 *
 * A monitoring dashboard for the AI pipeline, built on the append-only reliability log:
 *   GET /api/admin/reliability/summary?range=24h|7d|30d|all   (admin-only; no student identifiers)
 * The CSV export (GET /api/admin/export/reliability.csv) stays available from the toolbar.
 *
 * Reading order is the order an admin needs it in: overall status and what is wrong right now, then the key
 * measures against the previous period, then trends, then where the problems are (task, cause) and the latest
 * incidents. Every number comes from the server (ReliabilityMetrics.java); this view only displays it.
 *
 * Behaviour that matters for monitoring:
 *  - A failed load is an error, never zeros. A failed refresh keeps the last good data and says it is out of date.
 *  - Status is never colour-only: each tone has an icon and a word. Charts have a text alternative and a data table.
 *  - Auto-refresh (60 s, on by default, paused while the tab is hidden); the range and auto-refresh choice are remembered.
 *  - The status panel is a polite live region and is only rewritten when its wording changes, so a refresh
 *    that finds nothing new announces nothing.
 *
 * Contract: default export { mount(el, ctx), unmount() }. Fetches through ctx.api.get; all text is inserted as text nodes.
 */
import { h, createLifecycle } from "./view-dom.js";
import {
  SUMMARY_PATH, CSV_PATH, DEFAULT_RANGE, REFRESH_MS, RANGES, rangeMeta,
  fmtInt, fmtPercent, fmtMs, fmtClock, fmtDateTime, parseTime, serverNow,
  validateSummary, healthHeadline, liveLines, kpiCards, TASK_COLUMNS, sortTasks, levelMeta,
  INCIDENT_FILTERS, filterIncidents, incidentKindLabel, barRows, actionLabel,
  chartData, chartSummaries, thresholdLines, bucketTitle
} from "./reliability-model.js";

export const RELIABILITY_CSV = CSV_PATH;

const CSS_ID = "admin-reliability-css";
const PREFS_KEY = "admin.reliability.prefs";
const TICK_MS = 30_000;
const INCIDENTS_SHOWN = 10;
const DETAIL_CLAMP = 140;

function ensureStyles() {
  if (typeof document === "undefined" || document.getElementById(CSS_ID)) return;
  const link = document.createElement("link");
  link.id = CSS_ID;
  link.rel = "stylesheet";
  link.href = new URL("../reliability.css", import.meta.url).href;
  document.head.append(link);
}

// Native append()/replaceChildren() turn null/false into visible "null"/"false" text, so children go through this.
const kids = (list) => list.flat(Infinity).filter((k) => k != null && k !== false && k !== true);
function swap(parent, ...list) { parent.replaceChildren(...kids(list)); return parent; }

function readPrefs() {
  try {
    const p = JSON.parse(localStorage.getItem(PREFS_KEY) || "{}");
    return { range: RANGES.some((r) => r.key === p.range) ? p.range : DEFAULT_RANGE, auto: p.auto !== false };
  } catch { return { range: DEFAULT_RANGE, auto: true }; }
}
function savePrefs(prefs) { try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch { /* storage unavailable: the choice is just not remembered */ } }

const rangeText = (key) => (key === "all" ? "All time" : `Last ${rangeMeta(key).label}`);
const iconAndLabel = (meta) => [h("span", { "aria-hidden": "true" }, meta.icon), meta.label];
const th = (text, numeric) => h("th", { scope: "col", class: numeric ? "rel-num" : "" }, h("span", { class: "rel-th" }, text));

function palette() {
  const cs = typeof getComputedStyle === "function" && typeof document !== "undefined" ? getComputedStyle(document.documentElement) : null;
  const v = (name, fallback) => (cs && cs.getPropertyValue(name).trim()) || fallback;
  return {
    primary: v("--color-primary", "#0284c7"), danger: v("--color-danger", "#dc2626"), warning: v("--color-warning", "#d97706"),
    info: v("--color-info", "#2563eb"), muted: v("--color-muted", "#64748b"), border: v("--color-border", "#e2e8f0"), text: v("--color-text", "#0f172a")
  };
}
const prefersReducedMotion = () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;

function errorMessage(error) {
  if (error && error.status === 403) return "This session no longer has admin access. Sign in again to see reliability data.";
  if (error && error.malformed) return "The server's answer was not in the expected format.";
  const m = error && error.message;
  return m && m !== "Request failed" ? m : "The reliability summary could not be loaded.";
}

/** Chart definitions: what to plot and how. Blue vs orange/red keeps them distinguishable without red/green contrast. */
const CHARTS = [
  { key: "calls", title: "AI calls", sub: "successful vs failed", type: "bar", stacked: true, empty: "No AI calls in this period.",
    has: (cd) => cd.has.calls,
    datasets: (c) => [
      { label: "Successful", key: "callsOk", backgroundColor: c.primary, stack: "calls" },
      { label: "Failed", key: "callsFailed", backgroundColor: c.danger, stack: "calls" }],
    tooltip: (view) => ({ afterBody: (items) => { const r = view.cd && view.cd.successRate[items[0].dataIndex]; return r == null ? "" : `Success rate: ${fmtPercent(r)}`; } }) },
  { key: "latency", title: "AI latency", sub: "successful calls", type: "line", empty: "No successful AI calls in this period.", yTitle: "seconds", decimals: true,
    has: (cd) => cd.has.latency,
    datasets: (c) => [
      { label: "Median (p50)", key: "p50", borderColor: c.primary, backgroundColor: c.primary, pointRadius: 2, tension: 0.25, spanGaps: true, borderWidth: 2 },
      { label: "Slowest 5% start here (p95)", key: "p95", borderColor: c.warning, backgroundColor: c.warning, pointRadius: 2, tension: 0.25, spanGaps: true, borderWidth: 2 },
      { label: "p95 degraded threshold", key: "warnLine", borderColor: c.danger, borderDash: [6, 4], pointRadius: 0, borderWidth: 1.5, fill: false }],
    tooltip: () => ({ label: (item) => `${item.dataset.label}: ${fmtMs(item.parsed.y * 1000)}` }) },
  { key: "yield", title: "Question yield", sub: "questions kept vs dropped", type: "bar", stacked: true, empty: "No question generation in this period.",
    has: (cd) => cd.has.yield,
    datasets: (c) => [
      { label: "Kept", key: "kept", backgroundColor: c.primary, stack: "q" },
      { label: "Dropped", key: "dropped", backgroundColor: c.warning, stack: "q" }] },
  { key: "blocks", title: "Limit blocks & generation failures", sub: "per period", type: "bar", empty: "No limit blocks or generation failures in this period.",
    has: (cd) => cd.has.blocks,
    datasets: (c) => [
      { label: "Daily-limit blocks", key: "limitBlocks", backgroundColor: c.info },
      { label: "Generation failures", key: "pipelineFailures", backgroundColor: c.danger }] }
];

class ReliabilityView {
  constructor(el, ctx) {
    this.host = el;
    this.ctx = ctx || {};
    this.life = createLifecycle();
    this.destroyed = false;
    this.prefs = readPrefs();
    this.data = null;          // last good summary
    this.cd = null;            // chartData(this.data)
    this.error = null;         // last load error (cleared by a good load)
    this.token = 0;
    this.controller = null;
    this.fetchedAt = 0;
    this.charts = new Map();
    this.colors = palette();
    this.sort = { key: "level", dir: "desc" };
    this.incidentFilter = "all";
    this.incidentsAll = false;
    this.statusSig = "";
    this.refreshTimer = null;
    this.tickTimer = null;
  }

  get api() {
    if (this.ctx.api && typeof this.ctx.api.get === "function") return this.ctx.api;
    throw new Error("Reliability needs ctx.api.get");
  }

  start() {
    ensureStyles();
    this.build();
    swap(this.host, this.root);
    this.syncRefreshTimer();
    this.tickTimer = setInterval(() => this.tick(), TICK_MS);
    this.life.on(document, "visibilitychange", () => {
      if (document.visibilityState === "visible" && this.prefs.auto && Date.now() - this.fetchedAt > REFRESH_MS) this.load();
    });
    this.renderAll();
    this.load();
  }

  destroy() {
    this.destroyed = true;
    if (this.controller) this.controller.abort();
    clearInterval(this.refreshTimer);
    clearInterval(this.tickTimer);
    for (const chart of this.charts.values()) { try { chart.destroy(); } catch { /* already gone */ } }
    this.charts.clear();
    this.life.dispose();
    if (this.root) this.root.remove();
  }

  syncRefreshTimer() {
    clearInterval(this.refreshTimer);
    this.refreshTimer = null;
    if (this.prefs.auto) this.refreshTimer = setInterval(() => { if (!document.hidden) this.load(); }, REFRESH_MS);
  }

  tick() {
    if (this.destroyed || !this.data) return;
    this.renderLive();
    this.renderUpdated();
  }

  async load() {
    this.token += 1;
    const mine = this.token;
    if (this.controller) this.controller.abort();
    this.controller = typeof AbortController === "function" ? new AbortController() : null;
    this.root.setAttribute("aria-busy", "true");
    this.refs.refresh.setAttribute("aria-disabled", "true");
    try {
      const raw = await this.api.get(SUMMARY_PATH(this.prefs.range), this.controller ? { signal: this.controller.signal } : undefined);
      if (this.destroyed || mine !== this.token) return;
      if (!validateSummary(raw) || raw.range !== this.prefs.range) throw Object.assign(new Error("Malformed reliability summary"), { malformed: true });
      this.data = raw;
      this.cd = chartData(raw);
      this.fetchedAt = Date.now();
      this.error = null;
    } catch (error) {
      if (this.destroyed || mine !== this.token || (error && error.name === "AbortError")) return;
      if (error && error.status === 401) { window.location.href = "/login.html"; return; }
      this.error = error;
    }
    this.root.removeAttribute("aria-busy");
    this.refs.refresh.removeAttribute("aria-disabled");
    this.renderAll();
  }

  setRange(key) {
    if (key === this.prefs.range) return;
    this.prefs.range = key;
    savePrefs(this.prefs);
    this.data = null;          // never show one range's figures under another range's heading
    this.cd = null;
    this.error = null;
    this.statusSig = "";
    this.incidentsAll = false;
    this.renderAll();
    this.load();
  }

  serverNowDate() { return this.data ? serverNow(this.data.generatedAt, this.fetchedAt) : new Date(); }

  build() {
    const r = (this.refs = {});
    r.rangeButtons = RANGES.map((range) => h("button", { type: "button", "data-range": range.key, "aria-pressed": "false" }, range.label));
    r.updated = h("span", { class: "rel-updated" });
    r.autoBox = h("input", { type: "checkbox", id: "rel-auto" });
    r.autoBox.checked = this.prefs.auto;
    r.refresh = h("button", { type: "button", class: "btn" }, "↻ Refresh now");
    r.toolbar = h("div", { class: "rel-toolbar" },
      h("div", { class: "rel-seg", role: "group", "aria-label": "Time range" }, r.rangeButtons),
      h("div", { class: "rel-tools" },
        r.updated,
        h("label", { class: "rel-auto", for: "rel-auto" }, r.autoBox, `Auto-refresh (${REFRESH_MS / 1000} s)`),
        r.refresh,
        h("a", { class: "btn", href: CSV_PATH, download: "" }, "⬇ Export CSV")));

    r.alertError = h("div", { class: "rel-alert rel-alert--warn", role: "alert", hidden: true });
    r.alertNote = h("div", { class: "rel-alert rel-alert--warn", role: "status", hidden: true });

    // Overall status: the live region holds only what should be announced.
    r.statusLive = h("div", { class: "rel-status-main", role: "status", "aria-live": "polite", "aria-atomic": "true" });
    r.statusReasons = h("ul", { class: "rel-reasons" });
    r.statusLines = h("ul", { class: "rel-live" });
    r.period = h("p", { class: "rel-period" });
    r.status = h("section", { class: "admin-card rel-status", "aria-labelledby": "rel-h-status", "data-tone": "unknown" },
      h("h2", { class: "rel-sr", id: "rel-h-status" }, "Overall status"),
      r.statusLive, r.statusReasons, r.statusLines, r.period);

    r.kpiHeadSmall = h("small");
    r.kpis = h("div", { class: "rel-kpis" });
    r.kpiSection = h("section", { "aria-labelledby": "rel-h-kpi" },
      h("h2", { class: "rel-h", id: "rel-h-kpi" }, "Key measures ", r.kpiHeadSmall), r.kpis);

    r.chartParts = {};
    const chartCards = CHARTS.map((def) => {
      const canvas = h("canvas", { role: "img", "aria-label": def.title });
      const note = h("p", { class: "rel-chart-note", hidden: true });
      const body = h("div", { class: "rel-chart-body" }, canvas, note);
      r.chartParts[def.key] = { canvas, note, body };
      return h("figure", { class: "admin-card rel-chart", "data-chart": def.key },
        h("figcaption", { class: "rel-chart-title" }, def.title, h("small", null, def.sub)), body);
    });
    r.causes = h("section", { class: "admin-card" });
    r.limits = h("section", { class: "admin-card" });
    r.lists = h("div", { class: "rel-lists" }, r.causes, r.limits);
    r.dataTable = h("details", { class: "rel-data" });
    r.trends = h("section", { "aria-labelledby": "rel-h-trends" },
      h("h2", { class: "rel-h", id: "rel-h-trends" }, "Trends"),
      h("div", { class: "rel-charts" }, chartCards), r.lists, r.dataTable);

    r.tasks = h("section", { class: "admin-card", "aria-labelledby": "rel-h-tasks" });
    r.incidents = h("section", { class: "admin-card", "aria-labelledby": "rel-h-incidents" });
    r.how = h("details", { class: "admin-card rel-how" });

    r.content = h("div", { class: "rel-content" }, r.kpiSection, r.trends, r.tasks, r.incidents, r.how);
    this.root = h("div", { class: "rel-root", "data-view": "reliability" }, r.toolbar, r.alertError, r.alertNote, r.status, r.content);

    for (const btn of r.rangeButtons) this.life.on(btn, "click", () => this.setRange(btn.getAttribute("data-range")));
    this.life.on(r.refresh, "click", () => this.load());
    this.life.on(r.autoBox, "change", () => { this.prefs.auto = r.autoBox.checked; savePrefs(this.prefs); this.syncRefreshTimer(); });
  }

  renderAll() {
    if (this.destroyed) return;
    const r = this.refs;
    this.renderToolbar();
    this.renderAlerts();
    this.renderStatus();
    const failedWithoutData = !this.data && Boolean(this.error);
    r.content.hidden = failedWithoutData;
    // Cards that only make sense with data stay hidden while it is loading, rather than showing empty boxes.
    for (const el of [r.lists, r.dataTable, r.tasks, r.incidents, r.how]) el.hidden = !this.data;
    if (failedWithoutData) return;
    this.renderKpis();
    this.renderCharts();
    this.renderCauses();
    this.renderTasks();
    this.renderIncidents();
    this.renderDataTable();
    this.renderHow();
  }

  renderToolbar() {
    for (const btn of this.refs.rangeButtons) btn.setAttribute("aria-pressed", String(btn.getAttribute("data-range") === this.prefs.range));
    this.renderUpdated();
  }

  renderUpdated() {
    const el = this.refs.updated;
    if (!this.data) { el.textContent = ""; el.removeAttribute("data-stale"); return; }
    const at = fmtClock(parseTime(this.data.generatedAt));
    if (this.error) { el.textContent = `Out of date · last updated ${at}`; el.setAttribute("data-stale", "true"); }
    else { el.textContent = `Updated ${at}`; el.removeAttribute("data-stale"); }
  }

  renderAlerts() {
    const { alertError, alertNote } = this.refs;
    if (this.error && this.data) {
      swap(alertError,
        h("p", null, `The data could not be refreshed. Showing the last successful update (${fmtClock(parseTime(this.data.generatedAt))}). ${errorMessage(this.error)}`),
        this.retryButton());
      alertError.hidden = false;
    } else { swap(alertError); alertError.hidden = true; }
    if (this.data && this.data.truncated) {
      swap(alertNote, h("p", null, "This period holds more events than can be summarised at once, so only the most recent are counted and the comparison with the previous period is hidden. Pick a shorter range for complete figures."));
      alertNote.hidden = false;
    } else { swap(alertNote); alertNote.hidden = true; }
  }

  retryButton(label = "Try again") {
    const btn = h("button", { type: "button", class: "rel-link" }, label);
    btn.addEventListener("click", () => this.load(), { signal: this.life.signal });
    return btn;
  }

  renderStatus() {
    const { status, statusLive, statusReasons, statusLines, period } = this.refs;
    if (!this.data) {
      status.setAttribute("data-tone", "unknown");
      this.statusSig = "";
      swap(statusLive, this.error
        ? h("div", { class: "rel-error", style: "flex:1 1 100%;" }, h("p", null, errorMessage(this.error)), this.retryButton())
        : h("p", { class: "rel-headline" }, `Loading ${rangeText(this.prefs.range).toLowerCase()}…`));
      swap(statusReasons); swap(statusLines); swap(period);
      statusReasons.hidden = true;
      return;
    }
    const hl = healthHeadline(this.data);
    const sig = JSON.stringify([hl.meta.label, hl.headline, hl.more.map((m) => m.message), this.prefs.range]);
    if (sig !== this.statusSig) {
      this.statusSig = sig;
      status.setAttribute("data-tone", hl.meta.tone);
      swap(statusLive,
        h("span", { class: "rel-badge", "data-tone": hl.meta.tone }, iconAndLabel(hl.meta)),
        h("p", { class: "rel-headline" }, hl.headline));
      swap(statusReasons, hl.more.map((m) => h("li", null, m.message)));
    }
    statusReasons.hidden = hl.more.length === 0;
    this.renderLive();
    const w = this.data.window;
    const prev = this.data.previous;
    swap(period, this.prefs.range === "all"
      ? `Period: all recorded events, from ${fmtDateTime(parseTime(w.from))} to now.`
      : `Period: ${fmtDateTime(parseTime(w.from))} to now.${prev.available ? ` Compared with the ${rangeMeta(this.prefs.range).compare} (${fmtDateTime(parseTime(prev.from))} to ${fmtDateTime(parseTime(prev.to))}).` : ""}`);
  }

  renderLive() {
    if (!this.data) return;
    swap(this.refs.statusLines, liveLines(this.data, this.serverNowDate()).map((l) => h("li", { "data-tone": l.tone }, l.text)));
  }

  renderKpis() {
    const box = this.refs.kpis;
    swap(this.refs.kpiHeadSmall, rangeText(this.prefs.range));
    if (!this.data) {
      swap(box, Array.from({ length: 6 }, () => h("div", { class: "rel-kpi rel-kpi--loading", "aria-hidden": "true" },
        h("span", { class: "rel-skel", style: "height:12px;width:60%;" }),
        h("span", { class: "rel-skel", style: "height:30px;width:45%;" }),
        h("span", { class: "rel-skel", style: "height:12px;width:80%;" }))));
      return;
    }
    const cmp = rangeMeta(this.prefs.range).compare;
    swap(box, kpiCards(this.data).map((c) => h("div", { class: "rel-kpi", "data-tone": c.meta ? c.meta.tone : "none", "data-kpi": c.id, role: "group", "aria-label": c.label },
      h("div", { class: "rel-kpi-label", title: c.hint }, c.label),
      h("div", { class: "rel-kpi-value" }, c.value),
      h("div", { class: "rel-kpi-sub" }, c.sub),
      h("span", { class: "rel-sr" }, c.hint),
      h("div", { class: "rel-kpi-foot" },
        c.meta ? h("span", { class: "rel-pill", "data-tone": c.meta.tone }, iconAndLabel(c.meta)) : null,
        c.delta ? h("span", { class: "rel-delta", "data-tone": c.delta.tone },
          h("span", { "aria-hidden": "true" }, `${c.delta.arrow} ${c.delta.text}`),
          h("span", { class: "rel-sr" }, c.delta.sr),
          cmp ? h("span", { class: "rel-delta-note" }, ` vs ${cmp}`) : null) : null))));
  }

  chartOptions(def) {
    const c = this.colors;
    const tick = { font: { size: 10 }, color: c.muted };
    return {
      responsive: true, maintainAspectRatio: false,
      animation: prefersReducedMotion() ? false : { duration: 200 },
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { position: "bottom", labels: { boxWidth: 12, boxHeight: 12, font: { size: 11 }, color: c.text } },
        tooltip: { callbacks: { title: (items) => (this.cd && items.length ? this.cd.titles[items[0].dataIndex] : ""), ...(def.tooltip ? def.tooltip(this) : {}) } }
      },
      scales: {
        x: { stacked: Boolean(def.stacked), grid: { display: false }, ticks: { ...tick, autoSkip: true, maxTicksLimit: 8, maxRotation: 0 } },
        y: {
          stacked: Boolean(def.stacked), beginAtZero: true, grid: { color: c.border },
          ticks: def.decimals ? tick : { ...tick, precision: 0 },
          title: def.yTitle ? { display: true, text: def.yTitle, font: { size: 10 }, color: c.muted } : { display: false }
        }
      }
    };
  }

  renderCharts() {
    const Ctor = typeof window !== "undefined" ? window.Chart : undefined;
    const texts = this.data ? chartSummaries(this.data) : null;
    for (const def of CHARTS) {
      const part = this.refs.chartParts[def.key];
      part.canvas.setAttribute("aria-label", texts ? texts[def.key] : def.title);
      const showNote = (text, mode) => {
        part.note.textContent = text;
        part.note.hidden = false;
        part.body.classList.toggle("rel-chart-body--empty", mode === "empty");
        part.body.classList.toggle("rel-chart-body--off", mode === "off");
      };
      if (!this.data) { showNote("Loading…", "off"); continue; }
      if (typeof Ctor !== "function") { showNote("The chart library could not be loaded. The figures above and the data table below are unaffected.", "off"); continue; }

      const specs = def.datasets(this.colors);
      let chart = this.charts.get(def.key);
      if (!chart) {
        try {
          chart = new Ctor(part.canvas.getContext("2d"), {
            type: def.type,
            data: { labels: [], datasets: specs.map((ds) => ({ ...ds, data: [], borderRadius: def.type === "bar" ? 3 : undefined })) },
            options: this.chartOptions(def)
          });
          this.charts.set(def.key, chart);
        } catch { showNote("This chart could not be drawn. The data table below has the same figures.", "off"); continue; }
      }
      chart.data.labels = this.cd.labels;
      chart.data.datasets.forEach((ds, i) => { ds.data = this.cd[specs[i].key]; });
      chart.update("none");

      if (def.has(this.cd)) {
        part.note.hidden = true;
        part.body.classList.remove("rel-chart-body--empty", "rel-chart-body--off");
      } else showNote(def.empty, "empty");
    }
  }

  barList(rows, labelKey, cls, formatLabel = (s) => s) {
    return h("ul", { class: `rel-bars ${cls}` }, barRows(rows, labelKey).map((r) => h("li", null,
      h("span", { class: "rel-bar-label" }, formatLabel(r.label)),
      h("span", { class: "rel-bar-count" }, `${fmtInt(r.count)} · ${r.sharePct}%`),
      h("span", { class: "rel-bar-track", "aria-hidden": "true" }, h("span", { style: `width:${r.widthPct}%` })))));
  }

  renderCauses() {
    if (!this.data) return;
    const { api, limits } = this.data;
    swap(this.refs.causes,
      h("h3", { class: "rel-sub-h" }, "Why AI calls failed"),
      h("p", { class: "rel-sub-hint" }, "Failed calls grouped by what the AI provider (or the network) returned."),
      api.failureCauses.length ? this.barList(api.failureCauses, "label", "rel-bars--causes")
        : api.calls === 0 ? h("p", { class: "rel-none" }, "No AI calls in this period.")
          : h("p", { class: "rel-good" }, "✓ No failed AI calls in this period."));
    swap(this.refs.limits,
      h("h3", { class: "rel-sub-h" }, "Daily-limit blocks by action"),
      h("p", { class: "rel-sub-hint" }, "Students stopped by a daily limit. This is policy working as intended, not a fault."),
      limits.byAction.length ? this.barList(limits.byAction, "action", "rel-bars--limits", actionLabel)
        : h("p", { class: "rel-none" }, "No actions were blocked by a daily limit in this period."));
  }

  renderTasks() {
    if (!this.data) return;
    const head = h("div", { class: "rel-card-head" },
      h("div", null, h("h2", { class: "rel-h", id: "rel-h-tasks" }, "By task"),
        h("p", { class: "rel-sub-hint", style: "margin:0;" }, "Each AI task and quiz-generation flow on its own. Worst first by default; use a column heading to re-sort.")));
    if (this.data.tasks.length === 0) {
      swap(this.refs.tasks, head, h("p", { class: "rel-none" }, "No AI calls or quiz-generation runs were recorded in this period."));
      return;
    }
    const rows = sortTasks(this.data.tasks, this.sort.key, this.sort.dir);
    const headerCells = TASK_COLUMNS.map((col) => {
      const active = this.sort.key === col.key;
      const btn = h("button", { type: "button", class: "rel-sort", "data-active": String(active) },
        col.label, h("span", { class: "rel-sort-arrow", "aria-hidden": "true" }, active ? (this.sort.dir === "asc" ? "▲" : "▼") : "↕"));
      btn.addEventListener("click", () => {
        if (this.sort.key === col.key) this.sort.dir = this.sort.dir === "asc" ? "desc" : "asc";
        else this.sort = { key: col.key, dir: col.key === "task" ? "asc" : "desc" };
        this.renderTasks();
        const again = this.refs.tasks.querySelector(`th[data-col="${col.key}"] button`);
        if (again) again.focus();
      }, { signal: this.life.signal });
      return h("th", { scope: "col", class: col.numeric ? "rel-num" : "", "data-col": col.key, "aria-sort": active ? (this.sort.dir === "asc" ? "ascending" : "descending") : "none" }, btn);
    });
    const body = rows.map((t) => {
      const meta = levelMeta(t.level);
      return h("tr", null,
        h("th", { scope: "row" }, h("span", { class: "rel-code" }, t.task)),
        h("td", null, h("span", { class: "rel-pill", "data-tone": meta.tone }, iconAndLabel(meta))),
        h("td", { class: "rel-num" }, fmtInt(t.calls)),
        h("td", { class: "rel-num" }, t.calls ? `${fmtPercent(t.successRate)} (${fmtInt(t.failures)} failed)` : "—"),
        h("td", { class: "rel-num" }, fmtMs(t.p95)),
        h("td", { class: "rel-num" }, t.kept + t.dropped ? `${fmtPercent(t.yieldRate)} (${fmtInt(t.dropped)} dropped)` : "—"),
        h("td", { class: "rel-num" }, t.batches + t.pipelineFailures ? fmtInt(t.pipelineFailures) : "—"));
    });
    swap(this.refs.tasks, head, h("div", { class: "table-scroll", tabindex: "0", role: "region", "aria-label": "Reliability by task (scrollable)" },
      h("table", { class: "admin-table rel-table" },
        h("caption", { class: "rel-sr" }, "Reliability by task"),
        h("thead", null, h("tr", null, headerCells)),
        h("tbody", null, body))));
  }

  renderIncidents() {
    if (!this.data) return;
    const all = this.data.incidents;
    const filtered = filterIncidents(all, this.incidentFilter);
    const shown = this.incidentsAll ? filtered : filtered.slice(0, INCIDENTS_SHOWN);

    const select = h("select", { id: "rel-incident-filter" }, INCIDENT_FILTERS.map((f) => h("option", { value: f.key }, f.label)));
    select.value = this.incidentFilter;
    select.addEventListener("change", () => {
      this.incidentFilter = select.value;
      this.incidentsAll = false;
      this.renderIncidents();
      const again = this.refs.incidents.querySelector("#rel-incident-filter");
      if (again) again.focus();
    }, { signal: this.life.signal });

    const head = h("div", { class: "rel-card-head" },
      h("div", null, h("h2", { class: "rel-h", id: "rel-h-incidents" }, "Recent incidents"),
        h("p", { class: "rel-sub-hint", style: "margin:0;" }, "Failed AI calls and failed quiz generations, newest first. Daily-limit blocks are not incidents.")),
      all.length ? h("label", { class: "rel-filter", for: "rel-incident-filter" }, "Show", select) : null);

    if (all.length === 0) {
      swap(this.refs.incidents, head, h("p", { class: this.data.events === 0 ? "rel-none" : "rel-good" },
        this.data.events === 0 ? "No events in this period." : "✓ No failed AI calls or generation failures in this period."));
      return;
    }
    if (filtered.length === 0) { swap(this.refs.incidents, head, h("p", { class: "rel-none" }, "No incidents of this type in this period.")); return; }

    const rows = shown.map((i) => {
      const text = i.detail ? String(i.detail) : "";
      const long = text.length > DETAIL_CLAMP;
      const detail = h("div", { class: `rel-detail${long ? " rel-detail--clamped" : ""}` }, text || "—");
      const toggle = long ? h("button", { type: "button", class: "rel-link", "aria-expanded": "false" }, "Show full text") : null;
      if (toggle) {
        toggle.addEventListener("click", () => {
          const open = toggle.getAttribute("aria-expanded") === "true";
          toggle.setAttribute("aria-expanded", String(!open));
          toggle.textContent = open ? "Show full text" : "Hide full text";
          detail.classList.toggle("rel-detail--clamped", open);
        }, { signal: this.life.signal });
      }
      return h("tr", null,
        h("td", null, h("time", { datetime: i.at || "", title: i.at || "" }, fmtDateTime(parseTime(i.at)))),
        h("td", null, h("span", { class: "rel-kind" }, incidentKindLabel(i.kind))),
        h("td", null, h("span", { class: "rel-code" }, i.task || "—")),
        h("td", { class: "rel-wrap" }, i.cause),
        h("td", { class: "rel-wrap" }, detail, toggle));
    });

    let more = null;
    if (filtered.length > INCIDENTS_SHOWN) {
      const btn = h("button", { type: "button", class: "rel-link" }, this.incidentsAll ? "Show fewer" : `Show all ${filtered.length}`);
      btn.addEventListener("click", () => { this.incidentsAll = !this.incidentsAll; this.renderIncidents(); }, { signal: this.life.signal });
      more = h("p", { class: "rel-more-row" }, btn);
    }
    swap(this.refs.incidents, head,
      h("div", { class: "table-scroll", tabindex: "0", role: "region", "aria-label": "Recent incidents (scrollable)" },
        h("table", { class: "admin-table rel-table" },
          h("caption", { class: "rel-sr" }, "Recent incidents"),
          h("thead", null, h("tr", null, ["Time", "Type", "Task", "Cause", "Details"].map((t) => th(t, false)))),
          h("tbody", null, rows))),
      more);
  }

  renderDataTable() {
    if (!this.data) return;
    const box = this.refs.dataTable;
    const wasOpen = box.open;
    const step = this.data.window.stepMinutes;
    const cols = ["Period", "AI calls", "Failed", "Success", "p50", "p95", "Kept", "Dropped", "Limit blocks", "Gen. failures"];
    const rows = this.data.series.map((b) => h("tr", null,
      h("th", { scope: "row" }, bucketTitle(b.start, step)),
      h("td", { class: "rel-num" }, fmtInt(b.calls)),
      h("td", { class: "rel-num" }, fmtInt(b.failures)),
      h("td", { class: "rel-num" }, b.calls ? fmtPercent((b.calls - b.failures) / b.calls) : "—"),
      h("td", { class: "rel-num" }, fmtMs(b.p50)),
      h("td", { class: "rel-num" }, fmtMs(b.p95)),
      h("td", { class: "rel-num" }, fmtInt(b.kept)),
      h("td", { class: "rel-num" }, fmtInt(b.dropped)),
      h("td", { class: "rel-num" }, fmtInt(b.limitBlocks)),
      h("td", { class: "rel-num" }, fmtInt(b.pipelineFailures))));
    swap(box, h("summary", null, "Show the chart data as a table"),
      h("div", { class: "table-scroll", tabindex: "0", role: "region", "aria-label": "Chart data (scrollable)" },
        h("table", { class: "admin-table rel-table", style: "min-width:900px;" },
          h("caption", { class: "rel-sr" }, "Reliability figures for each period shown in the charts"),
          h("thead", null, h("tr", null, cols.map((c, i) => th(c, i > 0)))),
          h("tbody", null, rows))));
    box.open = wasOpen;
  }

  renderHow() {
    if (!this.data) return;
    const box = this.refs.how;
    const wasOpen = box.open;
    swap(box, h("summary", null, "How these figures are calculated"),
      h("ul", null, thresholdLines(this.data.health.thresholds).map((t) => h("li", null, t))),
      h("p", null, "An AI call is one request to the AI provider; the automatic retries after a rate-limit or server error each count as their own call. Latency uses successful calls only, because a call that fails instantly or times out would distort it. Question yield is the share of generated questions that passed validation. Times are the server's own timestamps."),
      h("p", null, "The status is a guide built on the default thresholds above, not a service-level guarantee. Export CSV downloads every raw event."));
    box.open = wasOpen;
  }
}

let current = null;

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Reliability view requires a mount element");
    if (current) { current.destroy(); current = null; }
    current = new ReliabilityView(el, ctx);
    current.start();
  },
  unmount() {
    if (current) { current.destroy(); current = null; }
  }
};
