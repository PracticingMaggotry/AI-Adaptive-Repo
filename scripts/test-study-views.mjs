/**
 * Focused tests for the separated Study views (participants, assigned handouts, data exports).
 * Run: node scripts/test-study-views.mjs      (Node 22+)
 *
 * Needs `jsdom`, which is NOT a dependency of this repository. Install it outside the repo and point the test at it:
 *     npm i --prefix /tmp/study-test jsdom
 *     JSDOM_FROM=/tmp/study-test/x.js node scripts/test-study-views.mjs
 * (or `npm i --no-save jsdom` in a scratch checkout). Without jsdom the script prints SKIPPED and exits 1, so a
 * missing dependency can never be mistaken for a pass.
 *
 * What this proves: each view mounts into a bare #adminContent element with a mocked ctx.api, renders its
 * loading / error / empty / data states, sends the same requests as the legacy screen, and unmount() leaves no
 * handlers, requests or DOM behind. It uses the real admin/ui.js for table / empty / toast; only ui.confirm is
 * replaced (jsdom has no <dialog>.showModal).
 * What it does NOT prove: real browser rendering, the real server, or integration with admin/routes.js.
 */
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";

async function loadJsdom() {
  try { return await import("jsdom"); } catch { /* fall through */ }
  const from = process.env.JSDOM_FROM || path.join(process.cwd(), "x.js");
  try { return createRequire(from)("jsdom"); } catch { return null; }
}
const jsdom = await loadJsdom();
if (!jsdom) { console.log("SKIPPED: jsdom is not installed (see the header of this file). Nothing was verified."); process.exit(1); }
const { JSDOM } = jsdom;

const dom = new JSDOM('<!doctype html><html><body><div id="adminContent"></div></body></html>', { url: "http://localhost/admin.html", pretendToBeVisual: true });
const { window } = dom;
for (const name of ["document", "Node", "Element", "HTMLInputElement", "HTMLElement", "FormData", "Blob", "File", "FileReader", "AbortController", "AbortSignal", "Event", "MouseEvent"]) {
  Object.defineProperty(globalThis, name, { value: window[name], configurable: true, writable: true });
}
globalThis.window = window;
const downloads = [];                                   // { download, text }
const readBlob = (blob) => new Promise((resolve, reject) => { const r = new window.FileReader(); r.onload = () => resolve(String(r.result)); r.onerror = reject; r.readAsText(blob); });
const blobs = new Map();
let urlCounter = 0;
window.URL.createObjectURL = (blob) => { const url = "blob:test/" + (++urlCounter); blobs.set(url, blob); return url; };
window.URL.revokeObjectURL = () => {};
globalThis.URL.createObjectURL = window.URL.createObjectURL;
globalThis.URL.revokeObjectURL = window.URL.revokeObjectURL;
window.HTMLAnchorElement.prototype.click = function () { downloads.push({ download: this.download, href: this.href }); };
window.console = console;

const staticRoot = new URL("../src/main/resources/static/admin/", import.meta.url);
const realUi = (await import(new URL("ui.js", staticRoot).href)).default;
const participantsView = (await import(new URL("views/study-participants.js", staticRoot).href)).default;
const handoutsView = (await import(new URL("views/assignedhandouts.js", staticRoot).href)).default;
const exportsModule = await import(new URL("views/study-data-exports.js", staticRoot).href);
const exportsView = exportsModule.default;

let checks = 0;
const ok = (value, message) => { checks += 1; assert.ok(value, message); };
const eq = (actual, expected, message) => { checks += 1; assert.deepEqual(actual, expected, message); };
const tick = (ms = 0) => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, message, timeout = 1500) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (predicate()) { checks += 1; return; } await tick(5); }
  assert.fail("timed out waiting for: " + message);
}

const mountPoint = () => document.getElementById("adminContent");
const text = () => mountPoint().textContent;
const q = (sel) => mountPoint().querySelector(sel);
const qa = (sel) => [...mountPoint().querySelectorAll(sel)];
const clickEl = (node) => node.dispatchEvent(new window.MouseEvent("click", { bubbles: true, cancelable: true }));

/** Fresh ctx per scenario. handlers: { "GET /path": (call) => response | Promise | throws } */
function makeCtx(handlers = {}, confirmAnswers = []) {
  const calls = [];
  const toasts = [];
  const confirms = [];
  const dispatch = async (pathname, options = {}) => {
    const method = (options.method || "GET").toUpperCase();
    const call = { path: pathname, method, body: options.body, signal: options.signal };
    calls.push(call);
    const handler = handlers[method + " " + pathname];
    if (!handler) throw Object.assign(new Error("no mock for " + method + " " + pathname), { status: 404 });
    return typeof handler === "function" ? handler(call) : handler;
  };
  const ui = {
    ...realUi,
    toast: (message, opts) => { toasts.push({ message, type: opts && opts.type }); return null; },
    confirm: async (options) => { confirms.push(options); return confirmAnswers.length ? confirmAnswers.shift() : { confirmed: false, reason: "" }; }
  };
  return { ctx: { params: {}, query: new URLSearchParams(), api: { get: dispatch, request: dispatch }, ui, router: {}, session: {}, badges: {} }, calls, toasts, confirms };
}
const callsTo = (calls, method, pathname) => calls.filter(c => c.method === method && c.path === pathname);
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };

// ── 0. module contract and source hygiene ───────────────────────────────────────────────────────
for (const [name, view] of [["participants", participantsView], ["assigned handouts", handoutsView], ["data exports", exportsView]]) {
  ok(typeof view.mount === "function" && typeof view.unmount === "function", name + " exports mount() and unmount()");
}
for (const file of ["study-common.js", "study-participants.js", "assignedhandouts.js", "study-data-exports.js"]) {
  const raw = await readFile(new URL("views/" + file, staticRoot), "utf8");
  const source = raw.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");   // ignore comments
  ok(!/innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\(|new Function/.test(source), file + " must not build markup from strings");
  ok(!/\bwindow\.(render|load|enroll|create|wipe|ah)\w*|\bcrOpen|showInfoModal|escHtmlGlobal|setModalInlineStatus/.test(source), file + " must not depend on legacy admin.js globals");
  ok(!/\blocalStorage|sessionStorage|setInterval\(/.test(source), file + " must not keep browser storage or interval timers");
}

// ── 1. participants ─────────────────────────────────────────────────────────────────────────────
const PEOPLE = [
  { code: "P001", email: "p001@study.invalid", consented: true },
  { code: "P002", email: "learner@example.test", consented: false }
];
{
  // 1a. loading -> data, consent shown, e-mail addresses not shown, request shape
  const gate = deferred();
  const { ctx, calls } = makeCtx({ "GET /api/admin/participants": () => gate.promise });
  await participantsView.mount(mountPoint(), ctx);
  ok(q("#participantsCard"), "legacy focus/print id #participantsCard is present");
  ok(q("#enrollEmails") && q("#enrollResult") && q("#participantList"), "legacy element ids are kept");
  ok(/Loading participants/.test(text()), "loading state is shown while the list request is pending");
  gate.resolve({ success: true, participants: PEOPLE });
  await until(() => qa("tbody tr").length === 2, "two participant rows");
  ok(/2 enrolled · 1 consented/.test(text()), "summary counts enrolled and consented");
  ok(/✓ Consented/.test(text()) && /✗ Not yet/.test(text()), "consent is shown in words, not colour alone");
  ok(!/learner@example\.test|p001@study\.invalid/.test(text()), "e-mail addresses are not shown in the list (legacy showed codes only)");
  eq(callsTo(calls, "GET", "/api/admin/participants").length, 1, "one list request on mount");
  ok(q('a[href="/api/admin/participants/mapping.csv"]'), "identity key CSV stays a plain link to the existing endpoint");
  await participantsView.unmount();
  ok(!q("#participantsCard"), "unmount removes the view");
}
{
  // 1b. empty, error + retry, unexpected body
  const { ctx } = makeCtx({ "GET /api/admin/participants": { success: true, participants: [] } });
  await participantsView.mount(mountPoint(), ctx);
  await until(() => /No participants enrolled yet/.test(text()), "empty state");
  await participantsView.unmount();

  let attempt = 0;
  const failing = makeCtx({ "GET /api/admin/participants": () => { if (++attempt === 1) throw Object.assign(new Error(""), { status: 403 }); return { success: true, participants: PEOPLE }; } });
  await participantsView.mount(mountPoint(), failing.ctx);
  await until(() => /Could not load participants: Admin access is required \(HTTP 403\)/.test(text()), "error state names the 403");
  clickEl([...qa("button")].find(b => b.textContent === "Retry"));
  await until(() => qa("tbody tr").length === 2, "retry recovers");
  await participantsView.unmount();

  const bare = makeCtx({ "GET /api/admin/participants": () => { throw Object.assign(new Error("Request failed"), { status: 403 }); } });
  await participantsView.mount(mountPoint(), bare.ctx);
  await until(() => /Could not load participants: Admin access is required \(HTTP 403\)/.test(text()), "apiFetch's generic 'Request failed' on a 403 reads as an access error");
  await participantsView.unmount();

  const odd = makeCtx({ "GET /api/admin/participants": "<html>login</html>" });
  await participantsView.mount(mountPoint(), odd.ctx);
  await until(() => /Unexpected response/.test(text()), "a non-JSON 200 is an error, not 'no participants'");
  await participantsView.unmount();
}
{
  // 1c. enrol
  const refreshed = [];
  globalThis.refreshActivityLog = async () => { refreshed.push(1); };
  let enrolled = false;
  const { ctx, calls } = makeCtx({
    "GET /api/admin/participants": () => ({ success: true, participants: enrolled ? PEOPLE : [] }),
    "POST /api/admin/participants": () => { enrolled = true; return { success: true, assigned: 2, skipped: ["x@y.test (not registered)"] }; }
  });
  await participantsView.mount(mountPoint(), ctx);
  await until(() => /No participants enrolled yet/.test(text()), "initial empty");
  const enrol = [...qa("button")].find(b => /Enrol/.test(b.textContent));
  clickEl(enrol);
  await tick(10);
  ok(/Enter at least one email/.test(q("#enrollResult").textContent), "empty input is rejected locally");
  eq(callsTo(calls, "POST", "/api/admin/participants").length, 0, "no request for empty input");
  q("#enrollEmails").value = "a@x.test, b@x.test;\nc@x.test  a@x.test";
  clickEl(enrol);
  await until(() => callsTo(calls, "POST", "/api/admin/participants").length === 1, "enrol request sent");
  eq(JSON.parse(callsTo(calls, "POST", "/api/admin/participants")[0].body), { emails: ["a@x.test", "b@x.test", "c@x.test", "a@x.test"] }, "same parser and body as the legacy screen (server de-duplicates)");
  await until(() => /Enrolled 2\. Skipped: x@y\.test \(not registered\)/.test(q("#enrollResult").textContent), "legacy result text");
  eq(q("#enrollEmails").value, "", "textarea cleared after enrolling");
  await until(() => qa("tbody tr").length === 2, "list reloads after enrolling");
  await until(() => refreshed.length >= 1, "activity-log cache refreshed");
  await participantsView.unmount();
}
{
  // 1d. create accounts: validation, CSV format, download, re-download, survives unmount
  downloads.length = 0;
  const gate = deferred();
  const ACCOUNTS = [{ code: "P003", email: "p003@study.invalid", password: "abc123XYZ9" }, { code: "P004", email: "p004@study.invalid", password: "k2m4n6p8Q3" }];
  const { ctx, calls, toasts } = makeCtx({
    "GET /api/admin/participants": { success: true, participants: [] },
    "POST /api/admin/participants/create-accounts": () => gate.promise
  });
  await participantsView.mount(mountPoint(), ctx);
  const create = [...qa("button")].find(b => /Create accounts/.test(b.textContent));
  q("#createAccountsCount").value = "0"; clickEl(create); await tick(10);
  ok(/whole number from 1 to 50/.test(text()), "count below 1 rejected locally");
  q("#createAccountsCount").value = "51"; clickEl(create); await tick(10);
  eq(callsTo(calls, "POST", "/api/admin/participants/create-accounts").length, 0, "no request for an out-of-range count");
  q("#createAccountsCount").value = "2";
  clickEl(create); clickEl(create);
  await tick(10);
  eq(callsTo(calls, "POST", "/api/admin/participants/create-accounts").length, 1, "double click sends one request");
  ok(create.disabled, "button disabled while creating");
  eq(JSON.parse(callsTo(calls, "POST", "/api/admin/participants/create-accounts")[0].body), { count: 2 }, "legacy request body");
  gate.resolve({ success: true, accounts: ACCOUNTS });
  await until(() => downloads.length === 1, "credentials CSV downloaded");
  eq(downloads[0].download, "study_accounts.csv", "legacy file name");
  const csv = await readBlob(blobs.get(downloads[0].href));
  eq(csv, "participant_code,email,password\r\nP003,p003@study.invalid,abc123XYZ9\r\nP004,p004@study.invalid,k2m4n6p8Q3", "CSV bytes identical to the legacy format");
  const again = [...qa("button")].find(b => /Download credentials again/.test(b.textContent));
  ok(again && !again.hidden, "download-again safety net is offered");
  clickEl(again);
  eq(downloads.length, 2, "download again re-sends the same file");
  await participantsView.unmount();
  ok(!document.body.textContent.includes("abc123XYZ9"), "passwords are not left in the DOM after leaving");

  // response arrives AFTER the admin navigated away: the file must still be delivered
  downloads.length = 0;
  const late = deferred();
  const second = makeCtx({
    "GET /api/admin/participants": { success: true, participants: [] },
    "POST /api/admin/participants/create-accounts": () => late.promise
  });
  await participantsView.mount(mountPoint(), second.ctx);
  clickEl([...qa("button")].find(b => /Create accounts/.test(b.textContent)));
  await tick(10);
  await participantsView.unmount();
  late.resolve({ success: true, accounts: ACCOUNTS });
  await until(() => downloads.length === 1, "passwords are still downloaded after navigating away");
  ok(second.toasts.some(t => /study_accounts\.csv was downloaded/.test(t.message)), "a toast tells the admin where the file went");
  ok(!q("#participantsCard"), "late response does not re-render an unmounted view");
}
{
  // 1e. delete identity key
  const { ctx, calls, confirms } = makeCtx({
    "GET /api/admin/participants": { success: true, participants: PEOPLE },
    "DELETE /api/admin/participants?confirm=DELETE": { success: true, deleted: 2 }
  }, [{ confirmed: false }, { confirmed: true }]);
  await participantsView.mount(mountPoint(), ctx);
  await until(() => qa("tbody tr").length === 2, "list");
  const wipe = [...qa("button")].find(b => /Delete identity key/.test(b.textContent));
  clickEl(wipe);
  await until(() => confirms.length === 1, "first confirm opened");
  await tick(10);
  eq(calls.filter(c => c.method === "DELETE").length, 0, "cancelling the confirmation deletes nothing");
  eq([confirms[0].tier, confirms[0].typeToConfirm], ["destructive", "DELETE"], "destructive tier with type-to-confirm");
  ok(/Download the Identity key CSV first/.test(confirms[0].message), "legacy warning text is kept");
  clickEl(wipe);
  await until(() => calls.filter(c => c.method === "DELETE").length === 1, "DELETE sent after confirmation");
  eq(calls.find(c => c.method === "DELETE").path, "/api/admin/participants?confirm=DELETE", "same endpoint and confirm parameter as the legacy screen");
  await until(() => /Identity key deleted \(2 row\(s\)\)/.test(text()), "success message");
  await participantsView.unmount();
}
{
  // 1f. lifecycle: no duplicate handlers after re-mount, aborts GET on unmount, nothing fires after unmount
  const { ctx, calls } = makeCtx({
    "GET /api/admin/participants": () => new Promise(() => {}),         // never settles
    "POST /api/admin/participants": { success: true, assigned: 1, skipped: [] }
  });
  await participantsView.mount(mountPoint(), ctx);
  await participantsView.mount(mountPoint(), ctx);                       // repeated mount without unmount
  await participantsView.mount(mountPoint(), ctx);
  eq(qa("#participantsCard").length, 1, "repeated mount leaves exactly one view");
  const firstSignal = calls[0].signal;
  q("#enrollEmails").value = "a@x.test";
  clickEl([...qa("button")].find(b => /Enrol/.test(b.textContent)));
  await tick(20);
  eq(callsTo(calls, "POST", "/api/admin/participants").length, 1, "three mounts still produce a single enrol request per click");
  q("#enrollEmails").value = "would-post-if-handler-survived@x.test";   // a live handler would now call the API
  const staleButton = [...qa("button")].find(b => /Enrol/.test(b.textContent));
  await participantsView.unmount();
  ok(firstSignal.aborted, "an earlier in-flight list request is aborted by the next mount");
  ok(calls[calls.length - 1].signal.aborted, "the in-flight list request is aborted on unmount");
  const before = calls.length;
  clickEl(staleButton);
  await tick(20);
  eq(calls.length, before, "a detached button has no live handler after unmount");
  await participantsView.unmount();                                      // unmount twice is harmless
}
{
  // 1g. XSS: server strings are text
  const hostile = '<img src=x onerror="window.__pwned=1">';
  const { ctx } = makeCtx({ "GET /api/admin/participants": { success: true, participants: [{ code: hostile, email: hostile, consented: true }] } });
  await participantsView.mount(mountPoint(), ctx);
  await until(() => qa("tbody tr").length === 1, "hostile row");
  ok(!q("img") && !window.__pwned && text().includes(hostile), "participant code is rendered as text");
  await participantsView.unmount();
}

// ── 2. assigned handouts ────────────────────────────────────────────────────────────────────────
const HANDOUTS = [
  { id: 7, participantCode: "P001", studentEmail: "p001@study.invalid", topic: "Data Structures", filename: "ds.pdf", sizeBytes: 5000, assignedAt: "2026-10-01T10:00:00", status: "PENDING" },
  { id: 8, participantCode: "P002", studentEmail: "learner@example.test", topic: "Algorithms", filename: "alg.docx", sizeBytes: 100, assignedAt: null, status: "PROCESSED" }
];
const handoutHandlers = (extra = {}) => ({
  "GET /api/admin/participants": { success: true, participants: PEOPLE },
  "GET /api/admin/assigned-handouts": { success: true, handouts: HANDOUTS },
  ...extra
});
{
  // 2a. renders picker + list + status
  const { ctx, calls } = makeCtx(handoutHandlers());
  await handoutsView.mount(mountPoint(), ctx);
  ok(q("#ahTopic") && q("#ahFile") && q("#ahParticipants") && q("#ahAssignBtn") && q("#ahStatus") && q("#ahList"), "legacy element ids are kept");
  await until(() => qa(".ah-pick").length === 2 && qa("#ahList tbody tr").length === 2, "picker and list rendered");
  ok(/2 assigned · 1 pending · 1 processed/.test(text()), "status summary derived from the existing list");
  const rows = qa("#ahList tbody tr");
  ok(/Pending/.test(rows[0].textContent) && !rows[0].querySelector('[data-ah="reset"]'), "pending row has no Reset");
  ok(/Processed/.test(rows[1].textContent) && rows[1].querySelector('[data-ah="reset"]'), "processed row offers Reset");
  ok(/5 KB/.test(rows[0].textContent) && /1 KB/.test(rows[1].textContent) && /—/.test(rows[1].textContent), "size rounding and missing date match the legacy output");
  eq(callsTo(calls, "GET", "/api/admin/assigned-handouts").length, 1, "one list request");
  eq(q("input#ahFile").accept, ".txt,.pdf,.doc,.docx,.csv", "accepted file types unchanged");
  await handoutsView.unmount();
  ok(!q("#ahTopic"), "unmount removes the view");
}
{
  // 2b. empty / error states
  const emptyCtx = makeCtx({ "GET /api/admin/participants": { success: true, participants: [] }, "GET /api/admin/assigned-handouts": { success: true, handouts: [] } });
  await handoutsView.mount(mountPoint(), emptyCtx.ctx);
  await until(() => /Nothing assigned yet/.test(text()) && /No participants enrolled yet/.test(text()), "empty states");
  ok(q('a[href="#/study/participants"]'), "empty picker points at the new Participants route");
  await handoutsView.unmount();

  let n = 0;
  const failing = makeCtx(handoutHandlers({ "GET /api/admin/assigned-handouts": () => { if (++n === 1) throw new Error("boom"); return { success: true, handouts: HANDOUTS }; } }));
  await handoutsView.mount(mountPoint(), failing.ctx);
  await until(() => /Could not load assignments: boom/.test(text()), "list error");
  clickEl([...qa("#ahList button")].find(b => b.textContent === "Retry"));
  await until(() => qa("#ahList tbody tr").length === 2, "retry recovers");
  await handoutsView.unmount();
}
{
  // 2c. assign: validation, FormData, reset, double click, refresh
  const refreshed = [];
  globalThis.refreshActivityLog = async () => { refreshed.push(1); };
  const gate = deferred();
  const { ctx, calls } = makeCtx(handoutHandlers({ "POST /api/admin/assigned-handouts": () => gate.promise }));
  await handoutsView.mount(mountPoint(), ctx);
  await until(() => qa(".ah-pick").length === 2, "picker");
  const assign = q("#ahAssignBtn");
  clickEl(assign); await tick(5);
  eq(q("#ahStatus").textContent, "Please enter a topic name.", "topic validation message");
  q("#ahTopic").value = "  Graphs  "; clickEl(assign); await tick(5);
  eq(q("#ahStatus").textContent, "Please choose a handout file.", "file validation message");
  const file = new window.File(["hello"], "graphs.txt", { type: "text/plain" });
  Object.defineProperty(q("#ahFile"), "files", { value: [file], configurable: true });
  clickEl(assign); await tick(5);
  eq(q("#ahStatus").textContent, "Pick at least one participant.", "participant validation message");
  eq(calls.filter(c => c.method === "POST").length, 0, "no request until valid");

  clickEl(q("#ahPickAll"));
  ok(qa(".ah-pick").every(b => b.checked), "select all checks every participant");
  qa(".ah-pick")[0].checked = false; qa(".ah-pick")[0].dispatchEvent(new window.Event("change", { bubbles: true }));
  ok(q("#ahPickAll").indeterminate === true, "select-all shows a partial selection");
  qa(".ah-pick")[0].checked = true; qa(".ah-pick")[0].dispatchEvent(new window.Event("change", { bubbles: true }));
  clickEl(assign); clickEl(assign);
  await tick(10);
  const posts = calls.filter(c => c.method === "POST");
  eq(posts.length, 1, "double click sends one upload");
  ok(assign.disabled && assign.textContent === "Uploading…", "button shows the uploading state");
  ok(posts[0].body instanceof window.FormData, "multipart FormData is sent (apiFetch drops the JSON content type for it)");
  eq(posts[0].body.get("topic"), "Graphs", "topic is trimmed like the legacy code");
  eq(posts[0].body.get("file").name, "graphs.txt", "file field");
  eq(posts[0].body.getAll("emails"), ["p001@study.invalid", "learner@example.test"], "emails are repeated form fields");
  gate.resolve({ success: true, message: "Assigned to 2 participant(s). Nothing has been sent to Anthropic yet.", skipped: ["z@x.test (admin)"] });
  await until(() => /Assigned to 2 participant\(s\)\. Nothing has been sent to Anthropic yet\. Skipped: z@x\.test \(admin\)/.test(q("#ahStatus").textContent), "legacy success message");
  eq([q("#ahTopic").value, q("#ahFile").value, qa(".ah-pick:checked").length], ["", "", 0], "form reset after success");
  ok(!assign.disabled && assign.textContent === "📤 Upload & Assign", "button restored");
  await until(() => callsTo(calls, "GET", "/api/admin/assigned-handouts").length === 2, "list reloaded");
  await until(() => refreshed.length === 1, "activity-log cache refreshed");
  await handoutsView.unmount();
}
{
  // 2d. reset / delete (+ re-mount does not double handlers)
  const { ctx, calls, confirms } = makeCtx(handoutHandlers({
    "POST /api/admin/assigned-handouts/8/reset": { success: true },
    "DELETE /api/admin/assigned-handouts/7": { success: true }
  }), [{ confirmed: false }, { confirmed: true }]);
  await handoutsView.mount(mountPoint(), ctx);
  await handoutsView.mount(mountPoint(), ctx);                            // remount: must not stack listeners
  await until(() => qa("#ahList tbody tr").length === 2, "rows");
  clickEl(q('[data-ah="reset"]'));
  await until(() => callsTo(calls, "POST", "/api/admin/assigned-handouts/8/reset").length >= 1, "reset sent");
  await tick(20);
  eq(callsTo(calls, "POST", "/api/admin/assigned-handouts/8/reset").length, 1, "one reset per click even after two mounts");
  await until(() => qa("#ahList tbody tr").length === 2, "list reloaded after reset");
  clickEl(q('[data-ah="delete"][data-id="7"]'));
  await until(() => confirms.length === 1, "delete confirmation");
  eq(confirms[0].title, 'Remove assigned handout: "Data Structures"', "legacy confirmation title");
  await tick(20);
  eq(calls.filter(c => c.method === "DELETE").length, 0, "cancel deletes nothing");
  clickEl(q('[data-ah="delete"][data-id="7"]'));
  await until(() => calls.filter(c => c.method === "DELETE").length === 1, "delete sent");
  eq(calls.find(c => c.method === "DELETE").path, "/api/admin/assigned-handouts/7", "same DELETE endpoint");
  await until(() => qa("#ahList tbody tr").length === 2 && q('[data-ah="reset"]') && !q('[data-ah="reset"]').disabled, "a fresh reset button after the reload");
  const staleReset = q('[data-ah="reset"]');
  const oldList = q("#ahList");
  await handoutsView.unmount();
  oldList.append(staleReset);                          // put a row button back under the old delegated listener
  const beforeStale = calls.length;
  clickEl(staleReset);
  await tick(20);
  eq(calls.length, beforeStale, "the delegated list listener is gone after unmount");

  const failing = makeCtx(handoutHandlers({ "POST /api/admin/assigned-handouts/8/reset": () => { throw new Error("nope"); } }));
  await handoutsView.mount(mountPoint(), failing.ctx);
  await until(() => qa("#ahList tbody tr").length === 2, "rows");
  clickEl(q('[data-ah="reset"]'));
  await until(() => failing.toasts.some(t => t.message === "nope" && t.type === "error"), "failed reset is reported, not swallowed");
  ok(!q('[data-ah="reset"]').disabled, "button usable again after a failure");
  await handoutsView.unmount();
}
{
  // 2e. leaving during an upload: request not aborted, outcome reported by toast; XSS
  const gate = deferred();
  const { ctx, calls, toasts } = makeCtx(handoutHandlers({ "POST /api/admin/assigned-handouts": () => gate.promise }));
  await handoutsView.mount(mountPoint(), ctx);
  await until(() => qa(".ah-pick").length === 2, "picker");
  q("#ahTopic").value = "T";
  Object.defineProperty(q("#ahFile"), "files", { value: [new window.File(["x"], "t.txt")], configurable: true });
  qa(".ah-pick")[0].checked = true;
  clickEl(q("#ahAssignBtn"));
  await tick(10);
  const upload = calls.find(c => c.method === "POST");
  await handoutsView.unmount();
  ok(!upload.signal, "uploads are never given an abort signal");
  gate.resolve({ success: true, message: "Assigned to 1 participant(s)." });
  await until(() => toasts.some(t => /Assigned to 1 participant/.test(t.message)), "outcome toast after leaving");
  ok(!q("#ahTopic"), "no re-render after unmount");

  const hostile = '<img src=x onerror="window.__pwned2=1">';
  const x = makeCtx({
    "GET /api/admin/participants": { success: true, participants: [{ code: hostile, email: hostile }] },
    "GET /api/admin/assigned-handouts": { success: true, handouts: [{ ...HANDOUTS[0], topic: hostile, filename: hostile, participantCode: hostile }] }
  });
  await handoutsView.mount(mountPoint(), x.ctx);
  await until(() => qa("#ahList tbody tr").length === 1 && qa(".ah-pick").length === 1, "hostile rows");
  ok(!q("img") && !window.__pwned2, "server strings are inserted as text");
  await handoutsView.unmount();
}

// ── 3. data exports ─────────────────────────────────────────────────────────────────────────────
const EXPECTED_LINKS = [
  ["⬇ Attempts CSV", "/api/admin/export/attempts.csv"],
  ["⬇ Per-question CSV", "/api/admin/export/attempt_questions.csv"],
  ["⬇ Exposures CSV", "/api/admin/export/question_exposures.csv"],
  ["⬇ Reliability CSV", "/api/admin/export/reliability.csv"],
  ["⬇ Review sample CSV", "/api/admin/export/review_questions.csv?perHandout=10"],
  ["⬇ Review sources TXT", "/api/admin/export/review_sources.txt?perHandout=10"],
  ["⬇ SUS CSV", "/api/admin/export/sus.csv"]
];
{
  const gate = deferred();
  const { ctx, calls } = makeCtx({ "GET /api/admin/participants": () => gate.promise });
  await exportsView.mount(mountPoint(), ctx);
  ok(q("#printExclude"), "legacy focus/print id #printExclude is present");
  ok(/Checking enrolment/.test(text()), "loading state");
  const links = qa("a.print-btn").map(a => [a.textContent, a.getAttribute("href")]);
  eq(links.sort((a, b) => a[0].localeCompare(b[0])), [...EXPECTED_LINKS].sort((a, b) => a[0].localeCompare(b[0])), "every legacy export keeps its label and exact URL (including perHandout=10)");
  ok(qa("a.print-btn").every(a => a.getAttribute("href").startsWith("/api/admin/export/")), "links are same-origin relative URLs like the legacy ones");
  ok(!q('a[href*="mapping.csv"]'), "the identity key stays on the Participants page");
  gate.resolve({ success: true, participants: PEOPLE });
  await until(() => /2 participant\(s\) enrolled · 1 consented/.test(text()), "coverage line");
  eq(calls.length, 1, "the only request is the informational participant check");
  eq(calls[0].method, "GET", "read-only");
  await exportsView.unmount();
  ok(!q("#printExclude"), "unmount removes the view");

  const none = makeCtx({ "GET /api/admin/participants": { success: true, participants: [] } });
  await exportsView.mount(mountPoint(), none.ctx);
  await until(() => /No participants are enrolled yet/.test(text()), "empty state");
  ok(q('a[href="#/study/participants"]') && qa("a.print-btn").length === 7, "empty state links to Participants and downloads stay available");
  await exportsView.unmount();

  let n = 0;
  const failing = makeCtx({ "GET /api/admin/participants": () => { if (++n === 1) throw new Error("db down"); return { success: true, participants: PEOPLE }; } });
  await exportsView.mount(mountPoint(), failing.ctx);
  await until(() => /Could not check enrolment: db down/.test(text()), "error state");
  ok(qa("a.print-btn").length === 7, "downloads stay available when the check fails");
  clickEl([...qa("button")].find(b => b.textContent === "Retry"));
  await until(() => /2 participant\(s\) enrolled/.test(text()), "retry recovers");
  const pending = makeCtx({ "GET /api/admin/participants": () => new Promise(() => {}) });
  await exportsView.unmount();
  await exportsView.mount(mountPoint(), pending.ctx);
  await exportsView.mount(mountPoint(), pending.ctx);
  eq(qa("#printExclude").length, 1, "repeated mount leaves one view");
  ok(pending.calls[0].signal.aborted, "previous in-flight check is aborted by the next mount");
  await exportsView.unmount();
  ok(pending.calls[pending.calls.length - 1].signal.aborted, "in-flight check aborted on unmount");
}

// ── 4. cross-view: navigation sequence leaves nothing behind ────────────────────────────────────
{
  const { ctx } = makeCtx({
    "GET /api/admin/participants": { success: true, participants: PEOPLE },
    "GET /api/admin/assigned-handouts": { success: true, handouts: HANDOUTS }
  });
  for (const view of [participantsView, handoutsView, exportsView, participantsView, exportsView, handoutsView]) {
    await view.mount(mountPoint(), ctx);
    await tick(15);
    await view.unmount();
    eq(mountPoint().childElementCount, 0, "each unmount leaves #adminContent empty");
  }
  eq(document.querySelectorAll("dialog").length, 0, "no stray dialogs");
}

console.log(`Study view checks passed: ${checks} assertions (jsdom, mocked API, real admin/ui.js; NOT run through the real shell).`);
process.exit(0);
