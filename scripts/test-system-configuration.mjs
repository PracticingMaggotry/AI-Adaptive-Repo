/**
 * Focused tests for System > Configuration.
 * Run: node scripts/test-system-configuration.mjs      (Node 22+)
 *
 * The model tests (types, validation, registry-vs-backend) need nothing. The view tests need `jsdom`, which is
 * NOT a dependency of this repository (same arrangement as test-study-views.mjs):
 *     npm i --prefix /tmp/study-test jsdom
 *     JSDOM_FROM=/tmp/study-test/x.js node scripts/test-system-configuration.mjs
 * Without jsdom the view section prints SKIPPED and the script exits 1, so a missing dependency is never a pass.
 *
 * Proves: supported types and bounds, validation, the exact PUT request, save success/failure/ambiguous failure,
 * stale-value refusal, read-only handling of unknown/unseeded/unconsumed keys, no create/delete calls, lifecycle
 * cleanup, and that route registry entries are preserved.
 * Does NOT prove: a real browser, a real backend, or that a value persists in MySQL (no live backend test was run).
 */
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";

let checks = 0;
const ok = (value, message) => { checks += 1; assert.ok(value, message); };
const eq = (actual, expected, message) => { checks += 1; assert.deepEqual(actual, expected, message); };
const tick = (ms = 0) => new Promise((resolve) => setTimeout(resolve, ms));

const staticRoot = new URL("../src/main/resources/static/admin/", import.meta.url);
const javaRoot = new URL("../src/main/java/com/adaptivelearning/adaptivelearningbackend/", import.meta.url);
const model = await import(new URL("views/configuration-model.js", staticRoot).href);
const {
  SETTINGS, SUPPORTED_KEYS, EDITABLE_KEYS, getSetting, validateInput, validatePair, warningsFor, buildModel, groupModel,
  buildUpdateRequest, interpretStored, parseWholeNumber, INT_MAX, PASSWORD_MAX_LENGTH
} = model;

// ── 1. Registry matches the backend source ────────────────────────────────────────────────────────
{
  const java = await readFile(new URL("ConfigurationService.java", javaRoot), "utf8");
  const seen = new Map();
  for (const m of java.matchAll(/get(Int|Long|String|Boolean)Config\("([A-Z_]+)",\s*([^)]+)\)/g)) seen.set(m[2], { kind: m[1], def: m[3].trim() });
  eq(seen.size, 19, "ConfigurationService declares 19 keys (if this changes, review the registry)");
  eq([...seen.keys()].sort(), [...SUPPORTED_KEYS].sort(), "registry covers exactly the keys ConfigurationService reads");
  const typeFor = { Int: "INTEGER", Long: "LONG", String: "STRING", Boolean: "BOOLEAN" };
  for (const [key, info] of seen) {
    eq(getSetting(key).type, typeFor[info.kind], `${key} type`);
    if (info.kind === "Int") eq(String(getSetting(key).default), info.def, `${key} default`);
    if (info.kind === "Boolean") eq(String(getSetting(key).default), info.def, `${key} default`);
    if (info.kind === "String") eq(`"${getSetting(key).default}"`, info.def, `${key} default`);
  }
  eq(getSetting("MAX_UPLOAD_BYTES").default, 10 * 1024 * 1024, "MAX_UPLOAD_BYTES default is 10 MB");

  // Snapshot names exist in the controller's /current/all.
  const controller = await readFile(new URL("SystemConfigurationController.java", javaRoot), "utf8");
  for (const s of SETTINGS.filter((x) => x.snapshot)) ok(controller.includes(`"${s.snapshot}"`), `${s.snapshot} is in /current/all`);
  eq(SETTINGS.filter((x) => x.snapshot).length, 12, "twelve keys are in the snapshot");

  // Seed: every seeded key has the registry's type.
  const seed = await readFile(new URL("../../../../resources/db/migration/V3__Create_system_configuration_and_seed.sql", javaRoot), "utf8");
  for (const m of seed.matchAll(/\('([A-Z_]+)',\s*'([^']*)',\s*'([A-Z]+)'/g)) {
    eq(getSetting(m[1]).type, m[3], `${m[1]} seed type`);
    eq(String(getSetting(m[1]).default), m[2], `${m[1]} seed value equals code default`);
  }
  ok(PASSWORD_MAX_LENGTH === 128 && java.length > 0 && (await readFile(new URL("PasswordPolicy.java", javaRoot), "utf8")).includes("MAX_LENGTH = 128"), "128 is PasswordPolicy.MAX_LENGTH");
  const props = await readFile(new URL("../../../../resources/application.properties", javaRoot), "utf8");
  ok(/multipart\.max-file-size=15MB/.test(props), "15 MB servlet ceiling still in application.properties");

  // Editable set is exactly the keys with a verified consumer.
  eq([...EDITABLE_KEYS].sort(), SUPPORTED_KEYS.filter((k) => !["MAX_MIXED_QUESTIONS", "MAX_PREVIEW_LENGTH", "MIN_EXTRACTED_TEXT_LENGTH"].includes(k)).sort(), "editable keys");
  for (const key of ["MAX_MIXED_QUESTIONS", "MAX_PREVIEW_LENGTH", "MIN_EXTRACTED_TEXT_LENGTH"]) {
    const uses = [...java.matchAll(new RegExp(`\\b${{ MAX_MIXED_QUESTIONS: "getMaxMixedQuestions", MAX_PREVIEW_LENGTH: "getMaxPreviewLength", MIN_EXTRACTED_TEXT_LENGTH: "getMinExtractedTextLength" }[key]}\\b`, "g"))].length;
    eq(uses, 1, `${key} getter is only declared in ConfigurationService (no internal use)`);
    eq(validateInput(key, "5").ok, false, `${key} is not editable`);
  }
}

// ── 2. Strict parsing and per-type validation ─────────────────────────────────────────────────────
for (const bad of ["", " ", "+5", "5.0", "1e3", " 5", "5 ", "0x10", "-0", "05", "abc", "١٢", "5,000"]) eq(parseWholeNumber(bad), null, `parseWholeNumber rejects ${JSON.stringify(bad)}`);
eq(parseWholeNumber("0"), 0n, "0 parses");
eq(parseWholeNumber("-7"), -7n, "negative parses (range checked separately)");

const V = (key, input) => validateInput(key, input);
// INTEGER daily limits: >= 0, <= int max
eq(V("MAX_UPLOADS_PER_DAY", "0"), { ok: true, value: "0" }, "daily limit 0 allowed (AdminController allows 0)");
eq(V("MAX_UPLOADS_PER_DAY", "25"), { ok: true, value: "25" });
eq(V("MAX_UPLOADS_PER_DAY", String(INT_MAX)), { ok: true, value: String(INT_MAX) });
for (const bad of ["-1", String(INT_MAX + 1), "1.5", "ten", "", "+3"]) eq(V("MAX_UPLOADS_PER_DAY", bad).ok, false, `daily limit rejects ${JSON.stringify(bad)}`);
eq(V("MAX_UPLOADS_PER_DAY", " 12 "), { ok: true, value: "12" }, "surrounding spaces in a number field are trimmed");
// LONG
eq(V("MAX_UPLOAD_BYTES", "10485760"), { ok: true, value: "10485760" });
eq(V("MAX_UPLOAD_BYTES", "9223372036854775807").ok, true, "long max accepted");
eq(V("MAX_UPLOAD_BYTES", "9223372036854775808").ok, false, "long overflow rejected");
eq(V("MAX_UPLOAD_BYTES", "0").ok, false, "0 bytes would reject every non-empty file");
eq(V("MAX_UPLOAD_BYTES", "1.5").ok, false);
eq(V("MAX_UPLOAD_BYTES", "10MB").ok, false);
// Other INTEGER
eq(V("MAX_TOPIC_LENGTH", "0").ok, false, "0 would reject every topic");
eq(V("MAX_TOPIC_LENGTH", "1").ok, true);
// Difficulty
eq(V("HARD_DIFFICULTY_THRESHOLD", "100").ok, true); eq(V("HARD_DIFFICULTY_THRESHOLD", "101").ok, false); eq(V("HARD_DIFFICULTY_THRESHOLD", "-1").ok, false);
eq(V("MEDIUM_DIFFICULTY_THRESHOLD", "0").ok, true);
// Password
eq(V("MIN_PASSWORD_LENGTH", "128").ok, true); eq(V("MIN_PASSWORD_LENGTH", "129").ok, false, "above PasswordPolicy.MAX_LENGTH makes every password invalid");
eq(V("MIN_PASSWORD_LENGTH", "0").ok, false); eq(V("MIN_PASSWORD_LENGTH", "1").ok, true);
// BOOLEAN: only canonical text
eq(V("REQUIRE_PASSWORD_UPPERCASE", "true"), { ok: true, value: "true" });
eq(V("REQUIRE_PASSWORD_UPPERCASE", "false"), { ok: true, value: "false" });
for (const bad of ["TRUE", "yes", "1", "", "True"]) eq(V("REQUIRE_PASSWORD_NUMBER", bad).ok, false, `boolean rejects ${JSON.stringify(bad)} (backend would silently read it as false)`);
// STRING: extensions
eq(V("ALLOWED_FILE_EXTENSIONS", "pdf, txt ,docx"), { ok: true, value: "pdf,txt,docx" }, "trimmed and canonicalised");
eq(V("ALLOWED_FILE_EXTENSIONS", "pdf,pdf,txt"), { ok: true, value: "pdf,txt" }, "duplicates collapsed");
for (const bad of ["", "pdf,", ",pdf", "pdf,,txt", "PDF", "exe", "pdf;txt", ".pdf"]) eq(V("ALLOWED_FILE_EXTENSIONS", bad).ok, false, `extensions reject ${JSON.stringify(bad)}`);
// STRING: content-type fragments
eq(V("ALLOWED_CONTENT_TYPES", "pdf,text,msword"), { ok: true, value: "pdf,text,msword" });
for (const bad of ["", "pdf,", "PDF", "application/pdf ,", "a b", "/pdf"]) eq(V("ALLOWED_CONTENT_TYPES", bad).ok, false, `fragments reject ${JSON.stringify(bad)} (an empty fragment matches every type)`);
eq(V("ALLOWED_CONTENT_TYPES", "application/pdf").ok, false, "slash is outside the verified fragment alphabet");
// Unknown / read-only keys can never validate
eq(V("SOME_NEW_KEY", "1").ok, false, "unknown key"); eq(V("MAX_PREVIEW_LENGTH", "100").ok, false, "unconsumed key");
let threw = false; try { buildUpdateRequest("SOME_NEW_KEY", "1"); } catch { threw = true; }
ok(threw, "no request can be built for an unsupported key");
threw = false; try { buildUpdateRequest("MAX_PREVIEW_LENGTH", "1"); } catch { threw = true; }
ok(threw, "no request can be built for a read-only key");

// Pair rule
eq(validatePair("HARD_DIFFICULTY_THRESHOLD", "40", "50").ok, false, "hard below medium"); eq(validatePair("HARD_DIFFICULTY_THRESHOLD", "90", "50").ok, true);
eq(validatePair("MEDIUM_DIFFICULTY_THRESHOLD", "85", "80").ok, false, "medium above hard"); eq(validatePair("MEDIUM_DIFFICULTY_THRESHOLD", "80", "80").ok, true);
ok(validatePair("MEDIUM_DIFFICULTY_THRESHOLD", "80", "80").warning, "equal thresholds warn");
eq(validatePair("MEDIUM_DIFFICULTY_THRESHOLD", "10", "abc").ok, false, "unreadable counterpart blocks");
eq(validatePair("MAX_UPLOADS_PER_DAY", "1", "x"), { ok: true }, "no pair rule on other keys");

// Warnings
ok(warningsFor("MAX_UPLOADS_PER_DAY", "0").some((w) => /blocks/.test(w)), "0 daily limit warns");
ok(warningsFor("MAX_UPLOAD_BYTES", String(20 * 1024 * 1024)).some((w) => /15 MB/.test(w)), "above servlet ceiling warns");
eq(warningsFor("MAX_UPLOAD_BYTES", "1048576"), [], "no warning for a normal value");
ok(warningsFor("MIN_PASSWORD_LENGTH", "6").length === 1, "below seeded default warns");

// Backend interpretation of stored text
eq(interpretStored(getSetting("MAX_TOPIC_LENGTH"), "abc").parsable, false, "bad int -> backend default");
eq(interpretStored(getSetting("MAX_TOPIC_LENGTH"), " 5").parsable, false, "Integer.parseInt does not trim");
eq(interpretStored(getSetting("MAX_TOPIC_LENGTH"), "+5"), { parsable: true, canonical: false, effective: "5" }, "Integer.parseInt accepts +5 (non-canonical)");
eq(interpretStored(getSetting("MAX_TOPIC_LENGTH"), "2147483648").parsable, false, "int overflow -> default");
eq(interpretStored(getSetting("REQUIRE_PASSWORD_NUMBER"), "yes"), { parsable: true, canonical: false, effective: "false" }, "parseBoolean('yes') is false");
eq(interpretStored(getSetting("REQUIRE_PASSWORD_NUMBER"), "TRUE").effective, "true");
eq(interpretStored(getSetting("ALLOWED_FILE_EXTENSIONS"), "  ").parsable, false, "blank string -> default");

// Model building
{
  const rows = [
    { id: 1, configKey: "MAX_UPLOADS_PER_DAY", configValue: "4", dataType: "INTEGER", description: "d" },
    { id: 2, configKey: "MIN_PASSWORD_LENGTH", configValue: "abc", dataType: "INTEGER" },
    { id: 3, configKey: "MAX_TOPIC_LENGTH", configValue: "100", dataType: "STRING" },
    { id: 4, configKey: "FEATURE_X", configValue: "on", dataType: "STRING", description: "mystery" },
    { id: 5, configKey: "MAX_PREVIEW_LENGTH", configValue: "1800", dataType: "INTEGER" }
  ];
  const snap = { maxUploadsPerDay: 4, minPasswordLength: 8, maxTopicLength: 100, maxPreviewLength: 1800 };
  const m = buildModel(rows, snap);
  const by = Object.fromEntries(m.supported.map((e) => [e.setting.key, e]));
  eq(by.MAX_UPLOADS_PER_DAY.editable, true);
  eq(by.MAX_QUIZ_SUBMISSIONS_PER_DAY.editable, false, "unseeded daily limit has no row -> read-only");
  ok(/cannot create rows/.test(by.MAX_QUIZ_SUBMISSIONS_PER_DAY.blockedReason));
  eq(by.MIN_PASSWORD_LENGTH.editable, true, "an unparsable stored value can be repaired");
  ok(by.MIN_PASSWORD_LENGTH.notes.some((n) => /default/.test(n)), "unparsable stored value is called out");
  eq(by.MAX_TOPIC_LENGTH.editable, false, "type mismatch blocks editing"); ok(/typed STRING/.test(by.MAX_TOPIC_LENGTH.blockedReason));
  eq(by.MAX_PREVIEW_LENGTH.editable, false, "unconsumed key stays read-only even with a row");
  eq(m.unknown.map((u) => u.key), ["FEATURE_X"], "unknown record is listed separately");
  eq(groupModel(m).map((g) => g.id), ["daily", "uploads", "difficulty", "extraction", "password", "other"], "groups in order");
  const failed = buildModel(null, null);
  ok(failed.supported.every((e) => !e.editable), "nothing is editable when rows did not load");
  const req = buildUpdateRequest("MAX_UPLOAD_BYTES", "20971520");
  eq(req.path, "/api/admin/configuration/MAX_UPLOAD_BYTES"); eq(req.options.method, "PUT");
  eq(JSON.parse(req.options.body), { configValue: "20971520", dataType: "LONG" }, "PUT body: value as text + proper data type, nothing else");
}

// ── 3. Routes are preserved ───────────────────────────────────────────────────────────────────────
{
  const routesModule = await import(new URL("routes.js", staticRoot).href);
  const { routes, aliases, sections, defaultPath } = routesModule;
  const by = Object.fromEntries(routes.map((r) => [r.path, r]));
  eq(routes.length, 17, "17 routes (16 sidebar + user detail)");
  eq(routes.map((r) => r.path), [
    "/overview", "/people/users", "/people/users/:id", "/people/deletion-requests", "/people/banned-emails",
    "/content/materials", "/content/reported-questions", "/content/categories",
    "/study/participants", "/study/assigned-handouts", "/study/data-exports",
    "/system/usage-limits", "/system/configuration", "/system/ai-testing", "/system/reliability",
    "/audit/activity-log", "/audit/platform-report"
  ], "route list and order unchanged");
  eq(aliases, {
    overview: "/overview", users: "/people/users", topics: "/content/materials", reportedquestions: "/content/reported-questions",
    bannedemails: "/people/banned-emails", deletionrequests: "/people/deletion-requests", reports: "/audit/platform-report",
    activitylog: "/audit/activity-log", assignedhandouts: "/study/assigned-handouts", aitesting: "/system/ai-testing"
  }, "aliases unchanged (reports -> platform report, not legacy)");
  eq(sections.map((s) => s.id), ["overview", "people", "content", "study", "system", "audit"]); eq(defaultPath, "/overview");
  eq(by["/system/configuration"].section, "system"); eq(by["/system/configuration"].title, "Configuration");
  ok(!by["/system/configuration"].legacyTab && !by["/system/configuration"].after, "Configuration is a standalone view (no legacy tab, no after-actions)");
  ok(String(by["/system/configuration"].load).includes("./views/configuration.js"), "Configuration loads its own module");
  eq(by["/system/configuration"].status, "ready", "Configuration is now an implemented route");
  const mod = (await by["/system/configuration"].load()).default;
  eq([typeof mod.mount, typeof mod.unmount], ["function", "function"], "mount/unmount contract");
  for (const [p, file] of [["/study/participants", "study-participants.js"], ["/study/assigned-handouts", "assignedhandouts.js"], ["/study/data-exports", "study-data-exports.js"], ["/audit/platform-report", "platform-report.js"], ["/system/reliability", "reliability.js"]]) {
    ok(String(by[p].load).includes("./views/" + file), `${p} still loads ${file}`);
  }
  eq(by["/study/participants"].legacyTab, undefined); eq(by["/audit/platform-report"].legacyTab, undefined); eq(by["/system/reliability"].legacyTab, undefined);
  eq(by["/system/reliability"].status, "partial"); eq(by["/audit/platform-report"].after, { focus: "#printTarget" });
  eq(by["/system/usage-limits"].after, { modal: "openDailyLimitsModal" }); eq(by["/system/usage-limits"].legacyTab, "users");
  eq(by["/people/users/:id"].shellLoad !== by["/people/users/:id"].load, true, "user detail keeps its shellLoad");
  eq(by["/content/materials"].after, { subTab: "materials" }); eq(by["/content/categories"].after, { subTab: "categories" });
  const src = await readFile(new URL("routes.js", staticRoot), "utf8");
  ok(!/configurationView|notImplementedView/.test(src), "placeholder removed from routes.js");
}

// ── 4. View behaviour (needs jsdom) ───────────────────────────────────────────────────────────────
async function loadJsdom() {
  try { return await import("jsdom"); } catch { /* fall through */ }
  const from = process.env.JSDOM_FROM || path.join(process.cwd(), "x.js");
  try { return createRequire(from)("jsdom"); } catch { return null; }
}
const jsdom = await loadJsdom();
if (!jsdom) { console.log(`Model and route checks passed (${checks}). SKIPPED the view tests: jsdom is not installed (see the header). Nothing in the view was verified.`); process.exit(1); }

const dom = new jsdom.JSDOM('<!doctype html><html><body><div id="adminContent"></div></body></html>', { url: "http://localhost/admin.html", pretendToBeVisual: true });
const { window } = dom;
for (const name of ["document", "Node", "Element", "HTMLElement", "AbortController", "AbortSignal", "Event", "MouseEvent", "KeyboardEvent"]) {
  Object.defineProperty(globalThis, name, { value: window[name], configurable: true, writable: true });
}
globalThis.window = window;
window.console = console;
const realUi = (await import(new URL("ui.js", staticRoot).href)).default;
const view = (await import(new URL("views/configuration.js", staticRoot).href)).default;

const mountPoint = () => document.getElementById("adminContent");
const text = () => mountPoint().textContent;
const row = (key) => [...mountPoint().querySelectorAll("[data-key]")].find((n) => n.getAttribute("data-key") === key);
const click = (node) => node.dispatchEvent(new window.MouseEvent("click", { bubbles: true, cancelable: true }));
const setInput = (node, value) => { node.value = value; node.dispatchEvent(new window.Event("input", { bubbles: true })); };
async function until(predicate, message, timeout = 1500) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (predicate()) { checks += 1; return; } await tick(5); }
  assert.fail("timed out waiting for: " + message);
}

const SEED_ROWS = () => [
  ["MAX_UPLOADS_PER_DAY", "4", "INTEGER"], ["MAX_MIXED_QUESTIONS", "30", "INTEGER"], ["MAX_TOPIC_LENGTH", "100", "INTEGER"],
  ["MAX_UPLOAD_BYTES", "10485760", "LONG"], ["HARD_DIFFICULTY_THRESHOLD", "80", "INTEGER"], ["MEDIUM_DIFFICULTY_THRESHOLD", "50", "INTEGER"],
  ["ALLOWED_FILE_EXTENSIONS", "pdf,txt,csv,doc,docx", "STRING"], ["ALLOWED_CONTENT_TYPES", "pdf,text,csv,msword,wordprocessingml,octet-stream", "STRING"],
  ["MAX_PREVIEW_LENGTH", "1800", "INTEGER"], ["MIN_EXTRACTED_TEXT_LENGTH", "100", "INTEGER"], ["MIN_PASSWORD_LENGTH", "8", "INTEGER"],
  ["REQUIRE_PASSWORD_UPPERCASE", "true", "BOOLEAN"], ["REQUIRE_PASSWORD_NUMBER", "true", "BOOLEAN"], ["REQUIRE_PASSWORD_SPECIAL_CHAR", "true", "BOOLEAN"]
].map(([configKey, configValue, dataType], i) => ({ id: i + 1, configKey, configValue, dataType, description: "seed " + configKey }));

const SNAP_NAMES = Object.fromEntries(SETTINGS.filter((s) => s.snapshot).map((s) => [s.key, s.snapshot]));
function snapshotFrom(rows) {
  const out = {};
  for (const r of rows) {
    const s = getSetting(r.configKey);
    if (!s || !SNAP_NAMES[r.configKey]) continue;
    // Mirrors ConfigurationService: parse the stored text, fall back to the code default when it cannot be parsed.
    const effective = interpretStored(s, r.configValue).effective;
    out[SNAP_NAMES[r.configKey]] = s.type === "BOOLEAN" ? effective === "true" : s.type === "STRING" ? effective : Number(effective);
  }
  return out;
}

/** Fake backend: a mutable table behind the four endpoints. Records every call. */
function makeBackend(initial = SEED_ROWS()) {
  const rows = initial;
  const calls = [];
  const fail = { rows: null, snapshot: null, get: null, put: null, putAfterApply: null };
  const err = (status, message = "") => Object.assign(new Error(message), { status });
  async function request(pathname, options = {}) {
    const method = (options.method || "GET").toUpperCase();
    calls.push({ method, path: pathname, body: options.body, signal: options.signal });
    await tick(1);
    if (options.signal && options.signal.aborted) throw Object.assign(new Error("aborted"), { name: "AbortError" });
    if (method === "GET" && pathname === "/api/admin/configuration") { if (fail.rows) throw fail.rows(); return JSON.parse(JSON.stringify(rows)); }
    if (method === "GET" && pathname === "/api/admin/configuration/current/all") { if (fail.snapshot) throw fail.snapshot(); return snapshotFrom(rows); }
    const m = pathname.match(/^\/api\/admin\/configuration\/([^/]+)$/);
    if (m) {
      const key = decodeURIComponent(m[1]);
      const found = rows.find((r) => r.configKey === key);
      if (method === "GET") { if (fail.get) throw fail.get(key); if (!found) throw err(404); return { ...found }; }
      if (method === "PUT") {
        if (!found) throw err(404);
        const body = JSON.parse(options.body);
        if (fail.put) throw fail.put(key, body);
        if (body.configValue != null) found.configValue = body.configValue;
        if (body.dataType != null) found.dataType = body.dataType;
        if (fail.putAfterApply) throw fail.putAfterApply();
        return { ...found };
      }
    }
    throw new Error("unexpected request " + method + " " + pathname);
  }
  return { rows, calls, fail, request, err, writes: () => calls.filter((c) => c.method !== "GET") };
}

function makeCtx(backend, answers = []) {
  const confirms = []; const toasts = [];
  const ui = {
    ...realUi,
    confirm: async (opts) => { confirms.push(opts); const a = answers.length ? answers.shift() : true; return { confirmed: a, reason: "" }; },
    toast: (msg, o) => { toasts.push({ msg, type: o && o.type }); }
  };
  return { ctx: { api: { request: backend.request, get: backend.request }, ui }, confirms, toasts };
}

async function mountView(backend, answers) {
  const made = makeCtx(backend, answers);
  await view.mount(mountPoint(), made.ctx);
  return { ...made, backend };
}
const reset = () => { view.unmount(); mountPoint().replaceChildren(); };
async function edit(key, value) {
  const opener = row(key).querySelector('[data-action="edit"]');
  if (opener) click(opener);
  const input = row(key).querySelector('[data-role="input"]');
  ok(input, `editor opens for ${key}`);
  setInput(input, value);
  click(row(key).querySelector('[data-action="save"]'));
}

// 4a. Loading, groups, read-only handling
{
  const backend = makeBackend([...SEED_ROWS(), { id: 99, configKey: "FEATURE_X", configValue: "<b>on</b>", dataType: "STRING", description: "mystery" }]);
  const gate = []; const original = backend.request;
  const slow = { ...backend, request: (p, o) => new Promise((resolve, reject) => gate.push(() => original(p, o).then(resolve, reject))) };
  const made = makeCtx(slow);
  const mounting = view.mount(mountPoint(), made.ctx);
  ok(/Loading configuration/.test(text()), "loading state is shown");
  gate.splice(0).forEach((f) => f());
  await mounting;
  const groups = [...mountPoint().querySelectorAll("[data-group]")].map((n) => n.getAttribute("data-group"));
  eq(groups, ["daily", "uploads", "difficulty", "extraction", "password", "other"], "all six groups render");
  ok(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="edit"]'), "seeded daily limit is editable");
  eq(row("MAX_QUIZ_SUBMISSIONS_PER_DAY").querySelector('[data-action="edit"]'), null, "unseeded daily limit has no Edit");
  ok(/cannot create rows/.test(row("MAX_QUIZ_SUBMISSIONS_PER_DAY").textContent), "…and says why");
  for (const k of ["MAX_MIXED_QUESTIONS", "MAX_PREVIEW_LENGTH", "MIN_EXTRACTED_TEXT_LENGTH"]) {
    eq(row(k).querySelector('[data-action="edit"]'), null, `${k} has no Edit`);
    ok(/No code outside ConfigurationService/.test(row(k).textContent), `${k} explains it is unconsumed`);
  }
  const unknown = row("FEATURE_X");
  eq(unknown.querySelector("button, input, select"), null, "unknown record has no controls");
  ok(/Read-only/.test(unknown.textContent) && unknown.textContent.includes("<b>on</b>"), "unknown record shown read-only, value rendered as text");
  eq(unknown.querySelector("b"), null, "no HTML injected from a value");
  ok(/not written to the Activity Log/.test(text()), "audit limitation is stated");
  eq(backend.calls.map((c) => c.method + " " + c.path).sort(), ["GET /api/admin/configuration", "GET /api/admin/configuration/current/all"], "mounting issues exactly the two read requests");
  eq(backend.writes().length, 0, "no writes on mount");
  reset();
}

// 4b. Error, retry, empty, snapshot failure
{
  const backend = makeBackend();
  backend.fail.rows = () => backend.err(500, "database down");
  await mountView(backend);
  ok(/Could not load the configuration: database down/.test(text()), "error state with reason");
  ok(mountPoint().querySelector("button"), "Retry button present");
  backend.fail.rows = null;
  click([...mountPoint().querySelectorAll("button")].find((b) => b.textContent === "Retry"));
  await until(() => row("MAX_UPLOADS_PER_DAY"), "retry recovers");
  reset();

  const forbidden = makeBackend(); forbidden.fail.rows = () => forbidden.err(403, "");
  await mountView(forbidden);
  ok(/Admin access is required \(HTTP 403\)/.test(text()), "403 with empty body gets a readable message");
  reset();

  const empty = makeBackend([]);
  await mountView(empty);
  ok(/No configuration records were found/.test(text()), "empty state");
  reset();

  const noSnap = makeBackend(); noSnap.fail.snapshot = () => noSnap.err(500, "snapshot boom");
  await mountView(noSnap);
  ok(/effective-value snapshot could not be loaded \(snapshot boom\)/.test(text()), "snapshot failure is reported");
  ok(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="edit"]').disabled, "editing disabled without a snapshot");
  noSnap.fail.snapshot = null;
  click([...mountPoint().querySelectorAll("#configBanner button")][0]);
  await until(() => row("MAX_UPLOADS_PER_DAY") && !row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="edit"]').disabled, "snapshot retry re-enables editing");
  reset();
}

// 4c. Validation in the UI sends nothing
{
  const backend = makeBackend();
  const made = await mountView(backend);
  await edit("MAX_UPLOADS_PER_DAY", "-3");
  await until(() => /Must be a whole number from 0/.test(row("MAX_UPLOADS_PER_DAY").textContent), "range error shown");
  await edit("MAX_UPLOADS_PER_DAY", "4.5");
  await until(() => /digits only/.test(row("MAX_UPLOADS_PER_DAY").textContent), "decimal rejected");
  eq(made.confirms.length, 0, "no confirmation for invalid input"); eq(backend.writes().length, 0, "nothing sent for invalid input");
  const input = row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="input"]');
  eq(input.value, "4.5", "draft stays visible after a validation error");
  setInput(input, "4"); click(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="save"]'));
  await until(() => /No change/.test(row("MAX_UPLOADS_PER_DAY").textContent), "same value is a no-op");
  eq(backend.writes().length, 0, "no-op sends nothing");
  click(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="cancel"]'));
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="input"]'), null, "Cancel closes the editor");
  reset();
}

// 4d. Successful save (integer, long, boolean, string) with exact request shape and reload
for (const [key, value, dataType, shown] of [
  ["MAX_UPLOADS_PER_DAY", "7", "INTEGER", "7"],
  ["MAX_UPLOAD_BYTES", "5242880", "LONG", "5242880"],
  ["ALLOWED_FILE_EXTENSIONS", "pdf, txt", "STRING", "pdf,txt"]
]) {
  const backend = makeBackend();
  const made = await mountView(backend);
  const readsBefore = backend.calls.length;
  await edit(key, value);
  await until(() => /Saved\./.test(row(key).textContent), `${key} saved`);
  const writes = backend.writes();
  eq(writes.length, 1, `${key}: exactly one write`);
  eq([writes[0].method, writes[0].path], ["PUT", "/api/admin/configuration/" + key], `${key}: PUT to the key path`);
  eq(JSON.parse(writes[0].body), { configValue: shown, dataType }, `${key}: body carries text value + data type only`);
  ok(!writes.some((c) => c.method === "POST" || c.method === "DELETE"), "never POST or DELETE");
  const after = backend.calls.slice(readsBefore).map((c) => c.method + " " + c.path);
  ok(after.includes("GET /api/admin/configuration") && after.includes("GET /api/admin/configuration/current/all"), `${key}: rows and snapshot reloaded after saving`);
  ok(after.indexOf("PUT /api/admin/configuration/" + key) < after.lastIndexOf("GET /api/admin/configuration/current/all"), `${key}: reload happens after the PUT`);
  eq(row(key).querySelector('[data-role="value"]').textContent, shown, `${key}: shown value comes from the reloaded server state`);
  eq(made.confirms.length, 1, `${key}: one confirmation`);
  ok(made.confirms[0].impact && made.confirms[0].message.includes(key), `${key}: confirmation names the setting and impact`);
  ok(made.toasts.some((t) => t.type === "success"), `${key}: success toast`);
  reset();
}
{
  const backend = makeBackend();
  const made = await mountView(backend);
  click(row("REQUIRE_PASSWORD_UPPERCASE").querySelector('[data-action="edit"]'));
  const select = row("REQUIRE_PASSWORD_UPPERCASE").querySelector("select");
  eq(select.value, "true", "boolean editor starts from the current value");
  select.value = "false"; select.dispatchEvent(new window.Event("input", { bubbles: true }));
  click(row("REQUIRE_PASSWORD_UPPERCASE").querySelector('[data-action="save"]'));
  await until(() => /Saved\./.test(row("REQUIRE_PASSWORD_UPPERCASE").textContent), "boolean saved");
  eq(JSON.parse(backend.writes()[0].body), { configValue: "false", dataType: "BOOLEAN" }, "boolean sent as the text 'false'");
  ok(/Existing passwords are not re-checked/.test(made.confirms[0].impact), "password confirmation explains scope");
  reset();
}

// 4e. Confirmation declined
{
  const backend = makeBackend();
  const made = await mountView(backend, [false]);
  await edit("MAX_UPLOADS_PER_DAY", "9");
  await until(() => /Cancelled/.test(row("MAX_UPLOADS_PER_DAY").textContent), "declined");
  eq(backend.writes().length, 0, "declining sends nothing");
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="value"]').textContent, "4", "displayed value unchanged");
  reset();
}

// 4f. Failures: value never shown as persisted
{
  const backend = makeBackend();
  backend.fail.put = () => backend.err(500, "constraint violation");
  const made = await mountView(backend);
  await edit("MAX_UPLOADS_PER_DAY", "9");
  await until(() => /the server did not confirm the change \(constraint violation\)/.test(row("MAX_UPLOADS_PER_DAY").textContent), "unconfirmed failure reported after verifying by reload");
  // 500 is ambiguous -> the page reloads to verify, and the server still holds 4
  await until(() => row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="value"]').textContent === "4", "server value still shown");
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="input"]').value, "9", "draft kept for retry");
  ok([...row("MAX_UPLOADS_PER_DAY").querySelectorAll("span")].some((n) => n.textContent === "Not saved"), "draft labelled as not saved");
  ok(!made.toasts.some((t) => t.type === "success"), "no success toast on failure");
  // The Save button is usable again (not stuck in save-in-progress)
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="save"]').disabled, false, "save re-enabled after failure");
  reset();
}
{
  const backend = makeBackend();
  backend.fail.put = () => backend.err(400, "bad request");
  await mountView(backend);
  await edit("MAX_TOPIC_LENGTH", "50");
  await until(() => /Not saved: bad request/.test(row("MAX_TOPIC_LENGTH").textContent), "4xx is a definite failure");
  eq(backend.calls.filter((c) => c.method === "GET" && c.path === "/api/admin/configuration").length, 1, "definite failure does not reload");
  eq(row("MAX_TOPIC_LENGTH").querySelector('[data-role="value"]').textContent, "100", "value unchanged");
  reset();
}
{
  // 404: the row vanished between load and save; no create is attempted.
  const backend = makeBackend();
  await mountView(backend);
  backend.rows.splice(backend.rows.findIndex((r) => r.configKey === "MAX_UPLOADS_PER_DAY"), 1);
  await edit("MAX_UPLOADS_PER_DAY", "9");
  await until(() => /Not saved/.test(row("MAX_UPLOADS_PER_DAY").textContent), "404 reported");
  eq(backend.writes().length, 0, "no PUT and no create after the row disappeared");
  reset();
}
{
  // Network failure after the server applied the change: verified by reload, not assumed.
  const backend = makeBackend();
  backend.fail.putAfterApply = () => new Error("Failed to fetch");
  await mountView(backend);
  await edit("MAX_UPLOADS_PER_DAY", "11");
  await until(() => /reloaded value shows the change was applied/.test(row("MAX_UPLOADS_PER_DAY").textContent), "ambiguous failure resolved by reload");
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="value"]').textContent, "11");
  reset();
}
{
  // Saved, but the follow-up reload fails: shown values are marked outdated, not presented as confirmed.
  const backend = makeBackend();
  const made = await mountView(backend);
  const orig = backend.request; let putSeen = false;
  made.ctx.api.request = async (p, o) => { if ((o && o.method) === "PUT") putSeen = true; if (putSeen && (o || {}).method !== "PUT" && p === "/api/admin/configuration") throw backend.err(500, "reload failed"); return orig(p, o); };
  await edit("MAX_UPLOADS_PER_DAY", "12");
  await until(() => /Saved, but the page could not reload/.test(row("MAX_UPLOADS_PER_DAY").textContent), "reload failure after save is reported");
  ok(/May be outdated/.test(text()), "values flagged as possibly outdated");
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="value"]').textContent, "4", "the page does not display the unconfirmed new value");
  reset();
}

// 4g. Stale values
{
  const backend = makeBackend();
  const made = await mountView(backend);
  click(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="edit"]'));
  backend.rows.find((r) => r.configKey === "MAX_UPLOADS_PER_DAY").configValue = "6"; // another admin
  setInput(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="input"]'), "9");
  click(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="save"]'));
  await until(() => /changed since the page loaded \(now 6/.test(row("MAX_UPLOADS_PER_DAY").textContent), "stale write refused");
  eq(backend.writes().length, 0, "stale save never sends the PUT");
  await until(() => row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="value"]').textContent === "6", "page reloaded to the newer value");
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="input"]'), null, "editor closed so the old draft cannot be re-submitted by habit");
  reset();
}
{
  // Stored text that the backend cannot parse: the effective default is what students see; the page says so and allows repair.
  const rows = SEED_ROWS(); rows.find((r) => r.configKey === "MIN_PASSWORD_LENGTH").configValue = "eight";
  const backend = makeBackend(rows);
  await mountView(backend);
  ok(/silently uses its default/.test(row("MIN_PASSWORD_LENGTH").textContent), "unparsable stored value flagged");
  eq(row("MIN_PASSWORD_LENGTH").querySelector('[data-role="value"]').textContent, "8", "effective value (the default) is what is shown");
  await edit("MIN_PASSWORD_LENGTH", "10");
  await until(() => /Saved\./.test(row("MIN_PASSWORD_LENGTH").textContent), "repair saved");
  eq(backend.rows.find((r) => r.configKey === "MIN_PASSWORD_LENGTH").configValue, "10");
  reset();
}

// 4h. Cross-field rule re-checked with fresh data
{
  const backend = makeBackend();
  await mountView(backend);
  await edit("HARD_DIFFICULTY_THRESHOLD", "40");
  await until(() => /cannot be lower than the Medium threshold \(50\)/.test(row("HARD_DIFFICULTY_THRESHOLD").textContent), "pair rule blocks");
  eq(backend.writes().length, 0);
  click(row("HARD_DIFFICULTY_THRESHOLD").querySelector('[data-action="cancel"]'));
  // Medium raised by someone else after load: fresh counterpart check refuses.
  click(row("HARD_DIFFICULTY_THRESHOLD").querySelector('[data-action="edit"]'));
  backend.rows.find((r) => r.configKey === "MEDIUM_DIFFICULTY_THRESHOLD").configValue = "75";
  setInput(row("HARD_DIFFICULTY_THRESHOLD").querySelector('[data-role="input"]'), "70");
  click(row("HARD_DIFFICULTY_THRESHOLD").querySelector('[data-action="save"]'));
  await until(() => /cannot be lower than the Medium threshold \(75\)/.test(row("HARD_DIFFICULTY_THRESHOLD").textContent), "fresh counterpart enforced");
  eq(backend.writes().length, 0, "no PUT when the fresh pair check fails");
  reset();
}

// 4i. Only one save at a time; unmount cleanliness
{
  const backend = makeBackend();
  const made = await mountView(backend);
  const orig = backend.request; let release;
  made.ctx.api.request = (p, o) => ((o || {}).method === "PUT" ? new Promise((resolve) => { release = () => resolve(orig(p, o)); }) : orig(p, o));
  await edit("MAX_UPLOADS_PER_DAY", "8");
  await until(() => release, "PUT in flight");
  ok(/Saving…/.test(row("MAX_UPLOADS_PER_DAY").textContent), "save-in-progress state shown");
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-action="save"]').disabled, true, "Save disabled while saving");
  eq(row("MAX_UPLOADS_PER_DAY").querySelector('[data-role="input"]').disabled, true, "input disabled while saving");
  ok(row("MAX_TOPIC_LENGTH").querySelector('[data-action="edit"]').disabled, "other Edit buttons disabled while saving");
  view.unmount();
  eq(mountPoint().childElementCount, 0, "unmount removes the DOM");
  const callsBefore = backend.calls.length;
  release(); await tick(30);
  eq(mountPoint().childElementCount, 0, "a save finishing after unmount does not touch the DOM");
  eq(backend.rows.find((r) => r.configKey === "MAX_UPLOADS_PER_DAY").configValue, "8", "the in-flight write was not aborted");
  eq(backend.calls.slice(callsBefore).filter((c) => c.method === "GET").length, 0, "no reload after unmount");
  reset();
}
{
  // Re-mount leaves no duplicate handlers: one click, one confirm.
  const backend = makeBackend();
  const first = makeCtx(backend); await view.mount(mountPoint(), first.ctx);
  const second = makeCtx(backend); await view.mount(mountPoint(), second.ctx);
  eq(mountPoint().querySelectorAll('[data-view="configuration"]').length, 1, "one root after re-mount");
  await tick(5);
  await edit("MAX_UPLOADS_PER_DAY", "5");
  await until(() => /Saved\./.test(row("MAX_UPLOADS_PER_DAY").textContent), "saved after re-mount");
  eq([first.confirms.length, second.confirms.length], [0, 1], "only the live mount reacts");
  eq(backend.writes().length, 1);
  reset();
}
{
  // Aborts GETs on unmount.
  const backend = makeBackend();
  const made = makeCtx(backend);
  const mounting = view.mount(mountPoint(), made.ctx);
  view.unmount(); await mounting;
  ok(backend.calls.every((c) => c.signal && c.signal.aborted), "read requests are aborted by unmount");
  eq(mountPoint().childElementCount, 0);
}

console.log(`System Configuration checks passed (${checks} assertions): registry vs backend source, type validation and bounds, exact PUT contract, save success/failure/ambiguous failure, stale values, read-only handling, lifecycle, route preservation.`);
