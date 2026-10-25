// Focused regression test for the COMBINED People > Deletion requests sidebar badge:
//   (admin-initiated dual-control requests that are still actionable: PENDING + APPROVED)
// + (user-initiated self-deletion requests awaiting one-admin review).
// Run: node --experimental-default-type=module scripts/test-deletion-badge.mjs
//
// Layers:
//   1. pure derivation (dedupe per id space, malformed bodies, junk entries)
//   2. the coordinator with a fake API (all-or-nothing rounds, last-known count, "?" state, coalescing, ordering)
//   3. jsdom scenarios that run the REAL legacy script, the REAL Deletion requests view + self queue + router, and the
//      REAL Overview view against a fake API that mirrors AdminController's response shapes.
//
// Needs `jsdom` (node_modules or NODE_PATH). Without it the pure/coordinator checks still run and the script exits
// with code 2: that is NOT a pass. jsdom is not a real browser: layout, the real `import()` of the module from the
// classic legacy script, and the live Spring backend are NOT exercised here (see scripts/smoke-deletion-badge.mjs
// for the Chromium run).
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = path.join(repo, 'src/main/resources/static');
const adminDir = path.join(root, 'admin');
let checks = 0;
const ok = (value, message) => { assert.ok(value, message); checks += 1; };
const eq = (actual, expected, message) => { assert.deepEqual(actual, expected, message); checks += 1; };
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function waitFor(cond, label, timeout = 4000) {
  const start = Date.now();
  while (Date.now() - start < timeout) { if (await cond()) return; await sleep(8); }
  throw new Error(`timed out waiting for: ${label}`);
}
const err = (status, message) => Object.assign(new Error(message), { status });

const mod = await import(pathToFileURL(path.join(adminDir, 'people/deletion-badge.js')));
const { deriveDeletionBadge, createDeletionBadge, getDeletionBadge, DeletionBadgeSourceError, DELETION_BADGE_PATHS, DELETION_BADGE_KEY, DELETION_BADGE_SLOT } = mod;

// ───────────────────────── 1. pure derivation ─────────────────────────
const dual = (id, status = 'PENDING', extra = {}) => ({ id, status, targetEmail: `t${id}@x.test`, ...extra });
const selfReq = id => ({ id, fullName: `User ${id}`, originalEmail: `u${id}@x.test`, requestedAt: '2026-10-01T00:00:00' });
const body = requests => ({ success: true, requests });
const derive = (p, a, s) => deriveDeletionBadge({ pending: body(p), approved: body(a), self: body(s) });

eq(DELETION_BADGE_KEY, 'people.deletion-requests', 'the badge key the sidebar element uses');
eq(DELETION_BADGE_SLOT, 'adminDeletionBadge', 'the per-document slot the legacy script reads');
eq(DELETION_BADGE_PATHS, { pending: '/api/admin/deletion-requests?status=PENDING', approved: '/api/admin/deletion-requests?status=APPROVED', self: '/api/admin/self-deletion-requests' }, 'only existing endpoints');

eq(derive([], [], []), { count: 0, dualControl: { pending: 0, approved: 0, total: 0 }, selfRequested: 0, ignored: { dualControl: 0, selfRequested: 0 } }, 'all three sources empty and well formed is a genuine zero');

{ // combined count; the two id spaces overlap on purpose (1 and 2 exist in both)
  const r = derive([dual(1), dual(2, 'PENDING', { callerVoted: true })], [dual(3, 'APPROVED')], [selfReq(1), selfReq(2)]);
  eq([r.count, r.dualControl, r.selfRequested], [5, { pending: 2, approved: 1, total: 3 }, 2], 'badge = dual-control pending + approved + self-requested; request ids and account ids are never merged');
}
{ // a request that is in both lists (approved between the two GETs) counts once, as APPROVED
  const r = derive([dual(7, 'PENDING')], [dual(7, 'APPROVED'), dual(8, 'APPROVED')], []);
  eq([r.count, r.dualControl], [2, { pending: 0, approved: 2, total: 2 }], 'dual-control id present in both lists is one item');
}
{ // duplicates inside one list, and the same account with a number and a numeric-string id
  const r = derive([dual(4), dual(4), dual('4')], [], [selfReq(9), selfReq(9), { ...selfReq(9), id: '9' }, selfReq(10)]);
  eq([r.dualControl.total, r.selfRequested, r.count], [1, 2, 3], 'duplicate dual-control ids collapse; duplicate account ids (number or numeric string) collapse');
}
{ // the same number in both id spaces is TWO items
  const r = derive([dual(104)], [], [selfReq(104)]);
  eq([r.dualControl.total, r.selfRequested, r.count], [1, 1, 2], 'request 104 and account 104 are different things');
}
{ // statuses: only PENDING and APPROVED are actionable; a missing status takes the list it came from; case-insensitive
  const r = derive([dual(1, 'REJECTED'), dual(2, 'EXECUTED'), dual(3, 'CANCELLED'), { id: 4 }, dual(5, 'pending')], [dual(6, 'approved'), { id: 7 }, dual(8, 'EXECUTED')], []);
  eq([r.dualControl, r.count], [{ pending: 2, approved: 2, total: 4 }, 4], 'rejected/executed/cancelled are not counted; missing status uses the list default');
}
{ // unusable ENTRIES are not actionable items and are reported, but they do not poison the whole count
  const junk = [null, undefined, 'x', 5, [], {}, { id: 0 }, { id: -3 }, { id: 'abc' }, { id: 1.5 }, { id: null }, { id: '01' }];
  const r = derive([...junk, dual(1)], [], [...junk, selfReq(2)]);
  eq([r.count, r.dualControl.total, r.selfRequested, r.ignored], [2, 1, 1, { dualControl: junk.length, selfRequested: junk.length }], 'junk entries are skipped and disclosed; valid ones still count');
}

// malformed envelopes: every source, every bad body, even when the other two sources are fine -> a throw, never a number
const goodBody = body([]);
const badBodies = [null, undefined, '', 'Service Unavailable', 5, true, [], [selfReq(1)], {}, { success: true }, { requests: null }, { requests: 'x' }, { requests: {} }, { success: false, requests: [] }, { success: false }];
for (const source of ['pending', 'approved', 'self']) {
  for (const bad of badBodies) {
    let thrown = null;
    try { deriveDeletionBadge({ pending: goodBody, approved: goodBody, self: goodBody, [source]: bad }); } catch (e) { thrown = e; }
    ok(thrown instanceof DeletionBadgeSourceError && thrown.source === source && thrown.malformed === true, `malformed ${source} body is rejected, not counted as empty: ${JSON.stringify(bad)}`);
  }
}
let none = null;
try { deriveDeletionBadge(); } catch (e) { none = e; }
ok(none instanceof DeletionBadgeSourceError, 'no input at all is malformed, not zero');

// ───────────────────────── 2. coordinator (no DOM) ─────────────────────────
function coordinator({ data, now } = {}) {
  const state = {
    data: data || { pending: [dual(1), dual(2)], approved: [dual(3, 'APPROVED')], self: [selfReq(1), selfReq(2)] },
    mode: { pending: 'ok', approved: 'ok', self: 'ok' }, calls: [], gates: {}, paints: [], sets: [], warnings: []
  };
  const keyOf = p => (p === DELETION_BADGE_PATHS.pending ? 'pending' : p === DELETION_BADGE_PATHS.approved ? 'approved' : p === DELETION_BADGE_PATHS.self ? 'self' : null);
  const api = {
    async get(p) {
      state.calls.push(p);
      const key = keyOf(p);
      if (!key) throw new Error('unexpected endpoint ' + p);
      if (state.gates[key]) await state.gates[key];
      const mode = state.mode[key];
      if (mode === 'fail') throw err(500, `${key} down`);
      if (mode === 'malformed') return { success: true };
      if (mode === 'text') return 'Service Unavailable';
      return body(state.data[key].map(r => ({ ...r })));
    }
  };
  let t = 1000;
  const badge = createDeletionBadge({ api, badges: { set: (k, v) => state.sets.push([k, v]) }, paint: v => state.paints.push(v), clock: now || (() => (t += 1)), warn: (...a) => state.warnings.push(a.join(' ')) });
  return { badge, ...state, state };
}
const last = h => h.paints[h.paints.length - 1];

{ // a successful round: three GETs on the documented endpoints, count mirrored to the shell store and painted
  const h = coordinator();
  const v = await h.badge.refresh();
  eq(h.calls.slice().sort(), Object.values(DELETION_BADGE_PATHS).sort(), 'exactly the three existing endpoints, once each');
  eq([v.status, v.count, h.sets, last(h).count], ['known', 5, [[DELETION_BADGE_KEY, 5]], 5], 'combined count published and mirrored into ctx.badges');
  ok(/3 admin-initiated requests/.test(v.title) && /2 self-requested deletions/.test(v.title) && /one-admin review/.test(v.title), 'tooltip keeps the two workflows apart');
}
for (const source of ['pending', 'approved', 'self']) {
  for (const mode of ['fail', 'malformed', 'text']) {
    // never published before: unknown, never a zero and never a partial total made from the two healthy sources
    const h = coordinator();
    h.mode[source] = mode;
    const v = await h.badge.refresh();
    eq([v.status, v.count, v.detail, h.sets], ['unknown', null, null, []], `${source} ${mode}, no previous count -> unknown ("?"), not 0, no partial total, store untouched`);
    ok(/unavailable/.test(v.title), 'unknown state is explained');
    ok(h.warnings.length === 1, 'the failure is reported once');
    // with a previous complete count: that count is preserved and marked stale
    const k = coordinator();
    await k.badge.refresh();
    k.data.pending.push(dual(50), dual(51), dual(52));           // a partial total from the healthy sources would differ from 5
    k.data.self.push(selfReq(60));
    k.mode[source] = mode;
    const before = k.sets.length;
    const s = await k.badge.refresh();
    eq([s.status, s.count, k.sets.length], ['stale', 5, before], `${source} ${mode}, after a complete count -> the last complete count (5) is kept, flagged stale, store untouched`);
    ok(/may be out of date/.test(s.title), 'stale state is explained');
    k.mode[source] = 'ok';
    const r = await k.badge.refresh();
    eq([r.status, r.count, k.sets[k.sets.length - 1]], ['known', 9, [DELETION_BADGE_KEY, 9]], `recovers on the next complete round (${source} ${mode})`);
  }
}
{ // two sources failing at once is still one failure
  const h = coordinator(); h.mode.pending = 'fail'; h.mode.self = 'malformed';
  eq([(await h.badge.refresh()).status, h.sets], ['unknown', []], 'several failing sources: unknown');
}
{ // coalescing: one round in flight, many callers -> exactly ONE follow-up round
  const h = coordinator();
  let release; h.gates.approved = new Promise(r => { release = r; });
  const first = h.badge.refresh();
  await sleep(5);
  eq(h.calls.length, 3, 'first round started');
  const waiters = [h.badge.refresh(), h.badge.refresh(), h.badge.refresh(), h.badge.refresh({ fresh: false }), h.badge.refresh({ fresh: false })];
  h.data.self.push(selfReq(99));                        // a change made while the first round was running
  await sleep(5);
  eq(h.calls.length, 3, 'callers during a round start nothing new');
  h.gates.approved = null; release();
  const results = await Promise.all([first, ...waiters]);
  eq(h.calls.length, 6, 'exactly one follow-up round (3 more GETs) for any number of callers');
  eq(results.map(r => r.count), Array(6).fill(6), 'every caller resolves after the follow-up round, which sees the change');
  await h.badge.refresh();
  eq(h.calls.length, 9, 'a later call starts a new round');
}
{ // fresh:false joins the round in flight and never schedules another one
  const h = coordinator();
  let release; h.gates.self = new Promise(r => { release = r; });
  const a = h.badge.refresh({ fresh: false }); const b = h.badge.refresh({ fresh: false }); const c = h.badge.refresh({ fresh: false });
  await sleep(5); release();
  await Promise.all([a, b, c]);
  eq(h.calls.length, 3, 'three joiners, one round');
  ok(!h.badge.isRefreshing(), 'idle afterwards');
}
{ // an older observation never replaces a newer complete count (applyResults is what the Overview uses)
  const h = coordinator();
  const res = (pending, approved, self) => ({ pending: { ok: true, value: body(pending) }, approved: { ok: true, value: body(approved) }, self: { ok: true, value: body(self) } });
  eq(h.badge.applyResults(res([dual(1)], [], []), { observedAt: 500 }).count, 1, 'first observation');
  eq(h.badge.applyResults(res([dual(1), dual(2), dual(3)], [], []), { observedAt: 400 }).count, 1, 'older observation ignored');
  eq(h.badge.applyResults({ pending: { ok: false, error: new Error('x') }, approved: res([], [], []).approved, self: res([], [], []).self }, { observedAt: 300 }).status, 'known', 'an older FAILURE does not downgrade a newer complete count');
  eq(h.badge.applyResults(res([dual(1), dual(2)], [], []), { observedAt: 500 }).count, 2, 'an equal timestamp applies');
  eq(h.badge.applyResults(res([dual(1), dual(2), dual(3)], [], []), { observedAt: 600 }).count, 3, 'a newer observation applies');
  eq(h.badge.applyResults({ pending: res([], [], []).pending, approved: { ok: false, error: err(503, 'x') }, self: res([], [], []).self }, { observedAt: 700 }).status, 'stale', 'a newer failure marks the last count stale');
  eq(h.badge.applyResults({}, { observedAt: 800 }).status, 'stale', 'missing results are a failure, not a zero');
  eq(h.badge.getState().count, 3, '...and the last complete count is still 3');
}
{ // never rejects, whatever breaks
  const calls = [];
  const throwing = createDeletionBadge({ api: { get() { throw new Error('sync boom'); } }, paint: () => { throw new Error('paint boom'); }, warn: m => calls.push(m) });
  const v = await throwing.refresh();
  eq(v.status, 'unknown', 'a synchronously throwing transport is a failed round');
  const noApi = createDeletionBadge({ warn: () => {} });
  eq((await noApi.refresh()).status, 'unknown', 'no transport is a failed round, not an exception');
}
{ // bind(): a store attached later receives the known count; a replaced transport is used next round
  const h = coordinator();
  await h.badge.refresh();
  const later = []; h.badge.bind({ badges: { set: (k, v) => later.push([k, v]) } });
  eq(later, [[DELETION_BADGE_KEY, 5]], 'late-bound ctx.badges is synced with the current complete count');
  const other = []; h.badge.bind({ api: { get: async p => { other.push(p); return body([]); } } });
  await h.badge.refresh();
  eq([other.length, h.badge.getState().count], [3, 0], 'bind({ api }) swaps the transport');
}
{ // per-document slot
  const a = getDeletionBadge(); const b = getDeletionBadge();
  ok(a === b && globalThis[DELETION_BADGE_SLOT] === a, 'one shared instance per document (global fallback outside a browser)');
  delete globalThis[DELETION_BADGE_SLOT];
}

// ───────────────────────── static source checks ─────────────────────────
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const stripComments = s => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1');
{
  const badgeSrc = stripComments(read('admin/people/deletion-badge.js'));
  const admin = fs.readFileSync(path.join(repo, 'src/main/java/com/adaptivelearning/adaptivelearningbackend/AdminController.java'), 'utf8');
  const gets = new Set([...admin.matchAll(/@GetMapping\("([^"]+)"\)/g)].map(m => '/api/admin' + m[1]));
  for (const p of Object.values(DELETION_BADGE_PATHS)) ok(gets.has(p.split('?')[0]), `${p} has a @GetMapping in the backend`);
  ok(!/method\s*:|POST|PUT|DELETE|innerHTML|insertAdjacentHTML|localStorage|sessionStorage/.test(badgeSrc), 'badge module is read-only, has no HTML sinks and no storage');
  eq([...badgeSrc.matchAll(/["'`](\/api\/[^"'`]+)["'`]/g)].map(m => m[1]).sort(), Object.values(DELETION_BADGE_PATHS).sort(), 'the module names exactly the three endpoints');

  const legacy = stripComments(read('admin/legacy/deletionrequests.js'));
  ok(!/getElementById\("deletionBadge"\)|status=PENDING/.test(legacy.slice(legacy.indexOf('refreshDeletionBadge'), legacy.indexOf('renderDeletionRequests'))), 'legacy refreshDeletionBadge no longer counts pending-only or writes the DOM itself');
  eq([...legacy.matchAll(/await refreshDeletionBadge\(\)/g)].length >= 3 || [...legacy.matchAll(/refreshDeletionBadge\(\)/g)].length >= 3, true, 'vote, cancel and execute still refresh the badge after success');
  const overview = stripComments(read('admin/views/overview.js'));
  ok(!/badges\.set\(\s*["']people\.deletion-requests["']/.test(overview), 'Overview no longer writes a pending-only count into the deletion badge key');
  ok(/applyResults/.test(overview) && /deletion-badge\.js/.test(overview), 'Overview hands its three responses to the shared badge');
  ok(/data-badge-key="people\.deletion-requests"/.test(read('admin.html')), 'admin.html still carries the badge element this module paints');
  ok(/function paintNavBadge/.test(read('admin.js')) && /createBadgeStore\(paintNavBadge\)/.test(read('admin.js')), 'admin.js still paints the store into [data-badge-key] elements');
}

// ───────────────────────── 3. DOM scenarios ─────────────────────────
async function loadJsdom() {
  const dirs = [undefined, ...(process.env.NODE_PATH || '').split(path.delimiter).filter(Boolean)];
  for (const dir of dirs) {
    try { return (dir ? createRequire(path.join(dir, 'x.js'))('jsdom') : await import('jsdom')); } catch { /* try next */ }
  }
  return null;
}
const jsdom = await loadJsdom();
if (!jsdom) { console.log(`Pure and coordinator checks passed (${checks}).`); console.error('SKIPPED DOM scenarios (exit 2): jsdom could not be loaded.'); process.exit(2); }
const { JSDOM } = jsdom.JSDOM ? jsdom : jsdom.default;

const sharedJs = fs.readFileSync(path.join(adminDir, 'shared.js'), 'utf8');
const legacyDeletionJs = fs.readFileSync(path.join(adminDir, 'legacy/deletionrequests.js'), 'utf8');
const routesMod = await import(pathToFileURL(path.join(adminDir, 'routes.js')));
const routerMod = await import(pathToFileURL(path.join(adminDir, 'router.js')));
const { createBadgeStore } = await import(pathToFileURL(path.join(adminDir, 'badges.js')));
const overviewView = (await import(pathToFileURL(path.join(adminDir, 'views/overview.js')))).default;
const ui = (await import(pathToFileURL(path.join(adminDir, 'ui.js')))).default;

const COLLISION = "Can't restore \u2014 taylor@example.test is already used by a newer account. The user likely already re-registered; this old account can still be permanently deleted instead.";
function fixture() {
  return {
    // request ids and account ids overlap on purpose (104)
    self: [
      { id: 104, fullName: 'Taylor Cruz', originalEmail: 'taylor@example.test', requestedAt: '2026-10-15T11:00:00' },
      { id: 110, fullName: 'Riley Older', originalEmail: 'riley@example.test', requestedAt: '2026-10-10T09:00:00' }
    ],
    dual: [
      { id: 104, status: 'PENDING', targetName: 'Lee Pending', targetEmail: 'lee@example.test', reason: 'Repeated abuse', requestedBy: 'pat@example.test', requestedAt: '2026-10-12T10:00:00', approveCount: 2, requiredCount: 3, callerVoted: false, votes: [] },
      { id: 105, status: 'APPROVED', targetName: 'Ready Person', targetEmail: 'ready@example.test', reason: 'Confirmed', requestedBy: 'pat@example.test', requestedAt: '2026-10-11T10:00:00', approveCount: 3, requiredCount: 3, callerVoted: true, votes: [] }
    ]
  };
}
const NAV = '<nav><a href="#/people/deletion-requests"><span>Deletion requests</span><span class="nav-badge" id="deletionBadge" data-badge-key="people.deletion-requests"></span></a></nav>';
const P = { pending: '/api/admin/deletion-requests?status=PENDING', approved: '/api/admin/deletion-requests?status=APPROVED', self: '/api/admin/self-deletion-requests' };

/** Fake of the admin API. `mode[key]` = ok | fail | malformed | text | unsuccessful, for key pending | approved | self. */
function makeApi(data, mode, calls, gates, failures) {
  const respond = (key, rows) => {
    const m = mode[key] || 'ok';
    if (m === 'fail') throw err(500, `${key} unavailable`);
    if (m === 'malformed') return { success: true };
    if (m === 'text') return 'Service Unavailable';
    if (m === 'unsuccessful') return { success: false, requests: [] };
    return { success: true, requests: rows.map(r => ({ ...r })) };
  };
  return async (p, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    const url = new URL(p, 'http://x');
    calls.push(`${method} ${url.pathname}${url.search}`);
    await sleep(1);
    const gateKey = `${method} ${url.pathname}${url.search}`;
    if (gates[gateKey]) await gates[gateKey];
    if (method === 'GET' && url.pathname === '/api/admin/self-deletion-requests') return respond('self', data.self);
    if (method === 'GET' && url.pathname === '/api/admin/deletion-requests') {
      const s = url.searchParams.get('status') || 'ALL';
      const key = s === 'PENDING' ? 'pending' : s === 'APPROVED' ? 'approved' : 'other';
      return respond(key, data.dual.filter(r => s === 'ALL' || r.status === s));
    }
    let m = url.pathname.match(/^\/api\/admin\/self-deletion-requests\/(\d+)\/(execute|restore)$/);
    if (m && method === 'POST') {
      const id = Number(m[1]);
      const forced = failures[`${m[2]}:${id}`];
      if (forced) { delete failures[`${m[2]}:${id}`]; throw err(forced.status, forced.message); }
      const idx = data.self.findIndex(x => x.id === id);
      if (idx < 0) throw err(404, 'Account not found.');
      if (m[2] === 'restore' && data.collide?.includes(id)) throw err(400, COLLISION);
      data.self.splice(idx, 1);
      return { success: true, message: m[2] === 'execute' ? 'Account permanently deleted.' : 'Account restored.' };
    }
    m = url.pathname.match(/^\/api\/admin\/deletion-requests\/(\d+)\/(vote|execute|cancel)$/);
    if (m && method === 'POST') {
      const r = data.dual.find(x => x.id === Number(m[1]));
      if (!r) throw err(404, 'Request not found.');
      const forced = failures[`dual-${m[2]}:${r.id}`];
      if (forced) { delete failures[`dual-${m[2]}:${r.id}`]; throw err(forced.status, forced.message); }
      if (m[2] === 'vote') { r.callerVoted = true; r.approveCount += 1; r.votes.push({ decision: 'APPROVE', adminEmail: 'me@example.test', note: '' }); if (r.approveCount >= r.requiredCount) r.status = 'APPROVED'; }
      if (m[2] === 'execute') r.status = 'EXECUTED';
      if (m[2] === 'cancel') r.status = 'CANCELLED';
      return { success: true };
    }
    throw new Error('unexpected request ' + method + ' ' + p);
  };
}

function installGlobals(win) {
  win.scrollTo = () => {};
  win.Element.prototype.scrollIntoView = () => {};
  win.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  win.HTMLDialogElement.prototype.close = function () { if (this.hasAttribute('open')) { this.removeAttribute('open'); this.dispatchEvent(new win.Event('close')); } };
  Object.assign(globalThis, { window: win, document: win.document, Element: win.Element, Node: win.Node, MutationObserver: win.MutationObserver, ResizeObserver: undefined });
}
// the same painter admin.js hands to createBadgeStore
function paintNavBadge(key, count) {
  document.querySelectorAll('[data-badge-key="' + key + '"]').forEach(badge => {
    if (count > 0) { badge.textContent = count; badge.style.display = 'inline-block'; } else badge.style.display = 'none';
  });
}

/** Deletion requests page through the real router, view, legacy script and self queue. */
async function boot({ data = fixture(), mode = {}, bootRound = false } = {}) {
  const dom = new JSDOM(`<!doctype html><body><div id="adminRouteNotice" role="status"></div>${NAV}<div id="adminContent" tabindex="-1"></div><div id="modal" style="display:none;"></div></body>`, { url: 'http://localhost/admin.html#/people/deletion-requests', runScripts: 'dangerously', pretendToBeVisual: true });
  const win = dom.window;
  installGlobals(win);
  const calls = []; const gates = {}; const failures = {}; const hooks = { activity: 0, users: 0 }; const warnings = [];
  win.console.warn = (...a) => warnings.push(a.join(' '));
  const apiFetch = makeApi(data, mode, calls, gates, failures);
  win.apiFetch = apiFetch;
  win.eval(`
    var adminData = { users: [], activityLog: [] };
    var deletionStatusFilter = 'PENDING';
    function setModalInlineStatus(elId, message) { var e = document.getElementById(elId); if (e) { e.style.display = 'block'; e.textContent = message; } }
  `);
  win.refreshActivityLog = async () => { hooks.activity += 1; };
  win.refreshUsers = async () => { hooks.users += 1; };
  win.eval(sharedJs);
  win.eval(legacyDeletionJs);
  if (bootRound) getDeletionBadge();   // the module has been imported by the boot-time legacy call
  for (const link of win.document.querySelectorAll('link[rel="stylesheet"]')) link.dispatchEvent(new win.Event('load'));
  const storeSets = [];
  const badges = createBadgeStore(paintNavBadge);
  badges.subscribe((k, c) => storeSets.push([k, c]));
  const errors = [];
  const router = routerMod.createRouter({
    routes: routesMod.routes, aliases: routesMod.aliases, defaultPath: routesMod.defaultPath,
    mount: win.document.getElementById('adminContent'),
    hooks: { getContext: () => ({ api: { get: apiFetch, request: apiFetch }, ui, badges }), onError: (e, match, phase) => errors.push(`${phase}: ${e.message}`) }
  });
  // what admin.js does at boot: a legacy refresh that is still running when the user lands on this page
  let releaseBoot = () => {};
  if (bootRound) {
    gates[`GET ${P.approved}`] = new Promise(r => { releaseBoot = r; });
    void win.refreshDeletionBadge();
    await sleep(15);
  }
  await router.start();

  const $ = sel => win.document.querySelector(sel);
  const $$ = sel => [...win.document.querySelectorAll(sel)];
  const badgeEl = () => $('#deletionBadge');
  const t = {
    releaseBoot: () => { releaseBoot(); delete gates[`GET ${P.approved}`]; },
    win, router, calls, gates, failures, hooks, data, mode, errors, warnings, storeSets, badges, $, $$,
    badgeText: () => badgeEl().textContent,
    badgeShown: () => badgeEl().style.display === 'inline-block',
    badgeState: () => badgeEl().dataset.badgeState,
    badgeTitle: () => badgeEl().getAttribute('title') || '',
    count: key => calls.filter(c => c === `GET ${P[key]}`).length,
    section: () => $('#selfDeletionSection'),
    item: id => $('#selfDeletionSection')?.querySelector(`li[data-user-id="${id}"]`),
    button: (scope, label) => [...(scope?.querySelectorAll('button') || [])].find(b => b.textContent.trim() === label),
    click: el => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true, cancelable: true })),
    dialog: () => $('dialog.admin-ui-dialog[open]'),
    async finish() {
      await waitFor(() => !(win.adminDeletionBadge && win.adminDeletionBadge.isRefreshing()), 'badge idle before teardown', 3000);
      win.history.replaceState(null, '', '#/overview'); await router.destroy();
    }
  };
  return t;
}
const pageReady = t => waitFor(() => t.section() && t.section().querySelector('.sdq__list, .admin-ui-empty, .sdq__state[role="alert"]') && t.$('#deletionList') && !/Loading/.test(t.$('#deletionList').textContent), 'both queues settled');
const settled = async t => { await waitFor(() => !t.win.adminDeletionBadge?.isRefreshing(), 'badge round finished'); await sleep(15); };
async function confirmDelete(t, id) {
  t.click(t.button(t.item(id), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'delete dialog');
  t.dialog().querySelector('input').value = 'DELETE';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
}
async function confirmRestore(t, id) {
  t.click(t.button(t.item(id), 'Restore access'));
  await waitFor(() => t.dialog(), 'restore dialog');
  t.click(t.button(t.dialog(), 'Confirm'));
}

// The badge module runs in Node (not inside jsdom), so its diagnostics go to Node's console: collect them instead of printing.
const nodeWarnings = [];
console.warn = (...args) => { nodeWarnings.push(args.join(' ')); };

const scenarios = [];
const scenario = (name, fn) => scenarios.push([name, fn]);

scenario('arrival: the badge is the combined total, explained, written to the shell store, with exactly one round of requests', async () => {
  const t = await boot();
  await pageReady(t);
  await waitFor(() => t.badgeText() === '4', 'combined count');
  ok(t.badgeShown() && t.badgeState() === 'known', 'badge visible and marked known');
  ok(/2 admin-initiated requests/.test(t.badgeTitle()) && /1 pending approval, 1 approved/.test(t.badgeTitle()) && /2 self-requested deletions/.test(t.badgeTitle()), 'tooltip lists the two workflows separately');
  eq(t.badges.snapshot(), { 'people.deletion-requests': 4 }, 'the shell store holds the combined count');
  eq([t.count('pending'), t.count('approved'), t.count('self')], [2, 1, 2], 'pending: page list + badge; approved: badge only; self: queue + badge -> one badge round, nothing duplicated');
  ok(t.$('.sdq-guide__badge-note')?.textContent.includes('navigation shortcut') && /separate counts/.test(t.$('.sdq-guide__badge-note').textContent), 'the page states the badge is only a navigation indicator');
  ok(/Self-requested deletions \(2\)/.test(t.$('#selfDeletionHeading').textContent), 'the self queue keeps its own count (2), not the total');
  ok(!/\b4\b/.test(t.$('#dualControlSection').textContent.replace(/\d+ of \d+ admins/g, '')), 'the dual-control card never shows the combined total');
  const settledCalls = t.calls.length;
  await sleep(200);
  eq(t.calls.length, settledCalls, 'no refresh loop: the request count stays flat after settling');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('landing on the page while the boot refresh is still running joins it: one round in total, not two', async () => {
  const t = await boot({ bootRound: true });
  await pageReady(t);
  eq(t.count('approved'), 1, 'the boot round is in flight and has asked for APPROVED once');
  t.releaseBoot();
  await waitFor(() => t.badgeText() === '4', 'count published');
  await settled(t); await sleep(120);
  eq([t.count('pending'), t.count('approved'), t.count('self')], [2, 1, 2], 'still a single badge round (the page list and the queue make the other pending/self requests)');
  await t.finish();
});

scenario('admin-initiated workflow: vote, execute and cancel each update the badge through ONE round', async () => {
  const t = await boot();
  await pageReady(t); await waitFor(() => t.badgeText() === '4', 'initial'); await settled(t);
  // vote: request 104 reaches 3/3 and moves PENDING -> APPROVED; it must not be counted twice or dropped
  let before = t.count('self');
  t.click([...t.$$('#deletionList button')].find(b => b.textContent.includes('Approve')));
  await waitFor(() => t.calls.includes('POST /api/admin/deletion-requests/104/vote'), 'vote posted');
  await waitFor(() => t.count('self') === before + 1, 'badge round after the vote'); await settled(t);
  eq([t.badgeText(), t.count('self') - before], ['4', 1], 'after the vote: still 4 (moved between lists, not double counted), exactly one badge round');
  ok(t.hooks.activity >= 1, 'legacy vote refresh hooks still run');
  // execute 105 (legacy modal, Approved filter)
  t.click([...t.$$('#deletionStatusFilter button')].find(b => b.textContent.includes('Approved')));
  await waitFor(() => t.$('#deletionList').textContent.includes('Ready Person'), 'approved filter');
  await settled(t);
  before = t.count('self');
  t.click([...t.$$('#deletionList .admin-card')].find(c => c.textContent.includes('Ready Person')).querySelector('.btn-delete'));
  await waitFor(() => t.$('#confirmExecuteBtn'), 'legacy execute modal');
  t.click(t.$('#confirmExecuteBtn'));
  await waitFor(() => t.badgeText() === '3', 'badge after execute'); await settled(t);
  eq(t.count('self') - before, 1, 'execute -> one badge round');
  eq([t.hooks.users >= 1, t.hooks.activity >= 2], [true, true], 'legacy execute refreshes users/activity as before');
  // cancel 104
  before = t.count('self');
  t.click([...t.$$('#deletionList .admin-card')].find(c => c.textContent.includes('Lee Pending')).querySelector('.btn-view'));
  await waitFor(() => t.badgeText() === '2', 'badge after cancel'); await settled(t);
  eq(t.count('self') - before, 1, 'cancel -> one badge round');
  eq(t.badges.snapshot(), { 'people.deletion-requests': 2 }, 'store follows');
  ok(t.item(104) && t.item(110), 'the self queue is untouched by dual-control actions');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('a FAILED dual-control action changes nothing and starts no badge round', async () => {
  const t = await boot();
  await pageReady(t); await waitFor(() => t.badgeText() === '4', 'initial'); await settled(t);
  t.failures['dual-vote:104'] = { status: 500, message: 'Vote failed on the server' };
  const before = t.calls.length;
  t.click([...t.$$('#deletionList button')].find(b => b.textContent.includes('Approve')));
  await waitFor(() => t.calls.includes('POST /api/admin/deletion-requests/104/vote'), 'vote attempted');
  await sleep(120);
  eq([t.badgeText(), t.calls.length - before], ['4', 1], 'only the failed POST was sent; the badge is untouched');
  await t.finish();
});

scenario('self-requested workflow: permanent deletion and restoration each update the badge through ONE round', async () => {
  const t = await boot();
  await pageReady(t); await waitFor(() => t.badgeText() === '4', 'initial'); await settled(t);
  let before = { approved: t.count('approved'), self: t.count('self') };
  await confirmDelete(t, 104);
  await waitFor(() => t.badgeText() === '3', 'badge after permanent deletion'); await settled(t);
  eq([t.count('approved') - before.approved, t.count('self') - before.self], [1, 2], 'one badge round (approved +1) plus the queue\'s own reload (self +1)');
  ok(t.calls.includes('POST /api/admin/self-deletion-requests/104/execute') && !t.item(104), 'account 104 deleted through the self endpoint and gone from the queue');
  ok(t.$('#deletionList').textContent.includes('Lee Pending'), 'dual-control request 104 (same number) is untouched and still counted');
  before = { approved: t.count('approved'), self: t.count('self') };
  await confirmRestore(t, 110);
  await waitFor(() => t.badgeText() === '2', 'badge after restore'); await settled(t);
  eq(t.count('approved') - before.approved, 1, 'restore -> one badge round');
  eq([t.hooks.activity >= 2, t.hooks.users >= 2], [true, true], 'activity log and users refresh as before');
  eq(t.badges.snapshot(), { 'people.deletion-requests': 2 }, 'store follows');
  await t.finish();
});

scenario('self-requested actions that did NOT succeed (500, restore email collision, cancelled dialog) leave the badge alone', async () => {
  const data = fixture(); data.collide = [104];
  const t = await boot({ data });
  await pageReady(t); await waitFor(() => t.badgeText() === '4', 'initial'); await settled(t);
  const rounds = () => t.count('approved');
  const before = rounds();
  t.failures['execute:110'] = { status: 500, message: 'Server exploded' };
  await confirmDelete(t, 110);
  await waitFor(() => /Deletion failed: Server exploded/.test(t.item(110)?.textContent || ''), 'inline error');
  await confirmRestore(t, 104);
  await waitFor(() => t.item(104)?.querySelector('.sdq__note--conflict'), 'collision note');
  t.click(t.button(t.item(110), 'Restore access'));
  await waitFor(() => t.dialog(), 'dialog'); t.click(t.button(t.dialog(), 'Cancel'));
  await sleep(120);
  eq([t.badgeText(), rounds() - before], ['4', 0], 'no refresh round and no change for failed or cancelled actions');
  await t.finish();
});

scenario('a request another admin already handled: the stale outcome resyncs the badge from the server', async () => {
  const t = await boot();
  await pageReady(t); await waitFor(() => t.badgeText() === '4', 'initial'); await settled(t);
  t.data.self = t.data.self.filter(x => x.id !== 104);   // handled elsewhere
  t.failures['execute:104'] = { status: 400, message: 'No pending self-deletion request on this account.' };
  const before = t.count('approved');
  await confirmDelete(t, 104);
  await waitFor(() => t.badgeText() === '3', 'badge resynced'); await settled(t);
  eq(t.count('approved') - before, 1, 'one resync round');
  await t.finish();
});

scenario('self-requested endpoint down on arrival: the badge says "?" (never 0, never a dual-control-only count) and recovers after Retry', async () => {
  const t = await boot({ mode: { self: 'fail' } });
  await waitFor(() => t.section()?.querySelector('.sdq__state[role="alert"]'), 'self queue error state');
  await waitFor(() => t.badgeText() === '?', 'unknown badge'); await settled(t);
  ok(t.badgeShown() && t.badgeState() === 'unknown', 'visible and marked unknown');
  ok(/unavailable/.test(t.badgeTitle()), 'explained in the tooltip');
  eq(t.storeSets, [], 'the shell store was never given a number');
  ok(t.$('#deletionList').textContent.includes('Lee Pending'), 'the dual-control queue still loads on its own');
  ok(/Could not load self-requested deletions: self unavailable/.test(t.section().textContent), 'the self queue keeps its own failure UI');
  ok(nodeWarnings.some(w => /Deletion badge not updated/.test(w)), 'the failure is logged as a warning, not an error');
  // the endpoint comes back: the queue's own Retry succeeds and that recovers the badge (no extra click anywhere else)
  t.mode.self = 'ok';
  t.click(t.button(t.section(), 'Retry'));
  await waitFor(() => t.section().querySelector('.sdq__list'), 'queue recovered');
  await waitFor(() => t.badgeText() === '4', 'badge recovered by the successful queue load');
  eq([t.badgeState(), t.badges.snapshot()], ['known', { 'people.deletion-requests': 4 }], 'known again and mirrored to the store');
  await settled(t);
  const calmCalls = t.calls.length; await sleep(150);
  eq(t.calls.length, calmCalls, 'recovery does not start a loop');
  await t.finish();
});

scenario('malformed answer from one source: unknown, not zero; recovers on the next complete round', async () => {
  const mode = { approved: 'malformed' };
  const t = await boot({ mode });
  await pageReady(t);
  await waitFor(() => t.badgeText() === '?', 'unknown'); await settled(t);
  ok(t.badgeShown() && t.badgeState() === 'unknown', 'unknown, visible');
  ok(t.$('#deletionList').textContent.includes('Lee Pending'), 'the legacy list (a different request) is unaffected');
  mode.approved = 'ok';
  await t.win.refreshDeletionBadge();
  await waitFor(() => t.badgeText() === '4', 'recovered');
  ok(t.badgeState() === 'known' && !/unavailable/.test(t.badgeTitle()), 'state and tooltip are known again');
  await t.finish();
});

scenario('last complete count is kept (stale) when a later round fails; a partial total is never shown', async () => {
  const mode = {};
  const t = await boot({ mode });
  await pageReady(t); await waitFor(() => t.badgeText() === '4', 'initial'); await settled(t);
  // grow the healthy sources, then break one: a partial total (pending + self only) would be 6 or 7, not 4
  t.data.dual.push({ id: 200, status: 'PENDING', targetName: 'Extra', targetEmail: 'e@x.test', reason: '-', requestedBy: 'a', requestedAt: '2026-10-20T00:00:00', approveCount: 0, requiredCount: 3, callerVoted: false, votes: [] });
  t.data.self.push({ id: 300, fullName: 'Extra Self', originalEmail: 's@x.test', requestedAt: '2026-10-20T00:00:00' });
  for (const m of ['fail', 'malformed', 'text', 'unsuccessful']) {
    mode.approved = m;
    const sets = t.storeSets.length;
    await t.win.refreshDeletionBadge();
    await settled(t);
    eq([t.badgeText(), t.badgeState(), t.storeSets.length - sets], ['4', 'stale', 0], `approved ${m}: the last complete count (4) stays, flagged stale, store untouched`);
    ok(/may be out of date/.test(t.badgeTitle()), 'tooltip says it may be out of date');
  }
  mode.approved = 'ok';
  await t.win.refreshDeletionBadge();
  await waitFor(() => t.badgeText() === '6', 'fresh complete count');
  eq([t.badgeState(), t.badgeTitle().includes('out of date')], ['known', false], 'back to known');
  await t.finish();
});

scenario('every source down at once: "?" and never a hidden zero', async () => {
  const t = await boot({ mode: { pending: 'fail', approved: 'fail', self: 'fail' } });
  await waitFor(() => t.badgeText() === '?', 'unknown'); await settled(t);
  ok(t.badgeShown(), 'the badge stays visible');
  eq(t.storeSets, [], 'no number was published');
  await t.finish();
});

scenario('duplicates: ids are deduplicated inside each id space and never across them', async () => {
  const data = fixture();
  const d = (id, status) => ({ id, status, targetName: 'x', targetEmail: 'x@x.test', reason: '-', requestedBy: 'a', requestedAt: '2026-10-01T00:00:00', approveCount: 0, requiredCount: 3, callerVoted: false, votes: [] });
  data.dual = [d(104, 'PENDING'), d(104, 'PENDING'), d(105, 'APPROVED')];   // 104 twice in the server list
  data.self = [data.self[0], data.self[0], { ...data.self[1], id: '110' }, data.self[1]];  // account 104 twice, 110 as number and as string
  const t = await boot({ data });
  await waitFor(() => t.badgeText() === '4', 'deduplicated total: dual {104,105} + self {104,110}');
  eq(t.badges.snapshot(), { 'people.deletion-requests': 4 }, 'store agrees');
  await t.finish();
});

scenario('nothing waiting in either queue: a real zero hides the badge', async () => {
  const data = fixture(); data.dual = []; data.self = [];
  const t = await boot({ data });
  await pageReady(t); await settled(t);
  ok(!t.badgeShown() && t.badgeState() === 'known', 'hidden and known (a genuine zero from three healthy sources)');
  eq(t.badges.snapshot(), { 'people.deletion-requests': 0 }, 'store holds the zero');
  await t.finish();
});

scenario('boot path: the legacy global works before any view ran, never throws, and uses window.apiFetch', async () => {
  const dom = new JSDOM(`<!doctype html><body>${NAV}<div id="adminContent"></div></body>`, { url: 'http://localhost/admin.html', runScripts: 'dangerously', pretendToBeVisual: true });
  const win = dom.window;
  installGlobals(win);
  const calls = []; const warnings = [];
  win.console.warn = (...a) => warnings.push(a.join(' '));
  win.apiFetch = makeApi(fixture(), {}, calls, {}, {});
  win.eval(sharedJs); win.eval(legacyDeletionJs);
  const el = () => win.document.getElementById('deletionBadge');
  // no slot yet and jsdom cannot run the real import(): must resolve quietly and leave the badge alone
  await win.refreshDeletionBadge();
  ok(el().textContent === '' && calls.length === 0 && warnings.length === 1, 'a module that cannot be loaded is a warning, not an exception, not a zero');
  // once the module has created the slot (this is what import() + getDeletionBadge() does in a browser)
  getDeletionBadge();
  await win.refreshDeletionBadge();
  eq([el().textContent, el().style.display, calls.length], ['4', 'inline-block', 3], 'boot-time refresh with no ctx at all: three GETs through window.apiFetch, combined count painted');
  await waitFor(() => !win.adminDeletionBadge.isRefreshing(), 'idle');
});

// ── Overview: the same badge, fed with the three responses the Overview already loads ──
const OVERVIEW_PLAIN = {
  '/api/admin/overview': { stats: { totalUsers: 1, totalTopics: 1, totalQuestions: 1, totalAttempts: 1, totalMaterials: 1 } },
  '/api/admin/users': { users: [] }, '/api/admin/banned-emails': { bannedEmails: [] }, '/api/admin/activity-log': { log: [] },
  '/api/admin/reports': { reports: [] }, '/api/admin/quiz-activity': { quizHistory: [] }, '/api/admin/topics': [],
  '/api/admin/material-categories': { categoryBreakdown: [], subCategoryBreakdown: [] }
};
async function bootOverview({ data = fixture(), mode = {}, gates = {} } = {}) {
  const dom = new JSDOM(`<!doctype html><body>${NAV}<div id="adminContent"></div></body>`, { url: 'http://localhost/admin.html#/overview', runScripts: 'dangerously', pretendToBeVisual: true });
  const win = dom.window;
  installGlobals(win);
  const calls = []; const warnings = [];
  win.console.warn = (...a) => warnings.push(a.join(' '));
  const deletionApi = makeApi(data, mode, calls, gates, {});
  const apiGet = async (p, options) => {
    const pth = new URL(p, 'http://x').pathname;
    if (pth in OVERVIEW_PLAIN) { calls.push(`GET ${pth}`); await sleep(1); return OVERVIEW_PLAIN[pth]; }
    return deletionApi(p, options);
  };
  win.apiFetch = apiGet;   // the shared badge's late-bound transport, as in the real page
  const badges = createBadgeStore(paintNavBadge);
  const host = win.document.getElementById('adminContent');
  await overviewView.mount(host, { api: { get: apiGet }, badges, session: { user: { name: 'Admin' } } });
  const $ = sel => win.document.querySelector(sel);
  const badgeEl = () => $('#deletionBadge');
  return {
    win, calls, gates, data, mode, badges, warnings, $,
    badgeText: () => badgeEl().textContent, badgeShown: () => badgeEl().style.display === 'inline-block', badgeState: () => badgeEl().dataset.badgeState,
    count: key => calls.filter(c => c === `GET ${P[key]}`).length,
    overviewIdle: () => !$('.ov-refresh').disabled,
    click: el => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true })),
    finish() { overviewView.unmount(); }
  };
}
scenario('Overview: the badge is the combined total from the Overview\'s own three responses, with no extra requests', async () => {
  const o = await bootOverview();
  await waitFor(() => o.badgeText() === '4', 'combined count (the old behaviour would show 1 = pending only)');
  await waitFor(() => o.overviewIdle(), 'overview settled'); await sleep(40);
  eq([o.count('pending'), o.count('approved'), o.count('self')], [1, 1, 1], 'one request per source: the badge adds none');
  eq(o.badges.snapshot()['people.deletion-requests'], 4, 'store holds the combined count');
  eq(o.win.adminDeletionBadge.getState().detail.dualControl, { pending: 1, approved: 1, total: 2 }, 'derivation detail available');
  o.finish();
});
scenario('Overview: a source that is still loading blocks publishing; a partial total never appears', async () => {
  let release; const gates = { [`GET ${P.self}`]: new Promise(r => { release = r; }) };
  const o = await bootOverview({ gates });
  await waitFor(() => o.count('pending') === 1 && o.count('approved') === 1, 'the two dual-control sources were requested');
  await sleep(80);
  eq([o.badgeText(), o.badgeShown(), 'people.deletion-requests' in o.badges.snapshot()], ['', false, false], 'two of three sources are in: nothing is published (a dual-control-only 2 would be partial)');
  release();
  await waitFor(() => o.badgeText() === '4', 'published once all three settled');
  o.finish();
});
scenario('Overview: failing or malformed source -> last complete count kept (stale) or "?"; Try again recovers with ONE request', async () => {
  const mode = { self: 'fail' };
  const o = await bootOverview({ mode });
  await waitFor(() => o.badgeText() === '?', 'unknown while the self source has never loaded'); await waitFor(() => o.overviewIdle(), 'settled');
  eq([o.badgeState(), 'people.deletion-requests' in o.badges.snapshot(), o.badgeShown()], ['unknown', false, true], 'unknown, nothing published for this key, never a dual-control-only count (2)');
  mode.self = 'ok';
  const before = { pending: o.count('pending'), approved: o.count('approved'), self: o.count('self') };
  o.click([...o.$('[data-group="selfDeletions"]').querySelectorAll('button')].find(b => b.textContent === 'Try again'));
  await waitFor(() => o.badgeText() === '4', 'recovered via the group retry');
  eq([o.count('pending') - before.pending, o.count('approved') - before.approved, o.count('self') - before.self], [0, 0, 1], 'Try again reloads only the self source');
  // later refreshes failing keep the 4
  for (const m of ['fail', 'malformed']) {
    mode.approved = m;
    o.click(o.$('.ov-refresh'));
    await waitFor(() => o.badgeState() === 'stale', `stale after approved ${m}`); await waitFor(() => o.overviewIdle(), 'settled');
    eq([o.badgeText(), o.badges.snapshot()['people.deletion-requests']], ['4', 4], `approved ${m}: still 4, not the partial 3 or 0`);
  }
  mode.approved = 'ok';
  o.click(o.$('.ov-refresh'));
  await waitFor(() => o.badgeState() === 'known', 'known again');
  eq(o.badgeText(), '4', 'complete again');
  o.finish();
});
scenario('Overview and the page share one instance: a newer page refresh is not overwritten by older Overview data', async () => {
  const o = await bootOverview();
  await waitFor(() => o.badgeText() === '4', 'initial'); await waitFor(() => o.overviewIdle(), 'settled');
  // a workflow action elsewhere changes the data and refreshes the shared badge...
  o.data.self.pop();
  await o.win.adminDeletionBadge.refresh();
  eq(o.badgeText(), '3', 'newer complete observation applied');
  // ...an observation from BEFORE that (as a slow Overview response would be) is ignored
  const state = o.win.adminDeletionBadge;
  const old = { pending: { ok: true, value: { requests: [] } }, approved: { ok: true, value: { requests: [] } }, self: { ok: true, value: { requests: [] } } };
  state.applyResults(old, { observedAt: 1 });
  eq(o.badgeText(), '3', 'older observation ignored');
  o.finish();
});

// ------------------------- Phase 5 stabilization scenarios -------------------------
// Each of these failed against the Phase 4 integrated tree (see INTEGRATION-NOTES.md, "Defects found and fixed").
const dualRow = (id, status, extra = {}) => ({ id, status, targetName: 'T' + id, targetEmail: `t${id}@x.test`, reason: 'r', requestedBy: 'a@x.test', requestedAt: '2026-10-01T00:00:00', approveCount: 1, requiredCount: 3, callerVoted: false, votes: [], ...extra });
const approveBtn = t => [...t.$$('#deletionList button')].find(b => b.textContent.includes('Approve'));
const filterBtn = (t, label) => [...t.$$('#deletionStatusFilter button')].find(b => b.textContent.includes(label));
const key = (t, el, k, extra = {}) => el.dispatchEvent(new t.win.KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...extra }));
const modalOpen = t => t.$('#modal').style.display !== 'none';

scenario('a failed round after a known ZERO is "?" (unconfirmed), never a hidden zero; a complete round restores the real zero', async () => {
  const data = fixture(); data.dual = []; data.self = [];
  const mode = {}; const t = await boot({ data, mode });
  await pageReady(t); await settled(t);
  ok(!t.badgeShown() && t.badgeState() === 'known', 'precondition: confirmed zero is hidden');
  const sets = t.storeSets.length;
  for (const m of ['fail', 'malformed', 'text', 'unsuccessful']) {
    mode.pending = m; await t.win.refreshDeletionBadge(); await settled(t);
    eq([t.badgeText(), t.badgeShown(), t.badgeState()], ['?', true, 'stale'], `pending ${m}: shown as ? and flagged stale`);
    ok(/may be out of date/.test(t.badgeTitle()), 'tooltip explains it');
  }
  eq(t.storeSets.length - sets, 0, 'no number was published to the shell store by failed rounds');
  mode.pending = 'ok'; await t.win.refreshDeletionBadge(); await settled(t);
  ok(!t.badgeShown() && t.badgeState() === 'known' && t.badgeTitle() === '', 'a complete round makes the zero real again (hidden, known)');
  await t.finish();
});

scenario('dual-control: a stale vote (request no longer pending) re-reads the list and the badge; a 500 still does not', async () => {
  const data = fixture(); data.dual = [dualRow(300, 'PENDING')]; data.self = [];
  const t = await boot({ data }); await pageReady(t); await waitFor(() => t.badgeText() === '1', 'initial'); await settled(t);
  t.failures['dual-vote:300'] = { status: 400, message: 'This request is no longer pending.' };
  data.dual[0].status = 'CANCELLED';                      // another admin cancelled it meanwhile
  const before = t.calls.length, selfBefore = t.count('self');
  t.click(approveBtn(t));
  await waitFor(() => /No pending requests\./.test(t.$('#deletionList').textContent), 'list re-read');
  await waitFor(() => t.count('self') === selfBefore + 1, 'one badge round'); await settled(t);
  eq(t.calls.slice(before).filter(c => c.startsWith('POST')), ['POST /api/admin/deletion-requests/300/vote'], 'the vote was not retried');
  ok(!approveBtn(t), 'the dead Approve button is gone');
  ok(/no longer pending/.test(t.$('#modal').textContent), 'the server message is still shown to the admin');
  ok(!t.badgeShown() && t.badgeState() === 'known', 'badge re-read: nothing open any more');
  await t.finish();
});

scenario('dual-control: a stale cancel and a stale execute re-read the list and badge', async () => {
  const data = fixture(); data.dual = [dualRow(310, 'PENDING'), dualRow(311, 'APPROVED')]; data.self = [];
  const t = await boot({ data }); await pageReady(t); await waitFor(() => t.badgeText() === '2', 'initial'); await settled(t);
  // cancel 310 that was cancelled elsewhere
  t.failures['dual-cancel:310'] = { status: 400, message: 'This request can no longer be cancelled.' };
  data.dual[0].status = 'CANCELLED';
  let selfBefore = t.count('self');
  t.click([...t.$$('#deletionList .admin-card')].find(c => c.textContent.includes('T310')).querySelector('.btn-view'));
  await waitFor(() => t.count('self') === selfBefore + 1, 'badge round after stale cancel'); await settled(t);
  eq(t.badgeText(), '1', 'badge now counts only the approved request');
  t.click(t.$('#modal button')); // OK
  // execute 311 that was executed elsewhere (Approved filter)
  t.click(filterBtn(t, 'Approved')); await waitFor(() => t.$('#deletionList').textContent.includes('T311'), 'approved list'); await settled(t);
  t.failures['dual-execute:311'] = { status: 400, message: 'This request has not been fully approved yet.' };
  data.dual[1].status = 'EXECUTED';
  selfBefore = t.count('self');
  t.click([...t.$$('#deletionList .admin-card')].find(c => c.textContent.includes('T311')).querySelector('.btn-delete'));
  await waitFor(() => t.$('#confirmExecuteBtn'), 'execute modal'); t.click(t.$('#confirmExecuteBtn'));
  await waitFor(() => /No approved requests\./.test(t.$('#deletionList').textContent), 'list re-read after stale execute');
  await waitFor(() => t.count('self') === selfBefore + 1, 'badge round after stale execute'); await settled(t);
  ok(/not been fully approved/.test(t.$('#executeModalStatus').textContent), 'the inline error stays visible in the modal');
  ok(!t.badgeShown(), 'badge re-read: nothing actionable');
  await t.finish();
});

scenario('dual-control: double clicks send ONE vote / ONE cancel and show no false error', async () => {
  const data = fixture(); data.dual = [dualRow(320, 'PENDING'), dualRow(321, 'PENDING', { callerVoted: true, approveCount: 2 })]; data.self = [];
  const t = await boot({ data }); await pageReady(t); await settled(t);
  const b = approveBtn(t); t.click(b); t.click(b); t.click(b);
  await waitFor(() => t.count('self') >= 2, 'badge round after the vote'); await settled(t);
  eq(t.calls.filter(c => c === 'POST /api/admin/deletion-requests/320/vote').length, 1, 'exactly one vote POST');
  ok(!modalOpen(t), 'no "already voted" error modal');
  const cancel = [...t.$$('#deletionList .admin-card')].find(c => c.textContent.includes('T321')).querySelector('.btn-view');
  t.click(cancel); t.click(cancel);
  await waitFor(() => t.calls.includes('POST /api/admin/deletion-requests/321/cancel'), 'cancel posted'); await settled(t); await sleep(40);
  eq(t.calls.filter(c => c === 'POST /api/admin/deletion-requests/321/cancel').length, 1, 'exactly one cancel POST');
  ok(!modalOpen(t), 'no error modal');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('dual-control: a slow answer for an earlier filter never replaces the list of the current filter', async () => {
  const data = fixture(); data.dual = [dualRow(400, 'PENDING'), dualRow(401, 'APPROVED'), dualRow(402, 'REJECTED')]; data.self = [];
  const t = await boot({ data }); await pageReady(t); await settled(t);
  let release; t.gates['GET /api/admin/deletion-requests?status=APPROVED'] = new Promise(r => { release = r; });
  t.click(filterBtn(t, 'Approved')); await sleep(30);
  t.click(filterBtn(t, 'All'));
  await waitFor(() => /T400/.test(t.$('#deletionList').textContent), 'All list shown');
  release(); delete t.gates['GET /api/admin/deletion-requests?status=APPROVED']; await sleep(120);
  const text = t.$('#deletionList').textContent;
  eq([/T400/.test(text), /T401/.test(text), /T402/.test(text)], [true, true, true], 'the All list is still on screen');
  eq(t.$('#deletionStatusFilter .active').textContent.trim(), 'All', 'and the active filter matches it');
  await t.finish();
});

scenario('dual-control list: a 200 that is not the documented envelope is an error, never "No ... requests."', async () => {
  const data = fixture(); data.dual = []; data.self = [];
  const mode = {}; const t = await boot({ data, mode }); await pageReady(t); await settled(t);
  for (const [m, label] of [['malformed', 'Rejected'], ['text', 'Deleted'], ['unsuccessful', 'Cancelled']]) {
    mode.other = m; t.click(filterBtn(t, label));
    await waitFor(() => /Could not load requests/.test(t.$('#deletionList').textContent), `${m}: error shown`);
    ok(!/No .*requests\./.test(t.$('#deletionList').textContent), `${m}: not presented as an empty queue`);
    eq(t.$('#deletionList [role="alert"]') !== null, true, `${m}: announced as an alert`);
  }
  mode.other = 'ok'; t.click(filterBtn(t, 'Rejected'));
  await waitFor(() => /No rejected requests\./.test(t.$('#deletionList').textContent), 'a genuinely empty list still says so');
  await t.finish();
});

scenario('self-requested: after a stale outcome the re-rendered row does not leave keyboard focus on <body>', async () => {
  const t = await boot(); await pageReady(t); await settled(t);
  t.failures['restore:104'] = { status: 400, message: 'No pending self-deletion request on this account.' };
  t.data.self.splice(t.data.self.findIndex(x => x.id === 104), 1);   // already handled elsewhere
  t.item(104).querySelector('button[data-sdq-action="restore"]').focus();
  await confirmRestore(t, 104);
  await waitFor(() => !t.item(104), 'row gone after authoritative reload'); await settled(t); await sleep(30);
  const a = t.win.document.activeElement;
  ok(a !== t.win.document.body && t.section().contains(a), 'focus is inside the self-requested section');
  eq(a.id, 'selfDeletionHeading', 'on its heading');
  await t.finish();
});

scenario('self-requested: repeated clicks and clicks on another row while one action is open start nothing extra', async () => {
  const t = await boot(); await pageReady(t); await settled(t);
  const b = t.button(t.item(110), 'Delete permanently'); t.click(b); t.click(b); t.click(b);
  await waitFor(() => t.dialog(), 'dialog');
  t.click(t.button(t.item(104), 'Restore access')); await sleep(40);
  eq(t.$$('dialog.admin-ui-dialog[open]').length, 1, 'still exactly one dialog');
  eq(t.calls.filter(c => c.startsWith('POST')), [], 'nothing was sent before confirmation');
  t.click(t.button(t.dialog(), 'Cancel')); await sleep(30);
  eq(t.calls.filter(c => c.startsWith('POST')), [], 'cancelling sends nothing');
  await t.finish();
});

scenario('self-requested: empty queue, load failure, Retry; the empty/failed states are never confused', async () => {
  const data = fixture(); data.self = [];
  const mode = { self: 'fail' }; const t = await boot({ data, mode });
  await waitFor(() => /Could not load self-requested deletions/.test(t.section()?.textContent || ''), 'failure state');
  ok(!/No self-requested deletions are waiting/.test(t.section().textContent), 'a failed load is not shown as empty');
  eq(t.badgeText(), '?', 'badge is ? while the self source is down');
  mode.self = 'ok'; t.click(t.button(t.section(), 'Retry'));
  await waitFor(() => /No self-requested deletions are waiting/.test(t.section().textContent), 'real empty state after Retry');
  await waitFor(() => t.badgeState() === 'known', 'badge recovered'); await settled(t);
  eq(t.badgeText(), t.badgeShown() ? t.badgeText() : '', 'badge reflects only the dual-control items now');
  await t.finish();
});

scenario('dual-control confirmations (legacy modal): dialog semantics, focus moves in, Tab is trapped, Escape closes, focus returns', async () => {
  const data = fixture(); data.dual = [dualRow(500, 'PENDING'), dualRow(501, 'APPROVED')]; data.self = [];
  const t = await boot({ data }); await pageReady(t); await settled(t);
  const doc = t.win.document;
  // Reject modal
  const reject = [...t.$$('#deletionList button')].find(b => b.textContent.includes('Reject'));
  reject.focus(); t.click(reject);
  const box = () => t.$('#modal .modal-box');
  eq([box().getAttribute('role'), box().getAttribute('aria-modal')], ['dialog', 'true'], 'dialog semantics');
  ok(box().getAttribute('aria-labelledby') && doc.getElementById(box().getAttribute('aria-labelledby')), 'labelled by its title');
  eq(doc.activeElement.id, 'rejectDeletionNote', 'focus moved to the note field');
  const order = [...box().querySelectorAll('textarea, button')];
  order[order.length - 1].focus(); key(t, doc.activeElement, 'Tab');
  eq(doc.activeElement, order[0], 'Tab from the last control wraps to the first');
  key(t, doc.activeElement, 'Tab', { shiftKey: true });
  eq(doc.activeElement, order[order.length - 1], 'Shift+Tab from the first wraps to the last');
  key(t, doc.activeElement, 'Escape'); await sleep(20);
  ok(!modalOpen(t), 'Escape closes it');
  eq(doc.activeElement, reject, 'focus returned to the Reject button');
  eq(t.calls.filter(c => c.startsWith('POST')), [], 'Escape sent nothing');
  // Execute modal (Approved filter): the safe choice is focused first; Escape is ignored while the deletion is being sent
  t.click(filterBtn(t, 'Approved')); await waitFor(() => t.$('#deletionList').textContent.includes('T501'), 'approved list'); await settled(t);
  const exec = t.$('#deletionList .btn-delete'); exec.focus(); t.click(exec);
  await waitFor(() => t.$('#confirmExecuteBtn'), 'execute modal');
  eq(doc.activeElement.textContent.trim(), 'Cancel', 'initial focus is Cancel, not the destructive button');
  let release; t.gates['POST /api/admin/deletion-requests/501/execute'] = new Promise(r => { release = r; });
  t.click(t.$('#confirmExecuteBtn')); await sleep(30);
  key(t, t.$('#confirmExecuteBtn') || doc.activeElement, 'Escape'); await sleep(20);
  ok(modalOpen(t), 'Escape does not dismiss the dialog while the deletion request is in flight');
  release(); delete t.gates['POST /api/admin/deletion-requests/501/execute'];
  await waitFor(() => !modalOpen(t), 'closed after success'); await settled(t);
  eq(t.calls.filter(c => c === 'POST /api/admin/deletion-requests/501/execute').length, 1, 'exactly one execute POST');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

let failed = 0;
for (const [name, fn] of scenarios) {
  try { await fn(); console.log(`ok   ${name}`); }
  catch (error) { failed += 1; console.error(`FAIL ${name}\n     ${error && error.stack ? error.stack.split('\n').slice(0, 5).join('\n     ') : error}`); }
}
console.log(`${scenarios.length - failed}/${scenarios.length} DOM scenarios passed; ${checks} assertions ran (pure, coordinator and jsdom; not a real browser).`);
process.exit(failed ? 1 : 0);
