/**
 * Overview view: the administrative landing page (#/overview).
 *
 * Replaces the transitional adapter over the legacy renderOverview() and absorbs the useful content of the old
 * admindashboard.html (see INTEGRATION-NOTES.md). Contract: default export { mount(el, ctx), unmount(ctx) }.
 *
 * - Self-contained: fetches through ctx.api.get (the shell wires this to the existing apiFetch), never reads the
 *   legacy `adminData` global and never calls renderCurrentTab(), so the legacy overview cannot overwrite it.
 * - Every data source loads, fails and retries independently; a failed source is shown as an error, never as zero.
 * - All dynamic text is inserted with textContent / createTextNode. There is no innerHTML in this file.
 * - Cleans up on unmount: aborts in-flight requests, destroys charts, removes listeners.
 */
import {
  ROUTES, QUICK_ACTIONS, CATEGORY_RANGES, CHART_PALETTE, toHref,
  buildKpis, platformRatios, accountBreakdown, activityPresentation, recentActivity, recentBanned,
  difficultyBuckets, scoreBuckets, averageScore, topicStats, topicPerformance, buildAttention, reportsGroup, deletionsGroup, flaggedUsersGroup
} from "../overview-model.js";
import { getDeletionBadge } from "../people/deletion-badge.js";

const CSS_ID = "admin-overview-css";
const STALE_AFTER_MS = 60_000;
const TOPIC_ROWS = 8;

function ensureStyles() {
  if (typeof document === "undefined" || document.getElementById(CSS_ID)) return;
  const link = document.createElement("link");
  link.id = CSS_ID;
  link.rel = "stylesheet";
  link.href = new URL("../overview.css", import.meta.url).href;
  document.head.append(link);
}

function h(tag, props, ...kids) {
  const node = document.createElement(tag);
  if (props) {
    for (const [key, value] of Object.entries(props)) {
      if (value == null || value === false) continue;
      if (key === "class") node.className = value;
      else if (key === "text") node.textContent = value;
      else if (key.startsWith("on") && typeof value === "function") node.addEventListener(key.slice(2).toLowerCase(), value);
      else node.setAttribute(key, value === true ? "" : String(value));
    }
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    node.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return node;
}

const link = (path, text, cls) => h("a", { class: cls, href: toHref(path) }, text);
const fmtDate = (d) => (d ? d.toLocaleDateString() : "");
const fmtDateTime = (d) => (d ? d.toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "");
const fmtValue = (v) => (v == null ? "—" : String(v));
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

function greeting(name) {
  const hour = new Date().getHours();
  const part = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
  const first = String(name || "").trim().split(/\s+/)[0];
  return first ? `${part}, ${first}` : part;
}

function redirectToLogin() { window.location.href = "/login.html"; }

// Source table. `validate` rejects malformed bodies so a bad response is an error, not an empty list.
const SOURCES = {
  stats: { path: () => "/api/admin/overview", validate: (d) => d && typeof d.stats === "object" && d.stats, pick: (d) => d.stats },
  users: { path: () => "/api/admin/users", validate: (d) => d && Array.isArray(d.users), pick: (d) => d.users },
  banned: { path: () => "/api/admin/banned-emails", validate: (d) => d && Array.isArray(d.bannedEmails), pick: (d) => d.bannedEmails },
  log: { path: () => "/api/admin/activity-log", validate: (d) => d && Array.isArray(d.log), pick: (d) => d.log },
  reports: { path: () => "/api/admin/reports?status=PENDING", validate: (d) => d && Array.isArray(d.reports), pick: (d) => d },
  delPending: { path: () => "/api/admin/deletion-requests?status=PENDING", validate: (d) => d && Array.isArray(d.requests), pick: (d) => d },
  delApproved: { path: () => "/api/admin/deletion-requests?status=APPROVED", validate: (d) => d && Array.isArray(d.requests), pick: (d) => d },
  // User-initiated deletions (single-admin review). Kept apart from the dual-control sources above on purpose.
  // A body that is not an object with a `requests` array (or that says success:false) is malformed, never "no requests".
  selfDeletions: { path: () => "/api/admin/self-deletion-requests", validate: (d) => d && typeof d === "object" && d.success !== false && Array.isArray(d.requests), pick: (d) => d },
  quiz: { path: () => "/api/admin/quiz-activity", validate: (d) => d && Array.isArray(d.quizHistory), pick: (d) => d.quizHistory },
  topics: { path: () => "/api/admin/topics", validate: (d) => Array.isArray(d), pick: (d) => d },
  categories: { path: (view) => `/api/admin/material-categories?range=${encodeURIComponent(view.range)}`, validate: (d) => d && typeof d === "object" && Array.isArray(d.categoryBreakdown), pick: (d) => d }
};
// Exposed (read-only) so tests can check the real validators and paths against fixtures instead of copying them.
export const OVERVIEW_SOURCES = SOURCES;
const ATTENTION_SOURCES = ["reports", "delPending", "delApproved", "selfDeletions", "users"];
const SECTIONS_BY_SOURCE = {
  stats: ["kpis", "ratios"], users: ["kpis", "accounts", "attention"], banned: ["accounts"], log: ["accounts", "activity"],
  reports: ["attention"], delPending: ["attention"], delApproved: ["attention"], selfDeletions: ["attention"],
  quiz: ["quiz", "topics"], topics: ["topics"], categories: ["categories"]
};
const GROUP_SOURCES = { reports: ["reports"], deletions: ["delPending", "delApproved"], selfDeletions: ["selfDeletions"], flagged: ["users"] };

class OverviewView {
  constructor(el, ctx) {
    this.host = el;
    this.ctx = ctx || {};
    this.destroyed = false;
    this.range = "all";
    this.charts = new Map();
    this.sources = Object.fromEntries(Object.keys(SOURCES).map((name) => [name, { status: "loading", data: null, error: null, token: 0, controller: null, at: 0, startedAt: 0 }]));
    this.lastAttentionLoad = 0;
    this.onVisibility = () => {
      if (document.visibilityState === "visible" && Date.now() - this.lastAttentionLoad > STALE_AFTER_MS) this.refreshAttention();
    };
  }

  // ── lifecycle ─────────────────────────────────────────────────────────────────
  start() {
    ensureStyles();
    this.root = h("div", { class: "ov-root", "data-overview": "" });
    this.refs = {};
    const section = (name, tag, cls, attrs) => (this.refs[name] = h(tag, { class: cls, ...attrs }));

    this.refreshBtn = h("button", { type: "button", class: "btn ghost ov-refresh", onclick: () => this.refreshAll() }, "↻ Refresh");
    this.updated = h("span", { class: "ov-updated" });
    this.root.append(
      h("div", { class: "ov-head" },
        h("div", null,
          h("p", { class: "ov-greeting" }, greeting(this.ctx.session && this.ctx.session.user && this.ctx.session.user.name)),
          h("p", { class: "ov-sub" }, "Platform status and what needs your attention. ", this.updated)),
        this.refreshBtn),
      section("attention", "section", "admin-card ov-attention", { "aria-labelledby": "ov-attn-title" }),
      section("kpis", "div", "admin-tiles ov-kpis", { role: "group", "aria-label": "Platform KPIs" }),
      h("div", { class: "ov-grid" },
        section("quick", "section", "admin-card", { "aria-labelledby": "ov-quick-title" }),
        section("accounts", "section", "admin-card", { "aria-labelledby": "ov-acct-title" })),
      h("div", { class: "ov-grid" },
        section("activity", "section", "admin-card", { "aria-labelledby": "ov-act-title" }),
        section("ratios", "section", "admin-card", { "aria-labelledby": "ov-ratio-title" })),
      section("categories", "section", "admin-card", { "aria-labelledby": "ov-cat-title" }),
      section("quiz", "section", "admin-card", { "aria-labelledby": "ov-quiz-title" }),
      section("topics", "section", "admin-card", { "aria-labelledby": "ov-topics-title" })
    );
    this.host.replaceChildren(this.root);
    this.renderQuick();
    this.renderAll();
    document.addEventListener("visibilitychange", this.onVisibility);
    this.refreshAll();
  }

  destroy() {
    this.destroyed = true;
    document.removeEventListener("visibilitychange", this.onVisibility);
    for (const src of Object.values(this.sources)) { src.token += 1; if (src.controller) src.controller.abort(); }
    this.destroyCharts();
    if (this.root) this.root.remove();
  }

  // ── data ──────────────────────────────────────────────────────────────────────
  get api() {
    if (this.ctx.api && typeof this.ctx.api.get === "function") return this.ctx.api;
    if (typeof apiFetch === "function") return { get: (path, options) => apiFetch(path, options) }; // eslint-disable-line no-undef
    throw new Error("Overview needs ctx.api.get");
  }

  refreshAll() { for (const name of Object.keys(SOURCES)) this.load(name); }
  refreshAttention() { for (const name of ATTENTION_SOURCES) this.load(name); }

  async load(name) {
    const src = this.sources[name];
    const def = SOURCES[name];
    src.token += 1;
    const mine = src.token;
    src.startedAt = Date.now();         // lets the deletion badge ignore an observation older than the one it already shows
    if (src.controller) src.controller.abort();
    src.controller = typeof AbortController === "function" ? new AbortController() : null;
    src.status = "loading";            // never keep showing the previous result while it is being replaced
    this.refreshSections(SECTIONS_BY_SOURCE[name]);
    try {
      const raw = await this.api.get(def.path(this), src.controller ? { signal: src.controller.signal } : undefined);
      if (this.destroyed || mine !== src.token) return;
      if (!def.validate(raw)) throw Object.assign(new Error("The server's answer was not in the expected format."), { malformed: true });
      src.data = def.pick(raw);
      src.status = "ok";
      src.error = null;
      src.at = Date.now();
    } catch (error) {
      if (this.destroyed || mine !== src.token || (error && error.name === "AbortError")) return;
      if (error && error.status === 401) { redirectToLogin(); return; }
      src.data = null;
      src.status = "error";
      src.error = error;
    }
    if (ATTENTION_SOURCES.includes(name)) { this.lastAttentionLoad = Date.now(); this.syncBadges(); }
    this.refreshSections(SECTIONS_BY_SOURCE[name]);
    this.renderUpdated();
  }

  syncBadges() {
    const badges = this.ctx.badges;
    const s = this.sources;
    if (badges && typeof badges.set === "function") {
      // Same de-duplicated counts the inbox shows, so a badge can never disagree with the list beside it.
      if (s.reports.status === "ok") badges.set("content.reported-questions", reportsGroup(s.reports.data).count);
      if (s.users.status === "ok") badges.set("people.users", flaggedUsersGroup(s.users.data).count);
    }
    this.syncDeletionBadge();
  }

  // The Deletion requests badge adds up BOTH queues (admin-initiated pending + approved, and self-requested), which the
  // Overview already loads as three separate sources. They are handed over only once all three have settled, so the
  // badge can never show a partial total; if any of them failed or was malformed the badge keeps its last complete
  // count (or shows "?"). No request is made here; the derivation and the dedupe rules live in people/deletion-badge.js.
  syncDeletionBadge() {
    const s = this.sources;
    const parts = { pending: s.delPending, approved: s.delApproved, self: s.selfDeletions };
    const all = Object.values(parts);
    if (all.some((src) => src.status === "loading")) return;
    const results = Object.fromEntries(Object.entries(parts).map(([key, src]) =>
      [key, src.status === "ok" ? { ok: true, value: src.data } : { ok: false, error: src.error }]));
    getDeletionBadge({ badges: this.ctx.badges }).applyResults(results, { observedAt: Math.min(...all.map((src) => src.startedAt)) });
  }

  state(name) { return this.sources[name]; }

  // ── rendering plumbing ────────────────────────────────────────────────────────
  renderAll() { for (const name of ["attention", "kpis", "accounts", "activity", "ratios", "categories", "quiz", "topics"]) this.renderSection(name); }
  refreshSections(names) { if (this.destroyed) return; for (const name of new Set(names)) this.renderSection(name); }
  renderSection(name) {
    const fn = { attention: this.renderAttention, kpis: this.renderKpis, accounts: this.renderAccounts, activity: this.renderActivity, ratios: this.renderRatios, categories: this.renderCategories, quiz: this.renderQuiz, topics: this.renderTopics }[name];
    if (fn) fn.call(this);
  }

  renderUpdated() {
    const times = ATTENTION_SOURCES.map((n) => this.sources[n]).filter((s) => s.status === "ok").map((s) => s.at);
    const busy = ATTENTION_SOURCES.some((n) => this.sources[n].status === "loading");
    this.refreshBtn.disabled = busy;
    this.refreshBtn.textContent = busy ? "Refreshing…" : "↻ Refresh";
    this.updated.textContent = times.length && !busy ? `Updated ${new Date(Math.max(...times)).toLocaleTimeString()}.` : "";
  }

  loadingBlock(label) { return h("p", { class: "ov-state", role: "status" }, `Loading ${label}…`); }

  errorBlock(label, error, sources) {
    const malformed = !!(error && error.malformed);   // reachable server, unusable body - reported differently from a failed request
    return h("div", { class: `ov-state ov-state--error${malformed ? " ov-state--malformed" : ""}`, role: "alert", "data-error-kind": malformed ? "malformed" : "request" },
      h("p", null, h("strong", null, malformed ? `Could not read ${label}.` : `Could not load ${label}.`), " ", error && error.message ? error.message : "Request failed."),
      h("button", { type: "button", class: "btn ghost", onclick: () => sources.forEach((n) => this.load(n)) }, "Try again"));
  }

  head(id, title, hint, linkEl) {
    return h("div", { class: "ov-card-head" },
      h("div", null, h("h3", { class: "admin-card-title", id }, title), hint ? h("p", { class: "admin-card-hint" }, hint) : null),
      linkEl || null);
  }

  // ── Needs attention ───────────────────────────────────────────────────────────
  renderAttention() {
    const box = this.refs.attention;
    const model = buildAttention({ reports: this.state("reports"), delPending: this.state("delPending"), delApproved: this.state("delApproved"), selfDeletions: this.state("selfDeletions"), users: this.state("users") });
    const allLoading = model.loading.length === model.groups.length;
    let summary;
    if (allLoading) summary = "Checking…";
    else if (model.empty) summary = "Nothing needs attention";
    else if (model.complete) summary = `${plural(model.total, "open item")} across ${plural(model.groups.filter((g) => g.count > 0).length, "area")}`;
    else summary = `${plural(model.total, "open item")} found${model.loading.length ? " so far" : ""}; ${model.failures.length ? "some sources could not be checked" : "still checking"}`;

    box.replaceChildren(
      h("div", { class: "ov-card-head" },
        h("div", null,
          h("h3", { class: "admin-card-title", id: "ov-attn-title" }, "Needs attention"),
          h("p", { class: "admin-card-hint ov-summary", role: "status", "aria-live": "polite" }, summary))),
      model.empty ? h("div", { class: "ov-allclear" }, h("strong", null, "✓ All clear."), " No pending question reports, open deletion requests, self-requested deletions, or flagged accounts.") : null,
      h("div", { class: "ov-groups" }, ...model.groups.filter((g) => g.state !== "ok" || g.count > 0).map((g) => this.renderGroup(g))),
      model.groups.some((g) => g.state === "ok" && g.count === 0) && !model.empty
        ? h("p", { class: "ov-fine" }, "Nothing pending: ", model.groups.filter((g) => g.state === "ok" && g.count === 0).map((g) => g.title.toLowerCase()).join(", "), ".")
        : null,
      h("p", { class: "ov-fine" }, "Checked: reported questions, deletion requests (pending and approved), self-requested deletions, flagged accounts.")
    );
  }

  renderGroup(g) {
    const titleId = `ov-grp-${g.id}`;
    if (g.state === "loading") return h("section", { class: "ov-group", "data-group": g.id, "aria-labelledby": titleId }, h("h4", { class: "ov-group-title", id: titleId }, g.title), this.loadingBlock(g.title.toLowerCase()));
    if (g.state === "error") return h("section", { class: "ov-group", "data-group": g.id, "aria-labelledby": titleId }, h("h4", { class: "ov-group-title", id: titleId }, g.title), this.errorBlock(g.title.toLowerCase(), g.error, GROUP_SOURCES[g.id]));
    return h("section", { class: "ov-group", "data-group": g.id, "aria-labelledby": titleId },
      h("div", { class: "ov-group-head" },
        h("h4", { class: "ov-group-title", id: titleId }, g.title, " ", h("span", { class: "ov-count", "aria-label": plural(g.count, g.noun) }, String(g.count))),
        g.secondary ? h("span", { class: "ov-secondary" }, g.secondary) : null,
        link(g.route, "Open →", "ov-open")),
      h("ul", { class: "ov-items" }, ...g.items.map((item) =>
        h("li", null, h("a", { class: "ov-item", href: toHref(g.route) },
          h("span", { class: "ov-item-main" }, h("span", { class: "ov-item-title" }, item.title), item.meta ? h("span", { class: "ov-item-meta" }, item.meta) : null),
          item.badge ? h("span", { class: `ov-pill${item.tone ? " ov-pill--" + item.tone : ""}` }, item.badge) : null,
          item.when ? h("time", { class: "ov-item-when", datetime: item.when.toISOString() }, fmtDate(item.when)) : null)))),
      g.extra > 0 ? h("p", { class: "ov-more" }, link(g.route, `+${g.extra} more in ${g.title.toLowerCase()} →`)) : null,
      ...g.notes.map((note) => h("p", { class: "ov-note", role: "status" }, note, " ", h("button", { type: "button", class: "ov-linkbtn", onclick: () => GROUP_SOURCES[g.id].forEach((n) => this.load(n)) }, "Retry"))));
  }

  // ── KPIs, ratios ──────────────────────────────────────────────────────────────
  renderKpis() {
    const stats = this.state("stats"), users = this.state("users");
    const tiles = stats.status === "ok" ? buildKpis(stats.data, users.status === "ok" ? users.data : null) : null;
    this.refs.kpis.replaceChildren(...(tiles
      ? tiles.map((t) => h("div", { class: `admin-tile ov-tile ov-tile--${t.tone}` },
        h("div", { class: "admin-tile-icon", "aria-hidden": "true" }, t.icon), h("div", { class: "admin-tile-value" }, fmtValue(t.value)),
        h("div", { class: "admin-tile-label" }, t.label), t.hint ? h("div", { class: "ov-tile-hint" }, t.hint) : null))
      : [stats.status === "loading" ? this.loadingBlock("platform KPIs") : this.errorBlock("platform KPIs", stats.error, ["stats"])]));
  }

  renderRatios() {
    const box = this.refs.ratios, stats = this.state("stats");
    box.replaceChildren(this.head("ov-ratio-title", "📊 Platform activity", "Derived from the platform totals above."));
    if (stats.status === "loading") return box.append(this.loadingBlock("platform activity"));
    if (stats.status === "error") return box.append(this.errorBlock("platform activity", stats.error, ["stats"]));
    box.append(h("div", { class: "ov-ratios" }, ...platformRatios(stats.data).map((r) =>
      h("div", { class: "ov-ratio" }, h("div", { class: "ov-ratio-label" }, r.label), h("div", { class: "ov-ratio-value" }, fmtValue(r.value))))));
  }

  // ── Quick actions ─────────────────────────────────────────────────────────────
  renderQuick() {
    this.refs.quick.replaceChildren(
      this.head("ov-quick-title", "⚡ Quick actions", "Jump straight to common administrative tasks."),
      h("div", { class: "ov-quick" }, ...QUICK_ACTIONS.map((a) =>
        h("a", { class: "ov-qa", href: toHref(a.path) }, h("span", { class: "ov-qa-icon", "aria-hidden": "true" }, a.icon), a.label))));
  }

  // ── Accounts and security ─────────────────────────────────────────────────────
  renderAccounts() {
    const box = this.refs.accounts, users = this.state("users"), banned = this.state("banned"), log = this.state("log");
    box.replaceChildren(this.head("ov-acct-title", "👥 Accounts & security", "Account status across the platform.", link(ROUTES.users, "Manage →", "ov-open")));
    if (users.status === "loading") return box.append(this.loadingBlock("accounts"));
    if (users.status === "error") return box.append(this.errorBlock("accounts", users.error, ["users", "banned", "log"]));
    const b = accountBreakdown(users.data, banned.status === "ok" ? banned.data : [], log.status === "ok" ? log.data : []);
    const bannedKnown = banned.status === "ok", logKnown = log.status === "ok";
    const tile = (value, label, tone) => h("div", { class: `ov-stat ov-stat--${tone}` }, h("div", { class: "ov-stat-value" }, fmtValue(value)), h("div", { class: "ov-stat-label" }, label));
    box.append(
      h("div", { class: "ov-stats" },
        tile(bannedKnown ? b.active : null, "Active", "green"), tile(bannedKnown ? b.suspended : null, "Suspended", "orange"),
        tile(bannedKnown ? b.banned : null, "Banned", "red"), tile(b.flagged, "Flagged", "amber")),
      h("dl", { class: "ov-facts" },
        h("div", null, h("dt", null, "Banned emails"), h("dd", null, fmtValue(bannedKnown ? b.bannedEmails : null))),
        h("div", null, h("dt", null, "Deleted (in recent log)"), h("dd", null, fmtValue(logKnown ? b.deletedInLog : null)))),
      banned.status === "error" ? h("p", { class: "ov-note", role: "status" }, "Banned emails could not be loaded, so banned and suspended counts are hidden. ", h("button", { type: "button", class: "ov-linkbtn", onclick: () => this.load("banned") }, "Retry")) : null,
      banned.status === "loading" ? this.loadingBlock("banned emails") : null);
    if (bannedKnown) {
      const recent = recentBanned(banned.data);
      box.append(h("h4", { class: "ov-subhead" }, "Recently banned emails"),
        recent.length
          ? h("ul", { class: "ov-banned" }, ...recent.map((r) => h("li", null, h("span", { class: "ov-mono" }, r.email), r.when ? h("span", { class: "ov-item-when" }, fmtDate(r.when)) : null)))
          : h("p", { class: "ov-ok" }, "✓ No banned emails."),
        link(ROUTES.bannedEmails, "Manage banned emails →", "ov-open ov-open--block"));
    }
  }

  // ── Recent admin actions ──────────────────────────────────────────────────────
  renderActivity() {
    const box = this.refs.activity, log = this.state("log");
    box.replaceChildren(this.head("ov-act-title", "🕐 Recent admin actions", "Latest actions taken by administrators.", link(ROUTES.activityLog, "Full log →", "ov-open")));
    if (log.status === "loading") return box.append(this.loadingBlock("recent actions"));
    if (log.status === "error") return box.append(this.errorBlock("recent actions", log.error, ["log"]));
    const entries = recentActivity(log.data);
    if (!entries.length) return box.append(h("p", { class: "ov-state" }, "No admin actions recorded yet."));
    box.append(h("ul", { class: "ov-activity" }, ...entries.map((e) => {
      const p = activityPresentation(e.type);
      return h("li", { class: "ov-act" },
        h("span", { class: `ov-act-icon ov-tone--${p.tone}`, "aria-hidden": "true" }, p.icon),
        h("span", { class: "ov-act-body" }, h("span", { class: "ov-act-title" }, e.message), e.detail ? h("span", { class: "ov-act-sub" }, e.detail) : null),
        e.when ? h("time", { class: "ov-item-when", datetime: e.when.toISOString() }, fmtDateTime(e.when)) : null);
    })));
  }

  // ── Charts ────────────────────────────────────────────────────────────────────
  destroyCharts(prefix) {
    for (const [key, chart] of [...this.charts]) {
      if (prefix && !key.startsWith(prefix)) continue;
      try { chart.destroy(); } catch { /* chart already gone */ }
      this.charts.delete(key);
    }
  }

  chartBlock(key, title, build, fallback) {
    const wrap = h("div", { class: "ov-chart" }, h("div", { class: "ov-chart-title" }, title));
    const ChartCtor = typeof window !== "undefined" ? window.Chart : undefined;
    if (typeof ChartCtor !== "function") { wrap.append(fallback()); return wrap; }
    const canvas = h("canvas", { role: "img", "aria-label": `${title} chart` });
    wrap.append(h("div", { class: "ov-chart-canvas" }, canvas));
    queueMicrotask(() => {
      if (this.destroyed || !canvas.isConnected) return;
      try { this.charts.set(key, new ChartCtor(canvas.getContext("2d"), build())); } catch (e) { wrap.append(fallback()); }
    });
    return wrap;
  }

  countList(rows) {
    return h("ul", { class: "ov-countlist" }, ...rows.map(([label, n]) => h("li", null, h("span", null, label), h("strong", null, String(n)))));
  }

  renderCategories() {
    const box = this.refs.categories, src = this.state("categories");
    this.destroyCharts("cat");
    const rangeBar = h("div", { class: "range-filter", role: "group", "aria-label": "Date range" }, ...CATEGORY_RANGES.map((r) =>
      h("button", { type: "button", class: `range-btn${r.id === this.range ? " active" : ""}`, "aria-pressed": String(r.id === this.range), onclick: () => this.setRange(r.id) }, r.label)));
    const rangeLabel = (CATEGORY_RANGES.find((r) => r.id === this.range) || {}).label || "";
    const mc = src.status === "ok" ? src.data : null;
    const hint = mc && mc.topCategory && mc.topCategory !== "No materials yet"
      ? [`Top category (${rangeLabel}): `, h("strong", null, mc.topCategory), mc.topSubCategory && mc.topSubCategory !== "No sub-categories yet" ? [" · Top sub-topic: ", h("strong", null, mc.topSubCategory)] : null]
      : "How uploaded materials break down by auto-assigned subject category and sub-topic.";
    box.replaceChildren(h("div", { class: "ov-card-head" },
      h("div", null, h("h3", { class: "admin-card-title", id: "ov-cat-title" }, "🏷 Material categorization"), h("p", { class: "admin-card-hint" }, hint)),
      h("div", { class: "ov-head-tools" }, rangeBar, link(ROUTES.platformReport, "Full report →", "ov-open"))));
    if (src.status === "loading") return box.append(this.loadingBlock("material categories"));
    if (src.status === "error") return box.append(this.errorBlock("material categories", src.error, ["categories"]));
    const cats = (mc.categoryBreakdown || []).filter((c) => c.count > 0);
    const subs = mc.subCategoryBreakdown || [];
    if (!cats.length) return box.append(h("p", { class: "ov-state" }, "No categorized materials for this range yet. Categories are assigned automatically when a student uploads a handout."));
    box.append(h("div", { class: "ov-grid ov-grid--charts" },
      this.chartBlock("cat-bar", "By subject category", () => ({
        type: "bar", data: { labels: cats.map((c) => c.category), datasets: [{ label: "Materials", data: cats.map((c) => c.count), backgroundColor: cats.map((_, i) => CHART_PALETTE[i % CHART_PALETTE.length]), borderRadius: 6 }] },
        options: { indexAxis: "y", responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true, ticks: { precision: 0 } }, y: { ticks: { font: { size: 10 } } } } }
      }), () => this.countList(cats.map((c) => [c.category, c.count]))),
      subs.length
        ? this.chartBlock("cat-sub", "By sub-topic", () => ({
          type: "doughnut", data: { labels: subs.map((s) => s.subCategory), datasets: [{ data: subs.map((s) => s.count), backgroundColor: subs.map((_, i) => CHART_PALETTE[i % CHART_PALETTE.length]), borderWidth: 2, borderColor: "#fff" }] },
          options: { responsive: true, maintainAspectRatio: false, cutout: "55%", plugins: { legend: { position: "bottom", labels: { font: { size: 9 }, boxWidth: 8 } } } }
        }), () => this.countList(subs.map((s) => [s.subCategory, s.count])))
        : h("p", { class: "ov-state" }, "No sub-categories detected for this range yet.")));
  }

  setRange(id) {
    if (id === this.range) return;
    this.range = id;       // local to the Overview; the printable report keeps its own range
    this.load("categories");
  }

  renderQuiz() {
    const box = this.refs.quiz, src = this.state("quiz");
    this.destroyCharts("quiz");
    const history = src.status === "ok" ? src.data : [];
    const avg = averageScore(history);
    box.replaceChildren(h("div", { class: "ov-card-head" },
      h("div", null, h("h3", { class: "admin-card-title", id: "ov-quiz-title" }, "📝 Quiz performance"),
        h("p", { class: "admin-card-hint" }, src.status === "ok" ? `Across all ${plural(history.length, "recorded attempt")} platform-wide.` : "Platform-wide quiz attempts.")),
      avg != null ? h("span", { class: `ov-pill ov-pill--${avg >= 70 ? "green" : avg >= 50 ? "blue" : "red"}` }, `Avg ${avg}%`) : null));
    if (src.status === "loading") return box.append(this.loadingBlock("quiz analytics"));
    if (src.status === "error") return box.append(this.errorBlock("quiz analytics", src.error, ["quiz"]));
    if (!history.length) return box.append(h("p", { class: "ov-state" }, "No quiz attempts recorded yet."));
    const diff = difficultyBuckets(history), score = scoreBuckets(history);
    const diffLabels = Object.keys(diff).filter((k) => diff[k] > 0);
    const diffColors = { Easy: "#10b981", Medium: "#f59e0b", Hard: "#ef4444", Other: "#94a3b8" };
    box.append(
      h("div", { class: "ov-grid ov-grid--charts" },
        this.chartBlock("quiz-diff", "Difficulty mix", () => ({
          type: "doughnut", data: { labels: diffLabels, datasets: [{ data: diffLabels.map((k) => diff[k]), backgroundColor: diffLabels.map((l) => diffColors[l] || "#94a3b8"), borderWidth: 2, borderColor: "#fff" }] },
          options: { responsive: true, maintainAspectRatio: false, cutout: "60%", plugins: { legend: { position: "bottom", labels: { font: { size: 10 }, boxWidth: 8 } } } }
        }), () => this.countList(diffLabels.map((k) => [k, diff[k]]))),
        this.chartBlock("quiz-score", "Score distribution", () => ({
          type: "bar", data: { labels: Object.keys(score), datasets: [{ label: "Attempts", data: Object.values(score), backgroundColor: ["#ef4444", "#f59e0b", "#3b82f6", "#10b981"], borderRadius: 6 }] },
          options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } }
        }), () => this.countList(Object.entries(score)))),
      h("h4", { class: "ov-subhead" }, "Most recent attempts"),
      h("ul", { class: "ov-recent" }, ...history.slice(0, 5).map((a) => {
        const s = Math.round(Number(a.score) || 0);
        return h("li", null, h("span", { class: "ov-recent-topic" }, a.topic || "—"), h("span", { class: `ov-pill ov-pill--${s >= 75 ? "green" : s >= 50 ? "blue" : "red"}` }, `${s}%`), h("span", { class: "ov-item-when" }, a.date || "—"));
      })));
  }

  // ── Topics & content ──────────────────────────────────────────────────────────
  renderTopics() {
    const box = this.refs.topics, topics = this.state("topics"), quiz = this.state("quiz");
    box.replaceChildren(this.head("ov-topics-title", "📚 Topics & content", "Attempt count and average score per topic, platform-wide.", link(ROUTES.materials, "Review →", "ov-open")));
    if (topics.status === "loading") return box.append(this.loadingBlock("topics"));
    if (topics.status === "error") return box.append(this.errorBlock("topics", topics.error, ["topics"]));
    if (!topics.data.length) return box.append(h("p", { class: "ov-state" }, "No topics uploaded yet."));
    const stats = quiz.status === "ok" ? topicStats(quiz.data) : null;
    const rows = topics.data.slice(0, TOPIC_ROWS).map((name, i) => {
      const stat = stats ? stats.get(String(name).trim().toLowerCase()) : undefined;
      const perf = stats ? topicPerformance(stat) : null;
      return h("tr", null, h("td", { class: "ov-num" }, String(i + 1)), h("td", { class: "ov-strong" }, String(name)),
        h("td", null, stats ? String(stat ? stat.attempts : 0) : "—"),
        h("td", null, perf ? h("span", { class: `ov-perf ov-perf--${perf.tone}` }, perf.label) : "—"));
    });
    box.append(
      h("div", { class: "ov-table-scroll", role: "region", "aria-label": "Topics table", tabindex: "0" },
        h("table", { class: "ov-table" },
          h("thead", null, h("tr", null, h("th", { scope: "col" }, "#"), h("th", { scope: "col" }, "Topic"), h("th", { scope: "col" }, "Attempts"), h("th", { scope: "col" }, "Performance"))),
          h("tbody", null, ...rows))),
      topics.data.length > TOPIC_ROWS ? h("p", { class: "ov-more" }, `…and ${topics.data.length - TOPIC_ROWS} more.`) : null,
      quiz.status === "error" ? h("p", { class: "ov-note", role: "status" }, "Quiz data could not be loaded, so attempts and performance are hidden. ", h("button", { type: "button", class: "ov-linkbtn", onclick: () => this.load("quiz") }, "Retry")) : null);
  }
}

let current = null;

// Legacy compatibility guard. admin.js keeps a legacy re-render path (renderCurrentTab() -> renderOverview()) that
// the old screens call after in-place actions. While this view is mounted that path would replace it with the
// legacy markup, so for exactly the lifetime of the view the global is pointed at a refresh of this view instead,
// then restored. admin.js and legacy/overview.js are untouched.
let savedLegacyRenderer = null;
function guardLegacyRenderer() {
  if (typeof window === "undefined" || savedLegacyRenderer) return;
  savedLegacyRenderer = { fn: window.renderOverview };
  window.renderOverview = () => { if (current) current.refreshAll(); };
  savedLegacyRenderer.guard = window.renderOverview;
}
function restoreLegacyRenderer() {
  if (!savedLegacyRenderer || typeof window === "undefined") { savedLegacyRenderer = null; return; }
  if (window.renderOverview === savedLegacyRenderer.guard) {
    if (savedLegacyRenderer.fn === undefined) delete window.renderOverview; else window.renderOverview = savedLegacyRenderer.fn;
  }
  savedLegacyRenderer = null;
}

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Overview view requires a mount element");
    if (current) { current.destroy(); current = null; }
    current = new OverviewView(el, ctx);
    guardLegacyRenderer();
    current.start();
  },
  unmount() {
    if (current) { current.destroy(); current = null; }
    restoreLegacyRenderer();
  }
};
