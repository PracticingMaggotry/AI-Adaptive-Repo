/**
 * Browser checks for the dedicated Platform report and Reliability views (real Chromium via Playwright,
 * mocked /api/** responses, static files served locally; no Spring backend, no authenticated session).
 * Chromium print emulation and page.pdf() are used to exercise the print stylesheet and pagination;
 * a real printer / the browser's print dialog is NOT exercised.
 *
 * Run: node scripts/smoke-report-reliability.mjs   (exit 1 = failure, 2 = precondition unmet)
 */
import http from "node:http";
import path from "node:path";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const staticDir = fileURLToPath(new URL("../src/main/resources/static/", import.meta.url));
const { default: routes } = await import(pathToFileURL(path.join(staticDir, "admin/routes.js")).href);
const reportRoute = routes.find(r => r.path === "/audit/platform-report");
if (!String(reportRoute.load).includes("platform-report.js")) { console.log("NOT RUN: routes.js does not map /audit/platform-report to platform-report.js."); process.exit(2); }

async function loadPlaywright() {
  try { return await import("playwright"); } catch { /* try global */ }
  try { const root = (await import("node:child_process")).execSync("npm root -g").toString().trim(); return createRequire(path.join(root, "x.js"))("playwright"); } catch { return null; }
}
const pw = await loadPlaywright();
if (!pw) { console.log("SKIPPED: the playwright package is not installed. Nothing was verified."); process.exit(1); }

const mime = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const file = path.join(staticDir, decodeURIComponent(url.pathname).replace(/^\/+/, "") || "index.html");
  if (!file.startsWith(staticDir)) { res.writeHead(403).end(); return; }
  // Read BEFORE writing the status line (a failed read after writeHead(200) used to throw ERR_HTTP_HEADERS_SENT and kill the
  // process). /api/* belongs to page.route; one arriving here was still in flight when its context closed.
  if (url.pathname.startsWith("/api/")) { res.writeHead(503, { "content-type": "application/json" }).end('{"success":false,"message":"mock API route is no longer active"}'); return; }
  let body;
  try { body = await readFile(file); } catch { res.writeHead(404).end("not found"); return; }
  res.writeHead(200, { "content-type": mime[path.extname(file)] || "application/octet-stream" }).end(body);
});
await new Promise(r => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await pw.chromium.launch();
const results = [];
const check = async (name, fn) => { try { await fn(); results.push([true, name]); } catch (e) { results.push([false, name, e]); } };

// ── mock data ───────────────────────────────────────────────────────────────────────────────────
function dataset(big) {
  const nUsers = big ? 150 : 4;
  const users = Array.from({ length: nUsers }, (_, i) => ({ id: i + 1, fullName: `User ${i + 1}`, email: `user${i + 1}@example.test`, isAdmin: i === 0, archived: i % 7 === 3, flagged: i % 5 === 4 }));
  const banned = Array.from({ length: big ? 15 : 1 }, (_, i) => ({ email: `bad${i}@example.test`, bannedBy: "admin", reason: "Policy <b>violation</b>", bannedAt: "2026-09-01T10:00:00" }));
  const log = Array.from({ length: big ? 15 : 1 }, (_, i) => ({ type: "delete", message: `Deleted account ${i}`, detail: "User request", performedBy: "admin", time: "2026-09-02T10:00:00" })).concat([{ type: "login", message: "x" }]);
  const topics = Array.from({ length: big ? 40 : 3 }, (_, i) => `Topic ${i + 1}`);
  const cats = (range) => ({ totalMaterials: range === "week" ? 2 : 10, topCategory: range === "week" ? "Physics" : "Mathematics", topSubCategory: "Algebra",
    categoryBreakdown: [{ category: range === "week" ? "Physics" : "Mathematics", count: range === "week" ? 2 : 6 }, { category: "Chemistry", count: range === "week" ? 0 : 4 }, { category: "Empty", count: 0 }],
    subCategoryBreakdown: Array.from({ length: big ? 25 : 2 }, (_, i) => ({ subCategory: `Sub ${i}`, count: 1 })) });
  return { users, banned, log, topics, cats, stats: { totalUsers: nUsers, totalTopics: topics.length, totalQuestions: 321, totalAttempts: 77, totalMaterials: 10 } };
}
function makeState(over = {}) { return { requests: [], errors: [], fail: {}, delay: {}, data: dataset(false), ...over }; }
function mockApi(state) {
  return async (route) => {
    const req = route.request(); const url = new URL(req.url()); const p = url.pathname;
    state.requests.push(`${req.method()} ${p}${url.search}`);
    const json = (body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    const key = p.replace("/api/admin/", "").replace("/api/", "");
    if (state.delay[key]) await new Promise(r => setTimeout(r, state.delay[key]));
    if (state.fail[key]) return json({ success: false, message: "boom" }, state.fail[key]);
    const d = state.data;
    if (p === "/api/me") return json({ name: "Test Admin", isAdmin: true });
    if (p === "/api/admin/overview") return json({ stats: d.stats });
    if (p === "/api/admin/users") return json({ success: true, users: d.users });
    if (p === "/api/admin/topics") return json(d.topics);
    if (p === "/api/admin/material-categories") return json(d.cats(url.searchParams.get("range")));
    if (p === "/api/admin/banned-emails") return json({ bannedEmails: d.banned });
    if (p === "/api/admin/activity-log") return json({ success: true, log: d.log });
    if (p === "/api/admin/participants") return json({ success: true, participants: [] });
    if (p === "/api/admin/reports/pending-count") return json({ count: 0 });
    if (p === "/api/admin/reports") return json({ reports: [] });
    if (p === "/api/admin/deletion-requests") return json({ requests: [] });
    if (p.startsWith("/api/admin/export/")) return route.fulfill({ status: 200, headers: { "content-type": "text/csv" }, body: "a\r\n" });
    return json({});
  };
}
async function open(hash, state = makeState()) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
  await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: base }).catch(() => {});
  const page = await context.newPage();
  page.on("pageerror", e => state.errors.push("pageerror: " + e.message));
  page.on("console", m => { if (m.type() === "error" && !/Failed to load resource/.test(m.text())) state.errors.push("console: " + m.text()); });
  await context.addInitScript(() => {
    window.Chart = class { constructor() {} destroy() {} update() {} };
    window.katex = { render() {} }; window.renderMathInElement = () => {}; window.hljs = { highlightElement() {}, highlightAll() {} };
    window.__printCalls = 0; window.print = () => { window.__printCalls++; };
  });
  await page.route(/^https?:\/\/(cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com)\//, r => r.fulfill({ status: 200, contentType: r.request().url().endsWith(".css") ? "text/css" : "text/javascript", body: "" }));
  await page.route("**/api/**", mockApi(state));
  await page.goto(base + "/admin.html" + hash);
  await page.waitForFunction(() => document.title.endsWith("| Admin Panel | Learning System") && document.getElementById("adminContent").children.length > 0);
  return { page, context, state };
}
const count = (page, sel) => page.locator(sel).count();
const idle = (page, ms = 200) => page.waitForTimeout(ms);
const waitReport = (page) => page.waitForFunction(() => document.querySelector("#printTarget .admin-table") !== null);
const norm = (t) => t.replace(/Generated: .*? · Administrator/, "Generated: <t> · Administrator").replace(/Admin Report — .*? — Confidential/, "Admin Report — <t> — Confidential").replace(/[\s\u00a0]+/g, " ").trim();
const pages = (buf) => (buf.toString("latin1").match(/\/Type\s*\/Page[^s]/g) || []).length;

try {
  // 1. Platform report content, via the legacy #reports alias ─────────────────────────────────
  {
    const { page, state, context } = await open("#reports");
    await waitReport(page);
    await check("alias #reports lands on the dedicated Platform report, rendered once", async () => {
      assert.equal(await page.evaluate(() => location.hash), "#/audit/platform-report");
      assert.equal(await page.locator("#adminPageTitle").innerText(), "Platform report");
      assert.equal(await count(page, "#printTarget"), 1);
      assert.equal(await count(page, "#printExclude"), 1);
      assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), "printTarget", "shell focus target resolves");
    });
    await check("all seven legacy sections, data values and wording are present", async () => {
      const t = norm(await page.locator("#printTarget").innerText());
      for (const s of ["Platform Administration Report", "Confidential — Admin Use Only", "1. Platform Statistics", "2. Material Categorization Breakdown", "3. Security & Moderation Summary", "4. Banned Email Log", "5. Account Deletion Log", "6. Registered Users", "7. Active Topics"]) assert.ok(t.includes(s), s);
      assert.match(t, /Total Registered Users 4 Total Topics 3 Total Questions 321 Total Quiz Attempts 77 Total Uploaded Materials 10/);
      assert.match(t, /Material categorization range: All Time \(universal timeline\)/);
      assert.match(t, /Top category: Mathematics · Top sub-category: Algebra/);
      assert.match(t, /1 Mathematics 6 60% 2 Chemistry 4 40%/, "zero-count category hidden, shares computed");
      assert.ok(!/Empty/.test(t));
      assert.match(t, /Banned Emails 1 bad0@example\.test/);
      assert.match(t, /Deleted Accounts 1 See deletion log below\./);
      assert.match(t, /1\. Topic 1 \| 2\. Topic 2 \| 3\. Topic 3/);
      assert.ok(/Policy <b>violation<\/b>/.test(t), "untrusted text is shown as text, not parsed as HTML");
    });
    await check("legacy parity: text of the legacy renderReports() output equals the dedicated view's report", async () => {
      const mine = norm(await page.locator("#printTarget").innerText());
      await page.evaluate(() => window.renderReports());     // legacy renderer on the same session data
      const legacy = norm(await page.locator("#printTarget").innerText());
      assert.equal(mine, legacy);
    });
    await check("no Study controls, identity-key, handout or CSV-export toolbar content", async () => {
      await page.evaluate(() => { location.hash = "#/audit/activity-log"; }); await idle(page);
      await page.evaluate(() => { location.hash = "#/audit/platform-report"; }); await waitReport(page);
      for (const sel of ["#participantsCard", "#enrollEmails", "#enrollResult", "#participantList", "#ahAssignBtn", 'a[href*="/api/admin/export/"]', 'a[href*="/api/admin/participants"]', ".print-btn[href]"]) assert.equal(await count(page, sel), 0, sel);
      const t = await page.locator("#adminContent").innerText();
      assert.ok(!/Enrol|Create accounts|Identity key|Attempts CSV|Reliability CSV|Review sample/.test(t));
      assert.deepEqual((await page.locator("#adminContent .print-btn").allInnerTexts()).map(s => s.trim()), ["🖨️ Print / Save as PDF", "📋 Copy Report Text"]);
      assert.equal(await count(page, "#printTarget"), 1, "still exactly one report after navigating away and back");
    });
    await check("range filter: refetches only the categories, updates label and tables, no legacy re-render", async () => {
      const before = state.requests.length;
      await page.getByRole("button", { name: "This Week" }).click();
      await page.waitForFunction(() => /This Week \(last 7 days\)/.test(document.getElementById("printTarget").innerText));
      const after = state.requests.slice(before);
      assert.deepEqual(after, ["GET /api/admin/material-categories?range=week"]);
      const t = norm(await page.locator("#printTarget").innerText());
      assert.match(t, /Top category: Physics/); assert.match(t, /1 Physics 2 100%/); assert.ok(!/Chemistry/.test(t));
      assert.equal(await page.locator("#reportsRangeFilter .active").innerText(), "This Week");
      assert.equal(await count(page, "#participantsCard"), 0);
      await page.getByRole("button", { name: "This Month" }).click();
      await page.waitForFunction(() => /This Month \(last 30 days\)/.test(document.getElementById("printTarget").innerText));
    });
    await check("legacy callbacks (setCategoryRange / renderCurrentTab) cannot overwrite the dedicated view", async () => {
      await page.evaluate(() => { window.__marker = document.getElementById("printTarget"); });
      await page.evaluate(async () => { await window.setCategoryRange("all"); window.renderCurrentTab(); });
      await idle(page, 300);
      assert.equal(await page.evaluate(() => document.getElementById("printTarget") === window.__marker), true, "same #printTarget node");
      assert.equal(await count(page, "#participantsCard"), 0);
      assert.equal(await count(page, "#printTarget"), 1);
    });
    await check("Print button calls window.print(); Copy puts the report text on the clipboard", async () => {
      await page.getByRole("button", { name: /Print \/ Save as PDF/ }).click();
      assert.equal(await page.evaluate(() => window.__printCalls), 1);
      await page.getByRole("button", { name: /Copy Report Text/ }).click();
      await page.waitForFunction(() => /Report text copied/.test(document.body.innerText));
      const clip = await page.evaluate(() => navigator.clipboard.readText());
      const expected = await page.evaluate(() => document.getElementById("printTarget").innerText);
      assert.equal(
        clip.replace(/\r\n/g, "\n"),
        expected.replace(/\r\n/g, "\n")
      );
    });
    await check("print stylesheet applies: chrome and toolbar hidden, pagination rules active on the new markup", async () => {
      await page.emulateMedia({ media: "print" });
      const r = await page.evaluate(() => {
        const cs = (s) => getComputedStyle(document.querySelector(s));
        return { toolbar: cs("#printExclude").display, side: cs(".sidebar").display, top: cs(".topbar").display, btn: cs(".print-btn").display,
          card: cs("#printTarget .admin-card").breakInside, thead: cs("#printTarget thead").display, tr: cs("#printTarget tbody tr").breakInside,
          title: cs("#printTarget .admin-card-title").breakAfter, vis: document.getElementById("printTarget").getBoundingClientRect().height > 0 };
      });
      assert.deepEqual(r, { toolbar: "none", side: "none", top: "none", btn: "none", card: "auto", thead: "table-header-group", tr: "avoid", title: "avoid", vis: true });
      await page.emulateMedia({ media: "screen" });
    });
    await check("small report prints; no page errors", async () => {
      await page.emulateMedia({ media: "print" });
      assert.ok(pages(await page.pdf({ format: "A4" })) >= 1);
      await page.emulateMedia({ media: "screen" });
      assert.deepEqual(state.errors, []);
    });
    await context.close();
  }

  // 2. Pagination: same page count as the legacy renderer, and more than one page ───────────────
  {
    const { page, state, context } = await open("#/audit/platform-report", makeState({ data: dataset(true) }));
    await waitReport(page);
    await check("long report paginates automatically (>1 A4 page) with the same page count as the legacy renderer", async () => {
      await page.emulateMedia({ media: "print" });
      const mine = pages(await page.pdf({ format: "A4", margin: { top: "12mm", bottom: "12mm", left: "12mm", right: "12mm" } }));
      await page.emulateMedia({ media: "screen" });
      await page.evaluate(() => window.renderReports());
      await page.emulateMedia({ media: "print" });
      const legacy = pages(await page.pdf({ format: "A4", margin: { top: "12mm", bottom: "12mm", left: "12mm", right: "12mm" } }));
      await page.emulateMedia({ media: "screen" });
      assert.ok(mine > 1, `expected multi-page output, got ${mine}`);
      assert.equal(mine, legacy, `dedicated=${mine} pages, legacy=${legacy} pages`);
    });
    await check("long report: table headers repeat and rows are not split (computed print rules on every table)", async () => {
      await page.evaluate(() => { location.hash = "#/audit/activity-log"; }); await idle(page);
      await page.evaluate(() => { location.hash = "#/audit/platform-report"; }); await waitReport(page);
      await page.emulateMedia({ media: "print" });
      const r = await page.evaluate(() => [...document.querySelectorAll("#printTarget table")].map(t => [getComputedStyle(t.tHead).display, getComputedStyle(t.tBodies[0].rows[0]).breakInside]));
      await page.emulateMedia({ media: "screen" });
      assert.ok(r.length >= 6 && r.every(([a, b]) => a === "table-header-group" && b === "avoid"), JSON.stringify(r));
      assert.deepEqual(state.errors, []);
    });
    await context.close();
  }

  // 3. Failures are shown, not turned into zeros ────────────────────────────────────────────────
  {
    const state = makeState(); state.fail["banned-emails"] = 500;
    const { page, context } = await open("#/audit/platform-report", state);
    await page.waitForSelector("#reportStatus [role=alert]");
    await check("a failed source is named, its sections say 'not available', nothing is faked as 0/None", async () => {
      const t = await page.locator("#adminContent").innerText();
      assert.match(t, /Some report data could not be loaded \(banned emails\)/);
      assert.match(t, /Could not load users, banned emails or the activity log\. This section is not available in this report\./);
      assert.ok(!/Banned Emails\s+0/.test(t));
      assert.match(t, /Total Registered Users\s+4/, "independent sections still render");
    });
    await check("Retry re-requests and recovers", async () => {
      delete state.fail["banned-emails"];
      await page.getByRole("button", { name: "Retry" }).click();
      await page.waitForFunction(() => /Banned Emails\s*1/.test(document.getElementById("printTarget").innerText));
      assert.equal(await count(page, "#reportStatus [role=alert]"), 0);
    });
    await context.close();
  }

  // 4. Slow API: navigation is not blocked and the focus target exists immediately ───────────────
  {
    const state = makeState(); state.delay["overview"] = 1200;
    const { page, context } = await open("#/audit/platform-report", state);
    await check("slow data: #printTarget (focus target) and a loading state exist at once; Print is disabled until loaded", async () => {
      assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), "printTarget");
      assert.match(await page.locator("#printTarget").innerText(), /Loading report/);
      assert.equal(await page.getByRole("button", { name: /Print/ }).isDisabled(), true);
      const t0 = Date.now();
      await page.locator('#adminNav a[data-route="/study/participants"]').click();
      await page.waitForSelector("#enrollEmails");
      assert.ok(Date.now() - t0 < 1000, "navigating away did not wait for the slow request");
    });
    await context.close();
  }

  // 5. Reliability ──────────────────────────────────────────────────────────────────────────────
  {
    const { page, state, context } = await open("#/system/reliability");
    await check("Reliability: dedicated, export-only page with the existing CSV link", async () => {
      assert.equal(await page.locator("#adminPageTitle").innerText(), "Reliability");
      assert.equal(await count(page, 'a[href="/api/admin/export/reliability.csv"]'), 1);
      assert.equal(await page.evaluate(() => document.activeElement.getAttribute("href")), "/api/admin/export/reliability.csv");
      assert.match(await page.locator("#adminRouteNotice").innerText(), /export-only/);
      assert.match(await page.locator("#adminContent").innerText(), /export-only for now/i);
      assert.match(await page.locator("#adminContent").innerText(), /no reliability dashboard, chart or live status/i);
    });
    await check("Reliability: none of the legacy Reports screen, Study controls, other exports, charts or tables", async () => {
      for (const sel of ["#printTarget", "#printExclude", "#participantsCard", "#enrollEmails", "#reportsRangeFilter", "canvas", "table", 'a[href*="/api/admin/export/"]:not([href="/api/admin/export/reliability.csv"])', ".print-btn:not([href])"]) assert.equal(await count(page, sel), 0, sel);
      assert.equal(await count(page, "#adminContent a[href]"), 2, "only the CSV link and the pointer to Study → Data exports");
    });
    await check("Reliability: no API data requests, CSV link downloads from the existing endpoint", async () => {
      // admin.js's own start-up requests (/api/me, overview, users, ...) are not the view's. Mounting adds none.
      await page.evaluate(() => { location.hash = "#/system/configuration"; }); await idle(page);
      const before = state.requests.length;
      await page.evaluate(() => { location.hash = "#/system/reliability"; }); await page.waitForSelector("[data-view=reliability]"); await idle(page, 300);
      assert.deepEqual(state.requests.slice(before), [], "mounting Reliability makes no API requests");
      assert.ok(!state.requests.some(r => /participants|export\//.test(r)), "nothing fetched from study or export endpoints");
      const [req] = await Promise.all([page.waitForRequest(r => r.url().endsWith("/api/admin/export/reliability.csv")), page.evaluate(() => fetch("/api/admin/export/reliability.csv"))]);
      assert.equal(new URL(req.url()).pathname, "/api/admin/export/reliability.csv");
    });
    await check("Reliability: legacy setCategoryRange/renderCurrentTab leave it untouched", async () => {
      await page.evaluate(async () => { await window.setCategoryRange("week"); window.renderCurrentTab(); });
      await idle(page, 300);
      assert.equal(await count(page, "#printTarget"), 0);
      assert.equal(await count(page, 'a[href="/api/admin/export/reliability.csv"]'), 1);
    });
    await check("Reliability: no page errors", async () => assert.deepEqual(state.errors, []));
    await context.close();
  }

  // 6. Neighbours still work ───────────────────────────────────────────────────────────────────
  {
    const { page, state, context } = await open("#/study/participants");
    await page.waitForSelector("#enrollEmails");
    await check("Study views keep their own controls; platform report and reliability do not take them", async () => {
      assert.equal(await count(page, "#participantsCard"), 1);
      await page.locator('#adminNav a[data-route="/study/data-exports"]').click();
      await page.waitForSelector('a[href="/api/admin/export/attempts.csv"]');
      await page.locator('#adminNav a[data-route="/audit/platform-report"]').click(); await waitReport(page);
      assert.equal(await count(page, "#participantsCard"), 0);
      await page.locator('#adminNav a[data-route="/system/reliability"]').click(); await page.waitForSelector("[data-view=reliability]");
      assert.equal(await count(page, "#printTarget"), 0);
      await page.locator('#adminNav a[data-route="/people/users"]').click(); await page.waitForSelector("#userSearchInput");
      await page.locator('#adminNav a[data-route="/overview"]').click(); await idle(page);
      assert.deepEqual(state.errors, []);
    });
    await context.close();
  }
} finally {
  await browser.close();
  server.close();
}

let failed = 0;
for (const [pass, name, error] of results) {
  console.log(`${pass ? "PASS" : "FAIL"}  ${name}`);
  if (!pass) { failed += 1; console.log("      " + String(error && error.message || error).split("\n").join("\n      ")); }
}
console.log(`\n${results.length - failed}/${results.length} browser checks passed (mocked API; Chromium print emulation + PDF page count only; no real printer, no Spring backend, no authenticated session).`);
process.exit(failed ? 1 : 0);
