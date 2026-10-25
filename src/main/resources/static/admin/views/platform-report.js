/**
 * Audit > Platform report  (route: /audit/platform-report)
 *
 * Dedicated replacement for the platform-report half of the legacy Reports screen
 * (admin/legacy/reports.js, renderReports). The legacy renderer is NOT called and stays available for
 * compatibility; this view is self-contained and never touches #participantsCard, enrolment, identity-key,
 * assigned-handout or CSV-export controls (those belong to Study).
 *
 * Preserved from the legacy screen (same ids, classes, inline styles, wording and section numbering):
 *   #printExclude   toolbar: Print / Save as PDF, Copy Report Text, material-categorization range filter
 *   #printTarget    the printable report: seven numbered sections (4 and 5 only when they have rows)
 * The print stylesheet lives in admin.html and is keyed on exactly those ids plus .admin-card,
 * .table-scroll, table, thead, tr, .admin-card-title and hr. Their nesting is unchanged, so the
 * automatic multi-page pagination rules (break-inside:auto on the card, repeating table headers, rows and
 * headings kept together) apply as before. Everything that is not report content is inside #printExclude,
 * which that stylesheet hides when printing.
 *
 * Data: see platform-report-model.js. Fetched directly through ctx.api, not via the admin.js cache.
 * Range: local to this view (default All Time, remembered while the page stays open). It no longer shares
 * admin.js's currentCategoryRange, and setCategoryRange()/renderCurrentTab() can no longer overwrite this
 * view because the route has no legacyTab.
 *
 * Contract: default export { mount(el, ctx), unmount() }.
 */
import { h, createLifecycle } from "./view-dom.js";
import {
  CATEGORY_RANGES, rangeLabel, normalizeRange, loadReportData, loadCategories, failedSources,
  statRows, categoryRows, subCategoryRows, deriveSecurity, userStatus
} from "./platform-report-model.js";

const SOURCE_LABELS = Object.freeze({
  stats: "platform statistics", users: "users", topics: "topics", categories: "material categories",
  banned: "banned emails", activity: "activity log"
});

let rememberedRange = "all";
let active = null; // { life, root, abort }

function teardown() {
  if (!active) return;
  const { life, root, abort } = active;
  active = null;
  life.dispose();
  if (abort.current) abort.current.abort();
  root.remove();
}

const MONO = "font-family:monospace;";
const unavailable = (what) => h("div", { class: "empty-state", role: "alert" }, `Could not load ${what}. This section is not available in this report.`);

function table(headers, rows, margin) {
  return h("div", { class: "table-scroll" },
    h("table", { class: "admin-table", style: `margin-bottom:${margin}px;` },
      h("thead", {}, h("tr", {}, headers.map((t) => h("th", {}, t)))),
      h("tbody", {}, rows)));
}
const row = (...cells) => h("tr", {}, cells.map((c) => (c instanceof Node ? c : h("td", {}, c))));
const td = (style, ...kids) => h("td", { style }, ...kids);
const emptyRow = (cols, text) => h("tr", {}, h("td", { colspan: cols, class: "empty-state" }, text));
const strong = (v) => h("strong", {}, String(v));
const title = (...kids) => h("div", { class: "admin-card-title" }, ...kids);

function buildReport(data, range, adminName, now) {
  const failed = new Set(failedSources(data));
  const parts = [];

  parts.push(
    h("div", { style: "display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;margin-bottom:12px;" },
      h("div", {},
        h("div", { style: "font-size:1.2rem;font-weight:900;color:#111827;" }, "📋 Platform Administration Report"),
        h("div", { style: "font-size:.78rem;color:#6b7280;margin-top:2px;" }, `Generated: ${now} · Administrator: ${adminName}`),
        h("div", { style: "font-size:.78rem;color:#6b7280;margin-top:2px;" }, "Material categorization range: ", strong(rangeLabel(range)))),
      h("div", { style: "text-align:right;" },
        h("div", { style: "font-size:.72rem;color:#9ca3af;font-weight:600;text-transform:uppercase;letter-spacing:.04em;" }, "Adaptive Learning System"),
        h("div", { style: "font-size:.72rem;color:#9ca3af;" }, "Confidential — Admin Use Only"))),
    h("hr", { style: "border:none;border-top:2px solid #192a3e;margin-bottom:14px;" }));

  // 1. Platform Statistics
  parts.push(title("1. Platform Statistics"));
  parts.push(failed.has("stats") ? unavailable(SOURCE_LABELS.stats)
    : table(["Metric", "Value"], statRows(data.stats.data).map(([k, v]) => row(k, td("", strong(v)))), 16));

  // 2. Material Categorization Breakdown
  const mc = data.categories.ok ? data.categories.data : null;
  parts.push(title("2. Material Categorization Breakdown ", h("span", { style: "font-size:.72rem;font-weight:600;color:#6b7280;" }, `(${rangeLabel(range)})`)));
  if (!mc) {
    parts.push(unavailable(SOURCE_LABELS.categories));
  } else {
    parts.push(h("div", { style: "font-size:.78rem;color:#6b7280;margin-bottom:8px;font-style:italic;" },
      "Categories are assigned automatically by AI when material is uploaded, based on a fixed set of curricular subject categories. Top category: ",
      strong(mc.topCategory || "—"), " · Top sub-category: ", strong(mc.topSubCategory || "—")));
    const cats = categoryRows(mc);
    parts.push(table(["#", "Category", "Materials", "Share"],
      cats.length ? cats.map((c) => row(String(c.n), c.name, td("", strong(c.count)), `${c.pct}%`)) : [emptyRow(4, "No categorized materials yet.")], 12));
    const subs = subCategoryRows(mc);
    parts.push(table(["#", "Sub-Category", "Materials", "Share"],
      subs.length ? subs.map((c) => row(String(c.n), c.name, td("", strong(c.count)), `${c.pct}%`)) : [emptyRow(4, "No sub-categories detected yet.")], 16));
  }

  // 3-5 need users / banned / activity
  const secOk = !failed.has("users") && !failed.has("banned") && !failed.has("activity");
  const sec = secOk ? deriveSecurity({ users: data.users.data, banned: data.banned.data, activity: data.activity.data }) : null;

  parts.push(title("3. Security & Moderation Summary"));
  if (!sec) {
    parts.push(unavailable("users, banned emails or the activity log"));
  } else {
    parts.push(table(["Security Item", "Count", "Details"], [
      row("Banned Emails", td("", strong(sec.blocked.length)), sec.blocked.map((b) => b.email).join(", ") || "None"),
      row("Suspended Accounts", td("", strong(sec.suspended.length)), sec.suspended.join(", ") || "None"),
      row("Flagged Users", td("", strong(sec.flagged.length)), sec.flagged.join(", ") || "None"),
      row("Deleted Accounts", td("", strong(sec.deleted.length)), sec.deleted.length ? "See deletion log below." : "None")
    ], 16));

    if (sec.blocked.length > 0) {
      parts.push(title("4. Banned Email Log"));
      parts.push(table(["Email", "Banned By", "Reason", "Date Banned"], sec.blocked.map((b) =>
        row(td(MONO, b.email), b.bannedBy || "—", b.reason, b.bannedAt ? new Date(b.bannedAt).toLocaleDateString() : "—")), 16));
    }
    if (sec.deleted.length > 0) {
      parts.push(title("5. Account Deletion Log"));
      parts.push(table(["Action", "Reason", "Performed By", "Date"], sec.deleted.map((e) =>
        row(e.message, e.detail || "—", e.performedBy || "—", e.time ? new Date(e.time).toLocaleDateString() : "—")), 16));
    }
  }

  // 6. Registered Users
  parts.push(title("6. Registered Users"));
  if (!sec) {
    parts.push(unavailable("users, banned emails or the activity log"));
  } else {
    parts.push(table(["#", "Name", "Email", "Role", "Status"], (data.users.data || []).map((u, i) =>
      row(String(i + 1), u.fullName, td(MONO + "font-size:.8rem;", u.email), u.isAdmin ? "Admin" : "Student", userStatus(u, sec))), 16));
  }

  // 7. Active Topics
  parts.push(title("7. Active Topics"));
  parts.push(failed.has("topics") ? unavailable(SOURCE_LABELS.topics)
    : h("div", { style: "font-size:.85rem;color:#374151;line-height:1.8;" },
      data.topics.data.length === 0 ? "No topics found." : data.topics.data.map((t, i) => `${i + 1}. ${t}`).join(" \u00a0|\u00a0 ")));

  parts.push(
    h("hr", { style: "border:none;border-top:1px solid #e2e8f0;margin:16px 0;" }),
    h("div", { style: "font-size:.72rem;color:#9ca3af;text-align:center;" }, `Adaptive Learning System — Admin Report — ${now} — Confidential`));

  return h("div", { class: "admin-card" }, parts);
}

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Platform report view requires a mount element");
    const api = ctx && ctx.api;
    if (!api || typeof api.get !== "function") throw new Error("Platform report needs ctx.api.get.");
    teardown();
    const life = createLifecycle();
    const abort = { current: null };
    const state = { seq: 0, data: null, range: normalizeRange(rememberedRange) };

    const printBtn = h("button", { type: "button", class: "print-btn", disabled: true }, "🖨️ Print / Save as PDF");
    const copyBtn = h("button", { type: "button", class: "print-btn", style: "background:#7c3aed;", disabled: true }, "📋 Copy Report Text");
    const rangeBar = h("div", { class: "range-filter", id: "reportsRangeFilter", role: "group", "aria-label": "Material categorization range" });
    const status = h("div", { id: "reportStatus", "aria-live": "polite", style: "font-size:.82rem;" });
    const pointer = h("div", { style: "font-size:.78rem;color:#6b7280;margin-top:6px;" },
      "Study enrolment and CSV exports are in ",
      h("a", { href: "#/study/participants" }, "Study → Participants"), " and ",
      h("a", { href: "#/study/data-exports" }, "Study → Data exports"), ".");
    const printTarget = h("div", { id: "printTarget" }, h("div", { class: "admin-card" }, h("div", { class: "empty-state", role: "status" }, "Loading report…")));

    const root = h("div", { "data-view": "platform-report" },
      h("div", { id: "printExclude", style: "margin-bottom:4px;" },
        h("div", { style: "display:flex;gap:10px;flex-wrap:wrap;align-items:center;justify-content:space-between;" },
          h("div", { style: "display:flex;gap:10px;flex-wrap:wrap;" }, printBtn, copyBtn), rangeBar),
        pointer, status),
      printTarget);
    el.replaceChildren(root);
    active = { life, root, abort };

    function drawRange() {
      rangeBar.replaceChildren(...CATEGORY_RANGES.map((o) => {
        const b = h("button", { type: "button", class: "range-filter-btn" + (o.key === state.range ? " active" : ""), "aria-pressed": String(o.key === state.range), "data-range": o.key }, o.label);
        life.on(b, "click", () => setRange(o.key));
        return b;
      }));
    }

    function paint() {
      const d = state.data;
      const bad = failedSources(d);
      const adminName = (document.getElementById("topbarName") && document.getElementById("topbarName").textContent) || (ctx.session && ctx.session.user && ctx.session.user.name && ctx.session.user.name.split(" ")[0]) || "Admin";
      printTarget.replaceChildren(buildReport(d, state.range, adminName, new Date().toLocaleString()));
      printBtn.disabled = false; copyBtn.disabled = false;
      status.replaceChildren();
      if (bad.length) {
        const retry = h("button", { type: "button", class: "action-btn btn-view", style: "margin-left:8px;" }, "Retry");
        life.on(retry, "click", () => load());
        status.append(h("div", { role: "alert", style: "margin-top:8px;padding:8px 12px;border-radius:8px;background:#fffbeb;color:#92400e;border:1px solid #fde68a;" },
          "Some report data could not be loaded (" + bad.map((k) => SOURCE_LABELS[k]).join(", ") + "). Affected sections say so instead of showing zeros. Check before printing.", retry));
      }
    }

    async function load() {
      const seq = ++state.seq;
      if (abort.current) abort.current.abort();
      const controller = new AbortController(); abort.current = controller;
      status.replaceChildren(h("div", { role: "status" }, "Loading report data…"));
      const data = await loadReportData(api, state.range, { signal: controller.signal });
      if (!life.alive || seq !== state.seq) return;
      state.data = data; paint();
    }

    async function setRange(key) {
      if (key === state.range || !state.data) return;
      state.range = rememberedRange = key; drawRange();
      const seq = ++state.seq;
      if (abort.current) abort.current.abort();
      const controller = new AbortController(); abort.current = controller;
      const result = await loadCategories(api, key, { signal: controller.signal });
      if (!life.alive || seq !== state.seq) return;
      state.data = { ...state.data, categories: result }; paint();
    }

    life.on(printBtn, "click", () => window.print());
    life.on(copyBtn, "click", async () => {
      const text = printTarget.innerText || "";
      const say = (msg, type) => (ctx.ui && typeof ctx.ui.toast === "function" ? ctx.ui.toast(msg, { type }) : typeof globalThis.showInfoModal === "function" ? globalThis.showInfoModal(msg, type) : undefined);
      try { await navigator.clipboard.writeText(text); say("Report text copied to clipboard.", "success"); }
      catch { say("Copy failed — use Ctrl+A on the report to select manually.", "error"); }
    });

    drawRange();
    load();   // not awaited: the router serialises navigation behind mount(), so a slow API must not block it
  },

  unmount() { teardown(); }
};
