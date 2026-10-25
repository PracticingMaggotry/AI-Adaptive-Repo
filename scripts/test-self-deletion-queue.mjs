// Focused regression test for People > Deletion requests: the preserved admin-initiated dual-control queue
// AND the new self-requested (one-admin review) queue.
// Run: node --experimental-default-type=module scripts/test-self-deletion-queue.mjs
//
// Needs `jsdom` (resolved from node_modules or NODE_PATH). Without it the pure checks still run and the script
// exits with code 2: that is NOT a pass. jsdom is not a real browser: layout, focus rings, real <dialog>
// top-layer behaviour and the live Spring backend are NOT exercised. The fake API below mirrors the
// status codes and messages of AdminController (404 "Account not found.", 400 "No pending self-deletion
// request on this account.", 400 "Can't restore ... already used by a newer account ...").
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/main/resources/static');
const adminDir = path.join(root, 'admin');
let checks = 0;
const ok = (value, message) => { assert.ok(value, message); checks += 1; };
const eq = (actual, expected, message) => { assert.deepEqual(actual, expected, message); checks += 1; };

// ───────────────────────── pure checks ─────────────────────────
const queueMod = await import(pathToFileURL(path.join(adminDir, 'people/self-deletion-queue.js')));
const { classifyFailure, normalizeRequests, SELF_DELETION_PATHS, DELETE_CONFIRM_TEXT } = queueMod;
const err = (status, message) => Object.assign(new Error(message), { status });

eq(SELF_DELETION_PATHS.list, '/api/admin/self-deletion-requests', 'list endpoint');
eq(SELF_DELETION_PATHS.execute(7), '/api/admin/self-deletion-requests/7/execute', 'execute endpoint');
eq(SELF_DELETION_PATHS.restore(7), '/api/admin/self-deletion-requests/7/restore', 'restore endpoint');
eq(DELETE_CONFIRM_TEXT, 'DELETE', 'typed confirmation word');
eq(classifyFailure('execute', err(404, 'Account not found.')), 'stale', '404 is stale');
eq(classifyFailure('restore', err(400, 'No pending self-deletion request on this account.')), 'stale', '400 not-pending is stale');
eq(classifyFailure('restore', err(400, "Can't restore \u2014 a@b.test is already used by a newer account. The user likely already re-registered; this old account can still be permanently deleted instead.")), 'conflict', 'email collision');
eq(classifyFailure('execute', err(400, "Can't restore \u2014 a@b.test is already used by a newer account.")), 'error', 'collision wording is only a conflict for restore');
eq(classifyFailure('execute', err(500, 'boom')), 'error', '500 is a plain failure');
eq(classifyFailure('execute', new Error('Failed to fetch')), 'error', 'network failure is a plain failure');
eq(classifyFailure('execute', err(403, 'Forbidden')), 'error', '403 is a plain failure (no pretend success)');
eq(normalizeRequests({ requests: [{ id: 9, requestedAt: '2026-10-15T11:00:00' }, { id: 3, requestedAt: '2026-10-01T08:00:00' }, null, 'x', { id: 'abc' }] }).map(r => r.userId), [3, 9, null], 'oldest first; invalid ids become null; junk entries dropped');
assert.throws(() => normalizeRequests({ requests: 'nope' }), /unexpected response/); checks += 1;
assert.throws(() => normalizeRequests(null), /unexpected response/); checks += 1;

// ───────────────────────── DOM ─────────────────────────
async function loadJsdom() {
  const dirs = [undefined, ...(process.env.NODE_PATH || '').split(path.delimiter).filter(Boolean)];
  for (const dir of dirs) {
    try { return (dir ? createRequire(path.join(dir, 'x.js'))('jsdom') : await import('jsdom')); } catch { /* try next */ }
  }
  return null;
}
const jsdom = await loadJsdom();
if (!jsdom) { console.log(`Pure checks passed (${checks}).`); console.error('SKIPPED DOM scenarios (exit 2): jsdom could not be loaded.'); process.exit(2); }
const { JSDOM } = jsdom.JSDOM ? jsdom : jsdom.default;

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function waitFor(cond, label, timeout = 4000) {
  const start = Date.now();
  while (Date.now() - start < timeout) { if (await cond()) return; await sleep(10); }
  throw new Error(`timed out waiting for: ${label}`);
}

const sharedJs = fs.readFileSync(path.join(adminDir, 'shared.js'), 'utf8');
const legacyDeletionJs = fs.readFileSync(path.join(adminDir, 'legacy/deletionrequests.js'), 'utf8');
const routesMod = await import(pathToFileURL(path.join(adminDir, 'routes.js')));
const routerMod = await import(pathToFileURL(path.join(adminDir, 'router.js')));

const COLLISION = "Can't restore \u2014 taylor@example.test is already used by a newer account. The user likely already re-registered; this old account can still be permanently deleted instead.";
function fixture() {
  return {
    // user ids and admin-initiated request ids deliberately overlap (104) to prove they are never mixed
    self: [
      { id: 104, fullName: 'Taylor Cruz', originalEmail: 'taylor@example.test', requestedAt: '2026-10-15T11:00:00' },
      { id: 110, fullName: 'Riley Older', originalEmail: 'riley@example.test', requestedAt: '2026-10-10T09:00:00' }
    ],
    dual: [
      { id: 104, status: 'PENDING', targetName: 'Lee Pending', targetEmail: 'lee@example.test', reason: 'Repeated abuse', requestedBy: 'pat@example.test', requestedAt: '2026-10-12T10:00:00', approveCount: 1, requiredCount: 3, callerVoted: false, votes: [{ decision: 'APPROVE', adminEmail: 'sam@example.test', note: '' }] },
      { id: 105, status: 'APPROVED', targetName: 'Ready Person', targetEmail: 'ready@example.test', reason: 'Confirmed', requestedBy: 'pat@example.test', requestedAt: '2026-10-11T10:00:00', approveCount: 3, requiredCount: 3, callerVoted: true, votes: [] }
    ]
  };
}

async function boot({ data = fixture(), selfFail = [], dualFail = {} } = {}) {
  const dom = new JSDOM('<!doctype html><body><div id="adminRouteNotice" role="status"></div><div id="adminContent" tabindex="-1"></div><div id="modal" style="display:none;"></div></body>', { url: 'http://localhost/admin.html#/people/deletion-requests', runScripts: 'dangerously', pretendToBeVisual: true });
  const win = dom.window;
  win.scrollTo = () => {};
  win.Element.prototype.scrollIntoView = () => {};
  win.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  win.HTMLDialogElement.prototype.close = function () { if (this.hasAttribute('open')) { this.removeAttribute('open'); this.dispatchEvent(new win.Event('close')); } };
  Object.assign(globalThis, { window: win, document: win.document, Element: win.Element, Node: win.Node, MutationObserver: win.MutationObserver, ResizeObserver: undefined });

  const calls = [];            // "METHOD /path"
  const hooks = { activity: 0, users: 0 };
  const gates = {};            // path -> promise to hold a request open
  const failures = { selfList: [...selfFail], self: {}, dual: dualFail }; // consumed in order / by key
  let dualFilter = 'ALL';

  const apiFetch = async (p, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    const url = new URL(p, 'http://x');
    const pth = url.pathname;
    calls.push(`${method} ${pth}`);
    await sleep(1);
    if (gates[`${method} ${pth}`]) await gates[`${method} ${pth}`];
    if (pth === '/api/admin/self-deletion-requests' && method === 'GET') {
      const next = failures.selfList.shift();
      if (next) throw err(next.status || 500, next.message);
      return { success: true, requests: data.self.map(x => ({ ...x })) };
    }
    let m = pth.match(/^\/api\/admin\/self-deletion-requests\/(\d+)\/(execute|restore)$/);
    if (m && method === 'POST') {
      const id = Number(m[1]);
      const forced = failures.self[`${m[2]}:${id}`];
      if (forced) { if (!forced.sticky) delete failures.self[`${m[2]}:${id}`]; if (forced.status === 'network') throw new Error('Failed to fetch'); throw err(forced.status, forced.message); }
      const idx = data.self.findIndex(x => x.id === id);
      if (idx < 0) throw err(404, 'Account not found.');
      if (m[2] === 'restore' && forced === undefined && data.collide?.includes(id)) throw err(400, COLLISION);
      data.self.splice(idx, 1);
      return { success: true, message: m[2] === 'execute' ? 'Account permanently deleted.' : 'Account restored \u2014 the user can log in again with their original email.' };
    }
    if (pth === '/api/admin/deletion-requests' && method === 'GET') {
      if (failures.dual.list) throw err(500, failures.dual.list);
      dualFilter = url.searchParams.get('status') || 'ALL';
      return { success: true, requests: data.dual.filter(r => dualFilter === 'ALL' || r.status === dualFilter).map(r => ({ ...r })) };
    }
    m = pth.match(/^\/api\/admin\/deletion-requests\/(\d+)\/(vote|execute|cancel)$/);
    if (m && method === 'POST') {
      const r = data.dual.find(x => x.id === Number(m[1]));
      if (!r) throw err(404, 'Request not found.');
      if (m[2] === 'vote') { r.callerVoted = true; r.approveCount += 1; r.votes.push({ decision: JSON.parse(options.body).decision, adminEmail: 'me@example.test', note: JSON.parse(options.body).note }); if (r.approveCount >= r.requiredCount) r.status = 'APPROVED'; }
      if (m[2] === 'execute') r.status = 'EXECUTED';
      if (m[2] === 'cancel') r.status = 'CANCELLED';
      return { success: true };
    }
    throw new Error('unexpected request ' + method + ' ' + p);
  };
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
  calls.length = 0;

  const ui = (await import(pathToFileURL(path.join(adminDir, 'ui.js')))).default;
  for (const link of win.document.querySelectorAll('link[rel="stylesheet"]')) link.dispatchEvent(new win.Event('load')); // don't wait out ui.js's stylesheet timeout
  const errors = [];
  const router = routerMod.createRouter({
    routes: routesMod.routes, aliases: routesMod.aliases, defaultPath: routesMod.defaultPath,
    mount: win.document.getElementById('adminContent'),
    hooks: { getContext: () => ({ api: { get: apiFetch, request: apiFetch }, ui }), onError: (e, match, phase) => errors.push(`${phase}: ${e.message}`) }
  });
  await router.start();

  const $ = sel => win.document.querySelector(sel);
  const $$ = sel => [...win.document.querySelectorAll(sel)];
  const section = () => $('#selfDeletionSection');
  const sectionText = () => (section()?.textContent || '').replace(/\s+/g, ' ');
  const item = id => section()?.querySelector(`li[data-user-id="${id}"]`);
  const button = (scope, label) => [...(scope?.querySelectorAll('button') || [])].find(b => b.textContent.trim() === label);
  const click = el => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true, cancelable: true }));
  const dialog = () => $('dialog.admin-ui-dialog[open]');
  // ui.js keeps ONE toast region for the whole process, so toasts from earlier scenarios can still be in it: match by text, never by position.
  const toast = (kind, includes) => [...win.document.querySelectorAll(`.admin-ui-toast--${kind}`)].some(n => n.textContent.includes(includes));
  const selfCalls = () => calls.filter(c => /self-deletion-requests/.test(c));
  async function finish() { win.history.replaceState(null, '', '#/overview'); await router.destroy(); }
  return { win, router, calls, hooks, gates, failures, data, errors, $, $$, section, sectionText, item, button, click, dialog, toast, selfCalls, finish, apiFetch };
}

const scenarios = [];
const scenario = (name, fn) => scenarios.push([name, fn]);
const ready = t => waitFor(() => t.section() && t.section().querySelector('.sdq__list, .admin-ui-empty, .sdq__state[role="alert"]'), 'self queue settled');

scenario('both queues render, are labelled as different workflows, and use only existing endpoints', async () => {
  const t = await boot();
  await ready(t);
  await waitFor(() => t.$('#deletionList .admin-card'), 'dual-control queue loaded');
  ok(/Self-requested deletions/.test(t.$('#selfDeletionHeading').textContent), 'self section heading');
  ok(/Admin-initiated deletion requests/.test(t.$('#dualControlHeading').textContent), 'legacy card is labelled admin-initiated');
  ok(t.sectionText().includes('No votes') || t.sectionText().includes('no votes'), 'self section explains there are no votes');
  ok(t.$('#dualControlSection').textContent.includes('Every current admin must approve'), 'legacy dual-control explanation preserved');
  ok(t.$('.sdq-guide') && t.$$('.sdq-guide__card').length === 2, 'guide distinguishes the two workflows');
  // oldest request first: Riley (Oct 10) before Taylor (Oct 15)
  eq(t.$$('#selfDeletionSection li[data-user-id]').map(li => li.dataset.userId), ['110', '104'], 'self queue sorted oldest first');
  ok(t.item(104).textContent.includes('taylor@example.test') && t.item(104).textContent.includes('Account ID'), 'original email and account id shown');
  ok(!t.$('#deletionList').textContent.includes('Taylor Cruz'), 'self requests never appear in the dual-control list');
  ok(!t.$('#selfDeletionSection').textContent.includes('Lee Pending'), 'dual-control requests never appear in the self list');
  ok(t.$('#deletionList').textContent.includes('1 of 3 admins approved'), 'dual-control vote progress still rendered');
  ok(t.$('#deletionStatusFilter').children.length === 6, 'all six dual-control status filters preserved');
  eq([...new Set(t.calls)].sort(), ['GET /api/admin/deletion-requests', 'GET /api/admin/self-deletion-requests'], 'only existing GET endpoints on load');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('dual-control workflow is preserved: filter, approve vote, reject note, execute modal, cancel use the deletion-requests endpoints only', async () => {
  const t = await boot();
  await waitFor(() => t.$('#deletionList .admin-card'), 'dual list');
  await ready(t);
  // Approve (request id 104 collides with user id 104 on purpose)
  const approve = [...t.$$('#deletionList button')].find(b => b.textContent.includes('Approve'));
  t.click(approve);
  await waitFor(() => t.calls.includes('POST /api/admin/deletion-requests/104/vote'), 'vote posted to the REQUEST id');
  await waitFor(() => t.hooks.activity >= 1, 'activity refreshed after vote');
  ok(!t.calls.some(c => /self-deletion-requests\/104/.test(c)), 'request id 104 never touched the self-deletion endpoints');
  // Approved filter -> execute goes through the legacy modal
  t.click([...t.$$('#deletionStatusFilter button')].find(b => b.textContent.includes('Approved')));
  await waitFor(() => t.$('#deletionList').textContent.includes('Ready Person'), 'approved filter applied');
  t.click([...t.$$('#deletionList button')].find(b => b.textContent.includes('Execute Deletion')));
  await waitFor(() => t.$('#confirmExecuteBtn'), 'legacy execute confirmation');
  ok(t.$('#modal').textContent.includes('irreversible'), 'legacy confirmation text preserved');
  t.click(t.$('#confirmExecuteBtn'));
  await waitFor(() => t.calls.includes('POST /api/admin/deletion-requests/105/execute'), 'execute posted to the request id');
  ok(!t.calls.some(c => /self-deletion-requests\/\d+\/execute/.test(c)), 'dual-control execution never calls the self-deletion execute endpoint');
  ok(t.item(104) && t.item(110), 'self queue untouched by dual-control actions');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('empty states: both queues explain nothing is waiting', async () => {
  const data = fixture(); data.self = []; data.dual = [];
  const t = await boot({ data });
  await ready(t);
  await waitFor(() => /No pending requests/.test(t.$('#deletionList').textContent), 'dual empty state');
  ok(t.sectionText().includes('No self-requested deletions are waiting for review.'), 'self empty state');
  ok(!t.section().querySelector('.sdq__list'), 'no list rendered when empty');
  await t.finish();
});

scenario('self queue load error shows a retry; dual queue error is independent; retry recovers', async () => {
  const data = fixture();
  const t = await boot({ data, selfFail: [{ status: 500, message: 'Database down' }], dualFail: { list: 'Dual boom' } });
  await waitFor(() => t.section()?.querySelector('.sdq__state[role="alert"]'), 'self error state');
  ok(t.sectionText().includes('Could not load self-requested deletions: Database down'), 'self error message');
  await waitFor(() => /Could not load requests: Dual boom/.test(t.$('#deletionList').textContent), 'dual error state still handled by the legacy renderer');
  ok(!t.section().querySelector('.sdq__list'), 'no items shown while failed');
  const retry = t.button(t.section(), 'Retry');
  ok(retry, 'retry button present');
  t.click(retry);
  await waitFor(() => t.section().querySelector('.sdq__list'), 'recovered after retry');
  eq(t.$$('#selfDeletionSection li[data-user-id]').length, 2, 'items shown after retry');
  await t.finish();
});

scenario('failed refresh keeps the previous list visible with a retry banner', async () => {
  const t = await boot();
  await ready(t);
  t.failures.selfList.push({ status: 503, message: 'Unavailable' });
  t.click(t.button(t.section(), 'Refresh'));
  await waitFor(() => !t.section().querySelector('.sdq__banner').hidden, 'banner shown');
  ok(t.section().querySelector('.sdq__banner').textContent.includes('Unavailable'), 'banner explains');
  eq(t.$$('#selfDeletionSection li[data-user-id]').length, 2, 'previous items are not dropped by a failed refresh');
  t.click(t.button(t.section().querySelector('.sdq__banner'), 'Retry'));
  await waitFor(() => t.section().querySelector('.sdq__banner').hidden, 'banner cleared after retry');
  await t.finish();
});

scenario('permanent deletion: cancel does nothing, wrong DELETE text does nothing, correct text deletes, list + activity + users refresh', async () => {
  const t = await boot();
  await ready(t);
  // cancel
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'confirm dialog');
  ok(t.dialog().textContent.includes('irreversibly removed'), 'explains the account will be irreversibly removed');
  ok(t.dialog().textContent.includes('Type DELETE to confirm'), 'requires typing DELETE');
  ok(t.dialog().textContent.includes('Taylor Cruz') && t.dialog().textContent.includes('taylor@example.test'), 'names the account');
  t.click(t.button(t.dialog(), 'Cancel'));
  await waitFor(() => !t.dialog(), 'dialog closed');
  eq(t.selfCalls().filter(c => c.startsWith('POST')), [], 'cancel sends nothing');
  // wrong text
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'confirm dialog again');
  const input = t.dialog().querySelector('input'); input.value = 'delete me';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await waitFor(() => !t.dialog(), 'dialog closed after mismatch');
  eq(t.selfCalls().filter(c => c.startsWith('POST')), [], 'mismatched confirmation text sends nothing');
  ok(t.item(104), 'item still listed after mismatch');
  // correct
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'confirm dialog third time');
  t.dialog().querySelector('input').value = 'DELETE';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await waitFor(() => t.calls.includes('POST /api/admin/self-deletion-requests/104/execute'), 'execute posted with the USER id');
  await waitFor(() => !t.item(104), 'item removed after server success');
  ok(t.item(110), 'other request untouched');
  ok(t.toast('success', 'Account permanently deleted.'), 'success toast uses the server message');
  await waitFor(() => t.hooks.activity >= 1 && t.hooks.users >= 1, 'activity log and users refreshed');
  ok(t.calls.filter(c => c === 'GET /api/admin/self-deletion-requests').length >= 2, 'self queue reloaded from the server');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('repeated clicks while a deletion is running send exactly one request', async () => {
  const t = await boot();
  await ready(t);
  let release; t.gates['POST /api/admin/self-deletion-requests/104/execute'] = new Promise(r => { release = r; });
  const del = t.button(t.item(104), 'Delete permanently');
  t.click(del); t.click(del); t.click(t.button(t.item(110), 'Delete permanently')); // second/third click while the first confirm is open
  await waitFor(() => t.dialog(), 'one dialog');
  eq(t.$$('dialog.admin-ui-dialog').length, 1, 'only one confirmation dialog opened');
  t.dialog().querySelector('input').value = 'DELETE';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await waitFor(() => t.calls.includes('POST /api/admin/self-deletion-requests/104/execute'), 'request in flight');
  ok(del.textContent === 'Deleting\u2026' && del.getAttribute('aria-disabled') === 'true', 'button shows progress and is aria-disabled');
  t.click(del); t.click(t.button(t.item(110), 'Delete permanently')); t.click(t.button(t.item(110), 'Restore access'));
  await sleep(60);
  eq(t.selfCalls().filter(c => c.startsWith('POST')), ['POST /api/admin/self-deletion-requests/104/execute'], 'exactly one POST while in flight, even for other rows');
  ok(!t.dialog(), 'no extra dialog opened while a request is running');
  release();
  await waitFor(() => !t.item(104), 'removed after release');
  eq(t.selfCalls().filter(c => c.startsWith('POST')).length, 1, 'still exactly one POST');
  await t.finish();
});

scenario('failed deletion (500 and network) keeps the item, shows an inline error, and can be retried', async () => {
  const t = await boot();
  await ready(t);
  t.failures.self['execute:104'] = { status: 500, message: 'Server exploded' };
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'dialog');
  t.dialog().querySelector('input').value = 'DELETE';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await waitFor(() => /Deletion failed: Server exploded/.test(t.item(104)?.textContent || ''), 'inline error');
  ok(t.item(104), 'item NOT removed after a failed request');
  ok(t.item(104).textContent.includes('you can try again'), 'tells the admin they can retry');
  ok(t.toast('error', 'Deletion failed: Server exploded'), 'error toast');
  eq(t.hooks, { activity: 0, users: 0 }, 'no activity/users refresh when nothing changed');
  // network failure on the retry
  t.failures.self['execute:104'] = { status: 'network' };
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'dialog again');
  t.dialog().querySelector('input').value = 'DELETE';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await waitFor(() => /Failed to fetch/.test(t.item(104)?.textContent || ''), 'network error shown');
  ok(t.item(104), 'item still listed after network failure');
  ok(t.button(t.item(104), 'Delete permanently').getAttribute('aria-disabled') === 'false', 'button usable again');
  await t.finish();
});

scenario('stale request (already handled elsewhere): clear message, list refreshed from the server, nothing assumed', async () => {
  const t = await boot();
  await ready(t);
  // another admin handled 104 meanwhile: the server no longer lists it and answers 400 for execute
  t.data.self = t.data.self.filter(x => x.id !== 104);
  t.failures.self['execute:104'] = { status: 400, message: 'No pending self-deletion request on this account.' };
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'dialog');
  t.dialog().querySelector('input').value = 'DELETE';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await waitFor(() => !t.item(104), 'disappears only because the refreshed server list no longer has it');
  ok(t.toast('error', 'No pending self-deletion request'), 'toast explains');
  ok(t.item(110), 'unrelated request remains');
  // 404 while the server STILL lists it: it must stay, with the message
  t.failures.self['restore:110'] = { status: 404, message: 'Account not found.' };
  t.click(t.button(t.item(110), 'Restore access'));
  await waitFor(() => t.dialog(), 'restore dialog');
  t.click(t.button(t.dialog(), 'Confirm'));
  await waitFor(() => /Account not found\./.test(t.item(110)?.textContent || ''), 'note on the row');
  ok(t.item(110), 'a 404 does not remove an item the refreshed server list still returns');
  await t.finish();
});

scenario('restore: separate confirmation explains access is restored; success removes it and refreshes', async () => {
  const t = await boot();
  await ready(t);
  t.click(t.button(t.item(104), 'Restore access'));
  await waitFor(() => t.dialog(), 'restore dialog');
  ok(t.dialog().textContent.includes('Restore this account?'), 'restore title');
  ok(t.dialog().textContent.includes('access is restored') || t.dialog().textContent.includes("user's access is restored"), 'explains access is restored');
  ok(!t.dialog().querySelector('input'), 'restore does not ask for the DELETE word');
  ok(!t.dialog().textContent.includes('irreversibly'), 'restore dialog is not the deletion dialog');
  t.click(t.button(t.dialog(), 'Cancel'));
  await waitFor(() => !t.dialog(), 'closed');
  eq(t.selfCalls().filter(c => c.startsWith('POST')), [], 'cancel sends nothing');
  t.click(t.button(t.item(104), 'Restore access'));
  await waitFor(() => t.dialog(), 'restore dialog again');
  t.click(t.button(t.dialog(), 'Confirm'));
  await waitFor(() => t.calls.includes('POST /api/admin/self-deletion-requests/104/restore'), 'restore posted with the USER id');
  await waitFor(() => !t.item(104), 'removed after success');
  ok(t.toast('success', 'log in again'), 'success toast');
  await waitFor(() => t.hooks.activity >= 1 && t.hooks.users >= 1, 'activity + users refreshed');
  await t.finish();
});

scenario('restore email collision: server message shown, item stays queued, deletion still offered, nothing refreshed as changed', async () => {
  const data = fixture(); data.collide = [104];
  const t = await boot({ data });
  await ready(t);
  t.click(t.button(t.item(104), 'Restore access'));
  await waitFor(() => t.dialog(), 'restore dialog');
  t.click(t.button(t.dialog(), 'Confirm'));
  await waitFor(() => t.item(104)?.querySelector('.sdq__note--conflict'), 'conflict note');
  ok(t.item(104).textContent.includes('already used by a newer account'), 'server explanation shown');
  ok(t.item(104).textContent.includes('Permanent deletion is still available'), 'tells the admin deletion is still possible');
  ok(t.item(104) && t.data.self.some(x => x.id === 104), 'item still listed and still pending on the server');
  ok(t.toast('error', 'already used by another account'), 'error toast');
  eq(t.hooks, { activity: 0, users: 0 }, 'no refresh for an action that changed nothing');
  ok(t.button(t.item(104), 'Delete permanently').getAttribute('aria-disabled') === 'false', 'delete remains available');
  // deleting the colliding old account still works
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'dialog');
  t.dialog().querySelector('input').value = 'DELETE';
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await waitFor(() => !t.item(104), 'deleted');
  await t.finish();
});

scenario('server text is rendered as text, never as HTML', async () => {
  const data = fixture();
  data.self = [{ id: 7, fullName: '<img src=x onerror="window.__pwned=1">Eve', originalEmail: '<b>eve</b>@example.test', requestedAt: '2026-10-01T00:00:00' }];
  data.collide = [7];
  const t = await boot({ data });
  await ready(t);
  ok(t.item(7).textContent.includes('<img src=x onerror="window.__pwned=1">Eve'), 'name shown literally');
  ok(t.item(7).textContent.includes('<b>eve</b>@example.test'), 'email shown literally');
  eq(t.section().querySelectorAll('img, b').length, 0, 'no injected elements');
  t.click(t.button(t.item(7), 'Restore access'));
  await waitFor(() => t.dialog(), 'dialog');
  ok(t.dialog().querySelectorAll('img, b').length === 0, 'confirm dialog also renders text only');
  t.click(t.button(t.dialog(), 'Confirm'));
  await waitFor(() => t.item(7)?.querySelector('.sdq__note--conflict'), 'collision note');
  eq(t.section().querySelectorAll('img, b').length, 0, 'server error text (which echoes the email) is not parsed as HTML');
  ok(t.win.__pwned === undefined, 'no script ran');
  await t.finish();
});

scenario('records without a valid account id cannot be acted on', async () => {
  const data = fixture();
  data.self = [{ id: null, fullName: 'Broken Row', originalEmail: 'broken@example.test', requestedAt: null }];
  const t = await boot({ data });
  await ready(t);
  const li = t.section().querySelector('li.sdq__item');
  ok(li.textContent.includes('no valid account ID'), 'explains why');
  ok(li.textContent.includes('Unknown'), 'missing date shown as Unknown');
  t.click(t.button(li, 'Delete permanently')); t.click(t.button(li, 'Restore access'));
  await sleep(40);
  ok(!t.dialog(), 'no dialog for an unusable record');
  eq(t.selfCalls().filter(c => c.startsWith('POST')), [], 'no POST for an unusable record');
  await t.finish();
});

scenario('unmounting destroys the self queue; navigating away mid-confirm never sends the request', async () => {
  const t = await boot();
  await ready(t);
  t.click(t.button(t.item(104), 'Delete permanently'));
  await waitFor(() => t.dialog(), 'dialog');
  t.dialog().querySelector('input').value = 'DELETE';
  await t.finish();                                   // route change + unmount while the dialog is open
  ok(!t.section(), 'section removed on unmount');
  t.click(t.button(t.dialog(), 'Confirm destructive action'));
  await sleep(60);
  eq(t.selfCalls().filter(c => c.startsWith('POST')), [], 'confirming after the view was unmounted sends nothing');
});

let failed = 0;
for (const [name, fn] of scenarios) {
  try { await fn(); console.log(`ok   ${name}`); }
  catch (error) { failed += 1; console.error(`FAIL ${name}\n     ${error && error.stack ? error.stack.split('\n').slice(0, 4).join('\n     ') : error}`); }
}
console.log(`${scenarios.length - failed}/${scenarios.length} DOM scenarios passed; ${checks} assertions ran in jsdom (not a real browser).`);
process.exit(failed ? 1 : 0);
