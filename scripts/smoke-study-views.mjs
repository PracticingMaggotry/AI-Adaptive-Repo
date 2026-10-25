/**
 * Browser smoke test for the three separated Study views, driven through the REAL admin.html shell.
 * This is the acceptance check for the route-registry integration described in INTEGRATION-NOTES.md.
 *
 * It serves src/main/resources/static over a local HTTP server and drives headless Chromium. Only /api/* is
 * mocked (stateful, in-memory); the CDN libraries are stubbed. There is no Spring backend and no real
 * authentication, so this proves routing, rendering, request shapes and view lifecycle, not server authorization.
 *
 * PRECONDITION: admin/routes.js must already map the Study routes to the new modules. If it does not, this script
 * stops with exit code 2 and says so; it never reports a pass for an un-integrated registry.
 *
 * Run: node scripts/smoke-study-views.mjs        (needs the `playwright` package + Chromium; PLAYWRIGHT_BROWSERS_PATH honored)
 */
import http from "node:http";
import path from "node:path";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const staticDir = fileURLToPath(new URL("../src/main/resources/static/", import.meta.url));

// ── precondition: the registry is integrated ────────────────────────────────────────────────────
const { default: routes } = await import(pathToFileURL(path.join(staticDir, "admin/routes.js")).href);
const byPath = Object.fromEntries(routes.map(r => [r.path, r]));
const expectModule = (routePath, file) => String((byPath[routePath] || {}).shellLoad || (byPath[routePath] || {}).load || "").includes(file);
if (!expectModule("/study/participants", "study-participants.js") || !expectModule("/study/data-exports", "study-data-exports.js") || !expectModule("/study/assigned-handouts", "assignedhandouts.js")
  || byPath["/study/participants"].legacyTab === "reports" || byPath["/study/data-exports"].legacyTab === "reports") {
  console.log("NOT RUN: admin/routes.js does not yet map /study/participants and /study/data-exports to the new modules (see INTEGRATION-NOTES.md).");
  process.exit(2);
}

async function loadPlaywright() {
  try { return await import("playwright"); } catch { /* try global */ }
  try {
    const root = (await import("node:child_process")).execSync("npm root -g").toString().trim();
    return createRequire(path.join(root, "x.js"))("playwright");
  } catch { return null; }
}
const pw = await loadPlaywright();
if (!pw) { console.log("SKIPPED: the playwright package is not installed. Nothing was verified."); process.exit(1); }

const mime = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const file = path.join(staticDir, decodeURIComponent(url.pathname).replace(/^\/+/, "") || "index.html");
  if (!file.startsWith(staticDir)) { res.writeHead(403).end(); return; }
  // Read BEFORE writing the status line. writeHead(200) followed by a failed read called writeHead(404) a second time, which throws
  // ERR_HTTP_HEADERS_SENT inside this async handler and kills the whole process. /api/* belongs to page.route; one that arrives
  // here was still in flight when its context closed.
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

// ── stateful mock API ───────────────────────────────────────────────────────────────────────────
function makeState() {
  return {
    requests: [], bodies: [], errors: [],
    participants: [{ code: "P001", email: "p001@study.invalid", consented: true }, { code: "P002", email: "learner@example.test", consented: false }],
    handouts: [{ id: 7, participantCode: "P001", studentEmail: "p001@study.invalid", topic: "Data Structures", filename: "ds.pdf", sizeBytes: 5000, assignedAt: "2026-10-01T10:00:00", status: "PENDING" },
               { id: 8, participantCode: "P002", studentEmail: "learner@example.test", topic: "Algorithms", filename: "alg.docx", sizeBytes: 100, assignedAt: "2026-10-02T10:00:00", status: "PROCESSED" }],
    failParticipants: false
  };
}
function mockApi(state) {
  return async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const method = req.method();
    state.requests.push(`${method} ${p}${url.search}`);
    if (method !== "GET") state.bodies.push({ key: `${method} ${p}${url.search}`, type: req.headers()["content-type"] || "", body: req.postData() || "" });
    const json = (body, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (p === "/api/me") return json({ name: "Test Admin", isAdmin: true });
    if (p === "/api/admin/participants" && method === "GET") return state.failParticipants ? json({ success: false }, 403) : json({ success: true, participants: state.participants });
    if (p === "/api/admin/participants" && method === "POST") { const { emails } = JSON.parse(req.postData()); state.participants.push({ code: "P00" + (state.participants.length + 1), email: emails[0], consented: false }); return json({ success: true, assigned: emails.length, skipped: ["nobody@x.test (not registered)"] }); }
    if (p === "/api/admin/participants" && method === "DELETE") { const n = state.participants.length; state.participants = []; return json({ success: true, deleted: n }); }
    if (p === "/api/admin/participants/create-accounts") return json({ success: true, accounts: [{ code: "P010", email: "p010@study.invalid", password: "abc123XYZ9" }, { code: "P011", email: "p011@study.invalid", password: "k2m4n6p8Q3" }] });
    if (p === "/api/admin/assigned-handouts" && method === "GET") return json({ success: true, handouts: state.handouts });
    if (p === "/api/admin/assigned-handouts" && method === "POST") { state.handouts.push({ id: 9, participantCode: "P001", studentEmail: "p001@study.invalid", topic: "Graphs", filename: "graphs.txt", sizeBytes: 12, assignedAt: "2026-10-22T10:00:00", status: "PENDING" }); return json({ success: true, message: "Assigned to 1 participant(s). Nothing has been sent to Anthropic yet." }); }
    if (/^\/api\/admin\/assigned-handouts\/\d+\/reset$/.test(p)) { const h = state.handouts.find(x => `/api/admin/assigned-handouts/${x.id}/reset` === p); if (h) h.status = "PENDING"; return json({ success: true }); }
    if (/^\/api\/admin\/assigned-handouts\/\d+$/.test(p) && method === "DELETE") { state.handouts = state.handouts.filter(x => `/api/admin/assigned-handouts/${x.id}` !== p); return json({ success: true }); }
    if (p.startsWith("/api/admin/export/")) return route.fulfill({ status: 200, headers: { "content-type": "text/csv", "content-disposition": `attachment; filename="${p.split("/").pop()}"` }, body: "a,b\r\n1,2\r\n" });
    if (p === "/api/admin/activity-log") return json({ success: true, log: [] });
    if (p === "/api/admin/overview") return json({ stats: { totalUsers: 3, totalTopics: 2, totalQuestions: 40, totalAttempts: 9, totalMaterials: 5 } });
    if (p === "/api/admin/users") return json({ success: true, users: [] });
    if (p === "/api/admin/topics") return json([]);
    if (p === "/api/admin/material-categories") return json({ categoryBreakdown: [], subCategoryBreakdown: [] });
    if (p === "/api/admin/banned-emails") return json({ bannedEmails: [] });
    if (p === "/api/admin/reports/pending-count") return json({ count: 0 });
    if (p === "/api/admin/deletion-requests") return json({ requests: [] });
    return json({});
  };
}
async function open(hash, mutate) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, acceptDownloads: true });
  const page = await context.newPage();
  const state = makeState();
  if (mutate) mutate(state);
  page.on("pageerror", e => state.errors.push("pageerror: " + e.message));
  page.on("console", m => { if (m.type() === "error" && !/Failed to load resource/.test(m.text())) state.errors.push("console: " + m.text()); });
  await context.addInitScript(() => {
    window.Chart = class { constructor() {} destroy() {} update() {} };
    window.katex = { render() {} }; window.renderMathInElement = () => {}; window.hljs = { highlightElement() {}, highlightAll() {} };
  });
  await page.route(/^https?:\/\/(cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com)\//, r => r.fulfill({ status: 200, contentType: r.request().url().endsWith(".css") ? "text/css" : "text/javascript", body: "" }));
  await page.route("**/api/**", mockApi(state));
  await page.goto(base + "/admin.html" + hash);
  await page.waitForFunction(() => document.title.endsWith("| Admin Panel | Learning System") && document.getElementById("adminContent").children.length > 0);
  return { page, context, state };
}
const idle = (page, ms = 250) => page.waitForTimeout(ms);
const count = (page, sel) => page.locator(sel).count();
const goto = async (page, hash) => { await page.evaluate(h => { location.hash = h; }, hash); await idle(page, 150); };

try {
  // 1. Participants ─────────────────────────────────────────────────────────────────────────────
  {
    const { page, state, context } = await open("#/study/participants");
    await page.waitForSelector("#participantsCard tbody tr");
    await check("participants: own view, not the Reports screen", async () => {
      assert.equal(await count(page, "#participantsCard"), 1);
      assert.equal(await count(page, "#printTarget"), 0, "no platform report content");
      assert.equal(await page.locator("#adminPageTitle").innerText(), "Participants");
      // Phase 5: the route has no after.focus (test-admin-shell requires that), and the shell never moves focus on the initial load.
      assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), "", "initial load does not steal focus");
    });
    await check("participants: list shows codes and consent, never e-mail addresses", async () => {
      const t = await page.locator("#participantsCard").innerText();
      assert.ok(/P001/.test(t) && /P002/.test(t) && /✓ Consented/.test(t) && /✗ Not yet/.test(t));
      assert.ok(!/learner@example\.test|p001@study\.invalid/.test(t));
    });
    await check("participants: enrol sends the legacy body and reloads the list", async () => {
      await page.fill("#enrollEmails", "new1@x.test, new2@x.test");
      await page.getByRole("button", { name: /Enrol/ }).click();
      await page.waitForFunction(() => /Enrolled 2\./.test(document.getElementById("enrollResult").textContent));
      const post = state.bodies.find(b => b.key === "POST /api/admin/participants");
      assert.deepEqual(JSON.parse(post.body), { emails: ["new1@x.test", "new2@x.test"] });
      await page.waitForFunction(() => document.querySelectorAll("#participantsCard tbody tr").length === 3);
    });
    await check("participants: create accounts downloads the legacy CSV", async () => {
      await page.fill("#createAccountsCount", "2");
      const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: /Create accounts/ }).click()]);
      assert.equal(download.suggestedFilename(), "study_accounts.csv");
      const csv = await readFile(await download.path(), "utf8");
      assert.equal(csv, "participant_code,email,password\r\nP010,p010@study.invalid,abc123XYZ9\r\nP011,p011@study.invalid,k2m4n6p8Q3");
      assert.deepEqual(JSON.parse(state.bodies.find(b => b.key === "POST /api/admin/participants/create-accounts").body), { count: 2 });
    });
    await check("participants: identity key link keeps the existing endpoint", async () => {
      assert.equal(await page.locator('a[href="/api/admin/participants/mapping.csv"]').count(), 1);
    });
    await check("participants: cancelling the delete confirmation deletes nothing; typing DELETE deletes", async () => {
      await page.getByRole("button", { name: /Delete identity key/ }).click();
      await page.locator("dialog[open]").waitFor();
      await page.getByRole("button", { name: "Cancel" }).click();
      await idle(page);
      assert.ok(!state.requests.some(r => r.startsWith("DELETE ")));
      await page.getByRole("button", { name: /Delete identity key/ }).click();
      await page.locator("dialog[open] input[type=text]").fill("DELETE");
      await page.getByRole("button", { name: "Confirm destructive action" }).click();
      await page.waitForFunction(() => /Identity key deleted/.test(document.getElementById("participantsCard").textContent));
      assert.ok(state.requests.includes("DELETE /api/admin/participants?confirm=DELETE"));
      await page.waitForSelector("text=No participants enrolled yet.");
    });
    await check("participants: no page errors", async () => assert.deepEqual(state.errors, []));
    await context.close();
  }

  // 2. Assigned handouts (also via the old #assignedhandouts hash) ─────────────────────────────────
  {
    const { page, state, context } = await open("#assignedhandouts");
    await page.waitForSelector("#ahList tbody tr");
    await check("handouts: old hash lands on the new route and renders the new view once", async () => {
      assert.equal(await page.evaluate(() => location.hash), "#/study/assigned-handouts");
      assert.equal(await count(page, "#ahTopic"), 1);
      assert.match(await page.locator("#ahList").innerText(), /Data Structures/);
      assert.match(await page.locator("#adminContent").innerText(), /2 assigned · 1 pending · 1 processed/);
    });
    await check("handouts: upload sends multipart with repeated emails, refreshes list and activity-log cache", async () => {
      await page.fill("#ahTopic", "Graphs");
      await page.setInputFiles("#ahFile", { name: "graphs.txt", mimeType: "text/plain", buffer: Buffer.from("hello") });
      await page.locator(".ah-pick").first().check();
      const before = state.requests.filter(r => r === "GET /api/admin/activity-log").length;
      await page.click("#ahAssignBtn");
      await page.waitForFunction(() => /Nothing has been sent to Anthropic yet/.test(document.getElementById("ahStatus").textContent));
      const post = state.bodies.find(b => b.key === "POST /api/admin/assigned-handouts");
      assert.match(post.type, /^multipart\/form-data; boundary=/, "browser sets the multipart boundary (no JSON content type)");
      assert.match(post.body, /name="topic"\r\n\r\nGraphs/);
      assert.match(post.body, /name="emails"\r\n\r\np001@study\.invalid/);
      assert.match(post.body, /filename="graphs\.txt"/);
      await page.waitForFunction(() => document.querySelectorAll("#ahList tbody tr").length === 3);
      await idle(page);
      assert.ok(state.requests.filter(r => r === "GET /api/admin/activity-log").length > before, "activity-log cache refreshed");
    });
    await check("handouts: reset and delete use the legacy endpoints", async () => {
      await page.locator('[data-ah="reset"]').first().click();
      await page.waitForFunction(() => !document.querySelector('[data-ah="reset"]'));
      assert.ok(state.requests.includes("POST /api/admin/assigned-handouts/8/reset"));
      await page.locator('[data-ah="delete"][data-id="7"]').click();
      await page.locator("dialog[open]").waitFor();
      await page.getByRole("button", { name: "Confirm destructive action" }).click();
      await page.waitForFunction(() => document.querySelectorAll("#ahList tbody tr").length === 2);
      assert.ok(state.requests.includes("DELETE /api/admin/assigned-handouts/7"));
    });
    await check("handouts: no page errors", async () => assert.deepEqual(state.errors, []));
    await context.close();
  }

  // 3. Data exports ─────────────────────────────────────────────────────────────────────────────
  {
    const { page, state, context } = await open("#/study/data-exports");
    await page.waitForSelector("#printExclude a.print-btn");
    await check("exports: own view with the seven legacy links", async () => {
      assert.equal(await count(page, "#printExclude"), 1);
      assert.equal(await count(page, "#printTarget"), 0);
      const hrefs = await page.$$eval("#printExclude a.print-btn", as => as.map(a => a.getAttribute("href")).sort());
      assert.deepEqual(hrefs, ["/api/admin/export/attempt_questions.csv", "/api/admin/export/attempts.csv", "/api/admin/export/question_exposures.csv", "/api/admin/export/reliability.csv",
        "/api/admin/export/review_questions.csv?perHandout=10", "/api/admin/export/review_sources.txt?perHandout=10", "/api/admin/export/sus.csv"]);
      assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), "", "initial load does not steal focus");
    });
    await check("exports: clicking a link requests the exact legacy URL", async () => {
      const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: "⬇ Review sample CSV" }).click()]);
      assert.equal(download.suggestedFilename(), "review_questions.csv");
      assert.ok(state.requests.includes("GET /api/admin/export/review_questions.csv?perHandout=10"));
    });
    await check("exports: shows enrolment coverage", async () => {
      assert.match(await page.locator("#exportCoverage").innerText(), /2 participant\(s\) enrolled · 1 consented/);
    });
    await check("exports: no page errors", async () => assert.deepEqual(state.errors, []));
    await context.close();
  }

  // 4. Error state through the shell ─────────────────────────────────────────────────────────────
  {
    const { page, context } = await open("#/study/participants", s => { s.failParticipants = true; });
    await check("participants: a 403 shows an inline error with Retry, not an empty list", async () => {
      await page.waitForSelector("#participantsCard [role=alert]");
      const t = await page.locator("#participantsCard").innerText();
      assert.match(t, /Could not load participants: Admin access is required \(HTTP 403\)/);
      assert.ok(!/No participants enrolled yet/.test(t));
      assert.equal(await page.getByRole("button", { name: "Retry" }).count(), 1);
    });
    await context.close();
  }

  // 5. Navigation hygiene ────────────────────────────────────────────────────────────────────────
  {
    const { page, state, context } = await open("#/study/participants");
    await page.waitForSelector("#participantsCard tbody tr");
    await check("navigation: sidebar, Back and Forward leave exactly one Study view and no stale DOM", async () => {
      const links = { "/study/assigned-handouts": "#ahTopic", "/study/data-exports": "#exportCoverage", "/study/participants": "#enrollEmails" };
      for (const [route, marker] of Object.entries(links)) {
        await page.locator(`#adminNav a[data-route="${route}"]`).click();
        await page.waitForSelector(marker);
        for (const other of ["#participantsCard", "#ahList", "#exportCoverage"]) assert.ok((await count(page, other)) <= 1, "no duplicate ids: " + other);
      }
      await page.goBack(); await idle(page, 150); await page.goBack(); await idle(page, 150);
      assert.equal(await count(page, "#ahList"), 1, "two Backs from the last participants visit land on assigned handouts");
      assert.equal(await count(page, "#enrollEmails") + await count(page, "#exportCoverage"), 0, "the other Study views are gone");
      await page.goForward(); await idle(page, 150);
      assert.equal(await count(page, "#exportCoverage"), 1);
      assert.equal(await count(page, "#ahList"), 0);
    });
    await check("navigation: after leaving, no Study view makes further requests", async () => {
      await page.locator('#adminNav a[data-route="/people/banned-emails"]').click();
      await idle(page, 400);
      const before = state.requests.length;
      await idle(page, 800);
      assert.equal(state.requests.length, before, "no background requests while idle on another page");
    });
    await check("navigation: one click on Enrol after leaving and returning sends exactly one request", async () => {
      await page.locator('#adminNav a[data-route="/study/participants"]').click();
      await page.waitForSelector("#participantsCard tbody tr");
      await page.locator('#adminNav a[data-route="/study/data-exports"]').click();
      await page.locator('#adminNav a[data-route="/study/participants"]').click();
      await page.waitForSelector("#participantsCard tbody tr");
      const before = state.requests.filter(r => r === "POST /api/admin/participants").length;
      await page.fill("#enrollEmails", "again@x.test");
      await page.getByRole("button", { name: /Enrol/ }).click();
      await page.waitForFunction(() => /Enrolled/.test(document.getElementById("enrollResult").textContent));
      assert.equal(state.requests.filter(r => r === "POST /api/admin/participants").length, before + 1);
    });
    await check("dedicated Platform report has its own toolbar and report target and no longer carries the Study participants card", async () => {
      await page.locator('#adminNav a[data-route="/audit/platform-report"]').click();
      await page.waitForSelector("#printTarget");
      assert.equal(await count(page, "#participantsCard"), 0, "enrolment lives only under Study > Participants (test-report-reliability forbids it here)");
      assert.equal(await count(page, "#printExclude"), 1);
      assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), "printTarget", "route after.focus is #printTarget");
    });
    await check("after in-app navigation the shell moves focus to the page heading on the standalone Study views", async () => {
      for (const route of ["/study/participants", "/study/data-exports", "/study/assigned-handouts"]) {
        await page.locator(`#adminNav a[data-route="${route}"]`).click();
        await page.waitForFunction(r => document.getElementById("adminNav").querySelector(`a[data-route="${r}"]`).getAttribute("aria-current") === "page", route);
        await idle(page, 200);
        assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), "adminPageTitle", route);
      }
    });
    await check("legacy range changes (setCategoryRange -> renderCurrentTab) cannot re-render a legacy screen over any dedicated view", async () => {
      for (const [route, sel] of [["/study/participants", "#participantsCard"], ["/study/data-exports", "#exportCoverage"], ["/study/assigned-handouts", "#ahList"], ["/audit/platform-report", "#printTarget"], ["/system/reliability", "a[href='/api/admin/export/reliability.csv']"]]) {
        await page.locator(`#adminNav a[data-route="${route}"]`).click();
        await page.waitForSelector(sel);
        await idle(page, 250);
        const requestsBefore = state.requests.length;
        const marker = await page.evaluate(() => { const c = document.getElementById("adminContent"); c.dataset.phase5Marker = String(Math.random()); const first = c.firstElementChild; if (first) first.setAttribute("data-phase5-first", "1"); return c.dataset.phase5Marker; });
        await page.evaluate(r => window.setCategoryRange(r), String(Math.random()).slice(2, 5));
        await idle(page, 400);
        const after = state.requests.slice(requestsBefore);
        assert.equal(await page.evaluate(() => !!document.querySelector("#adminContent [data-phase5-first]")), true, route + ": the mounted view was replaced");
        assert.deepEqual(after.filter(r => !/material-categories\?range=/.test(r) && !/\/api\/admin\/(activity-log|self-deletion|deletion-requests|reports\/pending|users)/.test(r)), [], route + ": a legacy renderer fetched its own data over the view");
      }
    });
    await check("navigation: no page errors over the whole session", async () => assert.deepEqual(state.errors, []));
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
console.log(`\n${results.length - failed}/${results.length} browser checks passed (mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
