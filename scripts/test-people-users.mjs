// Focused tests for People > Users: account status derivation (pure) and the user detail drawer (DOM).
// Run: node --experimental-default-type=module scripts/test-people-users.mjs
// The DOM part needs `jsdom` (resolved from node_modules or NODE_PATH). Without it the pure part still
// runs and the script exits with code 2 (NOT a pass). jsdom is not a real browser: layout, focus rings,
// real dialog top-layer behaviour and the live backend are NOT exercised here.
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

// ───────────────────────── pure: account status ─────────────────────────
const { deriveAccountStatus, deriveAccountStatusDetail, ACCOUNT_STATUS_SEVERITY } = await import(pathToFileURL(path.join(adminDir, 'account-status.js')));
const { parseUserId } = await import(pathToFileURL(path.join(adminDir, 'people/users-data.js')));

const all = { bannedEmails: [], selfDeletionRequests: [], deletionRequests: [] };
const u = { id: 5, email: 'person@example.test', flagged: false, archived: false };
eq(Object.keys(ACCOUNT_STATUS_SEVERITY), ['ACTIVE', 'FLAGGED', 'SUSPENDED', 'BANNED', 'DELETION_PENDING'], 'severity order');
eq(deriveAccountStatus(null, all), null, 'no user -> null');
eq(deriveAccountStatus(u, all), 'ACTIVE', 'plain user is ACTIVE');
eq(deriveAccountStatus({ ...u, flagged: true }, all), 'FLAGGED', 'flagged');
eq(deriveAccountStatus({ ...u, archived: true }, all), 'SUSPENDED', 'archived = suspended');
eq(deriveAccountStatus({ ...u, flagged: true, archived: true }, all), 'SUSPENDED', 'suspended outranks flagged');
eq(deriveAccountStatus(u, { ...all, bannedEmails: [{ email: ' PERSON@example.test ' }] }), 'BANNED', 'banned email match is trimmed and case-insensitive');
eq(deriveAccountStatus({ ...u, flagged: true, archived: true }, { ...all, bannedEmails: [{ email: u.email }] }), 'BANNED', 'banned outranks suspended and flagged');
eq(deriveAccountStatus(u, { ...all, selfDeletionRequests: [{ id: 5 }] }), 'DELETION_PENDING', 'self-deletion request id is the user id');
eq(deriveAccountStatus(u, { ...all, selfDeletionRequests: [{ id: 6 }] }), 'ACTIVE', 'other user self-deletion does not apply');
eq(deriveAccountStatus(u, { ...all, deletionRequests: [{ id: 5, targetEmail: 'someone@else.test', status: 'PENDING' }] }), 'ACTIVE', 'admin request id is a REQUEST id and must never match a user id');
eq(deriveAccountStatus(u, { ...all, deletionRequests: [{ id: 9, targetEmail: 'Person@Example.test', status: 'PENDING' }] }), 'DELETION_PENDING', 'admin request PENDING by targetEmail');
eq(deriveAccountStatus(u, { ...all, deletionRequests: [{ id: 9, targetEmail: u.email, status: 'APPROVED' }] }), 'DELETION_PENDING', 'admin request APPROVED (awaiting execution)');
for (const status of ['EXECUTED', 'REJECTED', 'CANCELLED']) {
  eq(deriveAccountStatus(u, { ...all, deletionRequests: [{ id: 9, targetEmail: u.email, status }] }), 'ACTIVE', `${status} request is history, not a live status`);
}
eq(deriveAccountStatus({ ...u, flagged: true, archived: true }, { bannedEmails: [{ email: u.email }], selfDeletionRequests: [{ id: 5 }], deletionRequests: [] }), 'DELETION_PENDING', 'deletion pending outranks everything');
eq(deriveAccountStatus({ ...u, email: 'deleted+5+1@deleted.invalid', archived: true }, { ...all, bannedEmails: [{ email: 'person@example.test' }] }), 'SUSPENDED', 'a ban on the ORIGINAL email is not matched against the placeholder email');
eq(deriveAccountStatus({ ...u, banned: true, suspended: true, deletionRequested: true }, all), 'ACTIVE', 'fields the backend does not return are ignored');
eq(deriveAccountStatus(u, { ...all, selfDeletionRequests: [{ id: 5 }] }) === 'DELETED', false, 'Deleted is never a status');

let d = deriveAccountStatusDetail(u, {});
eq(d.status, 'ACTIVE', 'no sources loaded -> still ACTIVE ...');
eq(d.uncertain.map(x => x.status).sort(), ['BANNED', 'DELETION_PENDING'], '... but BANNED and DELETION_PENDING are reported as uncertain');
d = deriveAccountStatusDetail({ ...u, archived: true }, { bannedEmails: [], deletionRequests: [] });
eq(d.uncertain.map(x => x.status), ['DELETION_PENDING'], 'archived + self-deletion source missing -> pending self-deletion cannot be ruled out');
d = deriveAccountStatusDetail({ ...u, flagged: true }, { bannedEmails: [], deletionRequests: [] });
eq(d.uncertain, [], 'not archived -> self-deletion is impossible, nothing uncertain');
d = deriveAccountStatusDetail(u, { bannedEmails: [{ email: u.email }] });
eq(d.uncertain.map(x => x.status), ['DELETION_PENDING'], 'uncertainty is only reported for higher severities than the derived status');
eq(deriveAccountStatusDetail({ ...u, flagged: true, archived: true }, { ...all, bannedEmails: [{ email: u.email }] }).reasons.map(x => x.status), ['BANNED', 'SUSPENDED', 'FLAGGED'], 'reasons are listed most severe first');
eq(['12', '1', '9007199254740991'].map(parseUserId), [12, 1, 9007199254740991], 'valid ids');
eq(['0', '007', 'abc', '1.5', '-3', '', '9007199254740993', undefined].map(parseUserId), [null, null, null, null, null, null, null, null], 'invalid ids');

// ───────────────────────── DOM: drawer + router ─────────────────────────
async function loadJsdom() {
  const dirs = [undefined, ...(process.env.NODE_PATH || '').split(path.delimiter).filter(Boolean)];
  for (const dir of dirs) {
    try { return (dir ? createRequire(path.join(dir, 'x.js'))('jsdom') : await import('jsdom')); } catch {}
  }
  return null;
}
const jsdom = await loadJsdom();
if (!jsdom) { console.log(`Pure status checks passed (${checks}).`); console.error('SKIPPED DOM scenarios (exit 2): jsdom could not be loaded.'); process.exit(2); }
const { JSDOM } = jsdom.JSDOM ? jsdom : jsdom.default;

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function waitFor(cond, label, timeout = 3000) {
  const start = Date.now();
  while (Date.now() - start < timeout) { if (await cond()) return; await sleep(10); }
  throw new Error(`timed out waiting for: ${label}`);
}

const sharedJs = fs.readFileSync(path.join(adminDir, 'shared.js'), 'utf8');
const legacyUsersJs = fs.readFileSync(path.join(adminDir, 'legacy/users.js'), 'utf8');
const routesMod = await import(pathToFileURL(path.join(adminDir, 'routes.js')));
const routerMod = await import(pathToFileURL(path.join(adminDir, 'router.js')));

function fixtureData() {
  return {
    users: [
      { id: 101, fullName: 'Jordan Santos', email: 'jordan@example.test', isAdmin: false, flagged: true, flagReason: 'Suspicious upload', archived: false },
      { id: 102, fullName: 'Mika Reyes', email: 'mika@example.test', isAdmin: false, flagged: false, archived: false },
      { id: 103, fullName: 'Alex Rivera', email: 'alex@example.test', isAdmin: false, flagged: false, archived: true, archiveReason: 'Policy violation' },
      { id: 104, fullName: 'Taylor Cruz', email: 'deleted+104+1@deleted.invalid', isAdmin: false, flagged: false, archived: true, archiveReason: 'Account deletion requested by the user' },
      { id: 105, fullName: 'Sam Banned', email: 'sam@example.test', isAdmin: false, flagged: false, archived: false },
      { id: 106, fullName: 'Pat Admin', email: 'pat@example.test', isAdmin: true, flagged: false, archived: false },
      { id: 107, fullName: 'Dana Multi', email: 'dana@example.test', isAdmin: false, flagged: true, archived: true },
      { id: 108, fullName: 'Lee Pending', email: 'lee@example.test', isAdmin: false, flagged: false, archived: false }
    ],
    banned: [
      { id: 1, email: 'SAM@example.test', reason: 'Abuse', bannedBy: 'pat@example.test', bannedAt: '2026-10-01T10:00:00' },
      { id: 2, email: 'dana@example.test', reason: 'Hacking', bannedBy: 'pat@example.test', bannedAt: '2026-10-02T10:00:00' },
      { id: 3, email: 'taylor@example.test', reason: 'Original email only', bannedBy: 'pat@example.test', bannedAt: '2026-10-03T10:00:00' }
    ],
    selfDeletions: [{ id: 104, fullName: 'Taylor Cruz', originalEmail: 'taylor@example.test', requestedAt: '2026-10-15T11:00:00' }],
    deletionRequests: [
      { id: 102, targetEmail: 'other@example.test', status: 'PENDING', reason: 'collision check' },     // request id 102 must NOT flag user 102
      { id: 7, targetEmail: 'lee@example.test', targetName: 'Lee Pending', status: 'PENDING', reason: 'Repeated abuse', requestedBy: 'pat@example.test', approveCount: 1, requiredCount: 3 },
      { id: 8, targetEmail: 'jordan@example.test', status: 'EXECUTED', reason: 'old' }
    ],
    usage: {
      102: [{ actionType: 'quiz-submit', globalLimit: 10, personalLimit: null, limit: 10, used: 3 }, { actionType: 'material-upload', globalLimit: 5, personalLimit: 2, limit: 2, used: 1 }],
      103: []
    }
  };
}

async function boot({ hash = '#/people/users', fail = {}, data = fixtureData() } = {}) {
  const dom = new JSDOM('<!doctype html><body><div id="adminRouteNotice" role="status"></div><div id="adminContent" tabindex="-1"></div><div id="modal" style="display:none;"></div></body>', { url: 'http://localhost/admin.html' + hash, runScripts: 'outside-only', pretendToBeVisual: true });
  const win = dom.window;
  win.scrollTo = () => {};
  win.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  win.HTMLDialogElement.prototype.close = function () { if (this.hasAttribute('open')) { this.removeAttribute('open'); this.dispatchEvent(new win.Event('close')); } };
  Object.assign(globalThis, { window: win, document: win.document, Element: win.Element, Node: win.Node, MutationObserver: win.MutationObserver });

  const calls = [];
  const apiFetch = async (p, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    const url = new URL(p, 'http://x');
    const pth = url.pathname;
    calls.push(`${method} ${pth}`);
    await sleep(1);
    const failure = fail[pth];
    if (failure) { if (failure.once) delete fail[pth]; throw new Error(failure.message || failure); }
    if (pth === '/api/admin/users' && method === 'GET') return { success: true, users: data.users.map(x => ({ ...x })) };
    if (pth === '/api/admin/banned-emails' && method === 'GET') return { success: true, bannedEmails: data.banned };
    if (pth === '/api/admin/self-deletion-requests') return { success: true, requests: data.selfDeletions };
    if (pth === '/api/admin/deletion-requests' && method === 'GET') return { success: true, requests: data.deletionRequests };
    if (pth === '/api/admin/activity-log') return { success: true, log: [] };
    let m = pth.match(/^\/api\/admin\/users\/(\d+)\/daily-usage$/);
    if (m) return { success: true, email: 'x', usage: data.usage[m[1]] || [] };
    if (pth === '/api/admin/archive-user') { const b = JSON.parse(options.body); const x = data.users.find(y => y.email === b.email); x.archived = true; x.archiveReason = b.reason; return { success: true }; }
    if (pth === '/api/admin/unflag-user') { const b = JSON.parse(options.body); data.users.find(y => y.email === b.email).flagged = false; return { success: true }; }
    throw new Error('unexpected request ' + method + ' ' + p);
  };
  win.apiFetch = apiFetch;
  win.eval(`
    var adminData = { users: [], bannedEmails: [], activityLog: [] };
    var userFlagFilter = 'all';
    var crAllMaterials = [];
    var ACTION_TYPE_LABELS = { 'quiz-submit': 'Quiz Submissions', 'material-upload': 'Material Uploads' };
    function refreshDeletionBadge() {}
    async function fetchBannedEmails() { return (await apiFetch('/api/admin/banned-emails')).bannedEmails || []; }
    function renderCurrentTab() { return renderUsers(); }
    // mirrored from admin.js (they are not part of admin/legacy/users.js)
    async function fetchActivityLog() { try { return (await apiFetch('/api/admin/activity-log')).log || []; } catch (e) { return []; } }
    async function refreshActivityLog() { adminData.activityLog = await fetchActivityLog(); }
    async function refreshUsers() {
      const resp = await apiFetch('/api/admin/users');
      adminData.users = resp.users || [];
      adminData.flaggedUsers = adminData.users.filter(u => u.flagged).map(u => u.email);
    }
  `);
  win.eval(sharedJs);
  win.eval(legacyUsersJs);
  win.adminData.users = data.users.map(x => ({ ...x }));
  win.adminData.bannedEmails = data.banned;
  globalThis.adminData = win.adminData;
  globalThis.ACTION_TYPE_LABELS = win.ACTION_TYPE_LABELS;
  calls.length = 0;

  const ui = (await import(pathToFileURL(path.join(adminDir, 'ui.js')))).default;
  const errors = [];
  const router = routerMod.createRouter({
    routes: routesMod.routes, aliases: routesMod.aliases, defaultPath: routesMod.defaultPath,
    mount: win.document.getElementById('adminContent'),
    hooks: { getContext: () => ({ api: { get: apiFetch, request: apiFetch }, ui }), onError: (e, match, phase) => errors.push(`${phase}: ${e.message}`) }
  });
  await router.start();
  const $ = sel => win.document.querySelector(sel);
  const $$ = sel => [...win.document.querySelectorAll(sel)];
  const drawer = () => $('dialog.admin-ui-drawer[open]');
  const drawerText = () => (drawer()?.textContent || '').replace(/\s+/g, ' ');
  const rowFor = id => $$('#adminContent .admin-table tbody tr').find(tr => tr.querySelector(`button[data-action="view-usage"][data-id="${id}"]`));
  const button = (scope, label) => [...scope.querySelectorAll('button')].find(b => b.textContent.trim() === label);
  async function finish() {
    win.history.replaceState(null, '', '#/overview');   // leaving Users: lets the adapter drop remembered list context
    await router.destroy();
  }
  return { dom, win, router, calls, errors, data, fail, $, $$, drawer, drawerText, rowFor, button, finish, apiFetch };
}

const scenarios = [];
const scenario = (name, fn) => scenarios.push([name, fn]);

scenario('direct navigation opens the drawer over the list; no endpoint outside the existing API is called', async () => {
  const t = await boot({ hash: '#/people/users/102' });
  await waitFor(() => t.drawerText().includes('Mika Reyes') && t.drawerText().includes('Quiz Submissions'), 'drawer ready with usage');
  ok(t.$$('#adminContent .admin-table tbody tr').length === 8, 'list is rendered under the drawer');
  eq(t.$('dialog.admin-ui-drawer .admin-ui-drawer__title').textContent, 'Mika Reyes', 'title is the user name');
  ok(t.drawerText().includes('ACTIVE'), 'plain user is ACTIVE (admin request id 102 did not leak onto user 102)');
  ok(t.drawerText().includes('3') && t.drawerText().includes('2 (personal)'), 'usage shows used and personal cap');
  const known = new Set(['GET /api/admin/users', 'GET /api/admin/banned-emails', 'GET /api/admin/self-deletion-requests', 'GET /api/admin/deletion-requests', 'GET /api/admin/users/102/daily-usage']);
  ok(t.calls.every(c => known.has(c)), 'only existing endpoints were called: ' + [...new Set(t.calls)].join(', '));
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('list status column shows one normalized status per user', async () => {
  const t = await boot();
  await waitFor(() => t.$$('#adminContent .account-status').length === 8, 'badges decorated');
  const status = id => t.rowFor(id).querySelector('.account-status').textContent;
  eq([101, 102, 103, 104, 105, 106, 107, 108].map(status), ['FLAGGED', 'ACTIVE', 'SUSPENDED', 'DELETION PENDING', 'BANNED', 'ACTIVE', 'BANNED', 'DELETION PENDING'], 'normalized list statuses');
  ok(t.rowFor(103).querySelectorAll('.account-status').length === 1, 'exactly one badge per row');
  ok(t.rowFor(102).querySelector('a.user-link').getAttribute('href') === '#/people/users/102', 'name is a canonical hash link');
  await t.finish();
});

scenario('status details: banned, self-deletion, admin deletion request, uncertain sources', async () => {
  let t = await boot({ hash: '#/people/users/105' });
  await waitFor(() => t.drawerText().includes('Sam Banned') && t.drawerText().includes('Ban reason'), 'banned detail');
  ok(t.drawerText().includes('BANNED') && t.drawerText().includes('Abuse') && t.drawerText().includes('pat@example.test'), 'ban reason and actor shown');
  ok(t.button(t.drawer(), 'Unban email'), 'unban offered for banned email');
  await t.finish();

  t = await boot({ hash: '#/people/users/104' });
  await waitFor(() => t.drawerText().includes('Taylor Cruz') && t.drawerText().includes('Original email'), 'self-deletion detail');
  ok(t.drawerText().includes('DELETION PENDING') && t.drawerText().includes('taylor@example.test'), 'original email shown');
  ok(!t.drawerText().includes('BANNED'), 'a ban on the original email does not make the placeholder account BANNED');
  ok(!t.button(t.drawer(), 'Restore') && !t.button(t.drawer(), 'Suspend') && !t.button(t.drawer(), 'Ban email') && t.button(t.drawer(), 'Open deletion requests'), 'status-changing actions are withheld for self-deletion accounts');
  await t.finish();

  t = await boot({ hash: '#/people/users/108' });
  await waitFor(() => t.drawerText().includes('Lee Pending') && t.drawerText().includes('Admin deletion request'), 'admin deletion detail');
  ok(t.drawerText().includes('DELETION PENDING') && t.drawerText().includes('1 of 3 approvals'), 'admin deletion request summarized');
  ok(t.button(t.drawer(), 'Suspend'), 'actions stay available when only an admin request is open');
  await t.finish();

  t = await boot({ hash: '#/people/users/103', fail: { '/api/admin/banned-emails': 'down' } });
  await waitFor(() => t.drawerText().includes('Alex Rivera') && t.drawerText().includes('cannot be ruled out'), 'uncertain note');
  ok(t.drawerText().includes('SUSPENDED') && t.drawerText().includes('Banned emails could not be loaded'), 'status stays SUSPENDED and the unverified ban is disclosed');
  await t.finish();
});

scenario('not-found, invalid id, and API-error states', async () => {
  let t = await boot({ hash: '#/people/users/999' });
  await waitFor(() => t.drawerText().includes('User not found') && t.drawerText().includes('No user with ID 999'), 'not found');
  await t.finish();

  t = await boot({ hash: '#/people/users/abc' });
  await waitFor(() => t.drawerText().includes('not a valid user ID'), 'invalid id');
  await t.finish();

  t = await boot({ hash: '#/people/users/102', fail: { '/api/admin/users': { message: 'Boom', once: false } } });
  // the list itself loaded from adminData; the drawer's own fetch fails
  await waitFor(() => t.drawerText().includes('Boom'), 'error text');
  ok(t.button(t.drawer(), 'Retry'), 'retry offered');
  delete t.fail['/api/admin/users'];
  t.button(t.drawer(), 'Retry').click();
  await waitFor(() => t.drawerText().includes('Mika Reyes') && t.drawerText().includes('Quiz Submissions'), 'retry succeeded');
  await t.finish();
});

scenario('usage section has its own error and empty states', async () => {
  let t = await boot({ hash: '#/people/users/102', fail: { '/api/admin/users/102/daily-usage': { message: 'Usage service down', once: true } } });
  await waitFor(() => t.drawerText().includes('Could not load usage: Usage service down'), 'usage error');
  ok(t.drawerText().includes('Mika Reyes') && t.drawerText().includes('ACTIVE'), 'account details still shown');
  t.button(t.drawer(), 'Retry').click();
  await waitFor(() => t.drawerText().includes('Quiz Submissions'), 'usage retry');
  await t.finish();

  t = await boot({ hash: '#/people/users/103' });
  await waitFor(() => t.drawerText().includes('No daily limits are configured.'), 'usage empty');
  await t.finish();
});

scenario('list -> detail pushes history; closing returns to the list with search, filter and focus preserved', async () => {
  const t = await boot();
  await waitFor(() => t.$$('#adminContent .admin-table tbody tr').length === 8, 'list');
  t.win.setUserFlagFilter('all');
  t.win.renderUsers('mika');                                  // what typing in the search box does
  const visible = t.$$('#adminContent .admin-table tbody tr').length;
  eq(visible, 1, 'search narrowed the list');
  const before = t.win.history.length;
  t.rowFor(102).querySelector('a.user-link').click();
  await waitFor(() => t.drawer() && t.drawerText().includes('Mika Reyes'), 'drawer open after click');
  eq(t.win.location.hash, '#/people/users/102', 'canonical hash');
  eq(t.win.history.length, before + 1, 'one history entry pushed');
  t.button(t.drawer(), 'Close').click();
  await waitFor(() => !t.drawer() && t.win.location.hash === '#/people/users' && t.$$('#adminContent .admin-table tbody tr').length === visible, 'back on filtered list');
  eq(t.win.history.length, before + 1, 'closing used history.back(): no new entry');
  eq(t.$('#userSearchInput').value, 'mika', 'search text preserved');
  await waitFor(() => t.win.document.activeElement?.classList.contains('user-link') && t.win.document.activeElement.dataset.userId === '102', 'focus returns to the opened row');
  await t.finish();
});

scenario('browser Back/Forward close and reopen the drawer', async () => {
  const t = await boot();
  await waitFor(() => t.$$('#adminContent .admin-table tbody tr').length === 8, 'list');
  t.rowFor(103).querySelector('a.user-link').click();
  await waitFor(() => t.drawerText().includes('Alex Rivera'), 'open');
  t.win.history.back();
  await waitFor(() => !t.drawer() && t.win.location.hash === '#/people/users', 'Back closes drawer');
  t.win.history.forward();
  await waitFor(() => t.drawerText().includes('Alex Rivera') && t.win.location.hash === '#/people/users/103', 'Forward reopens drawer');
  t.button(t.drawer(), 'Close').click();           // reached by Forward: replace instead of back()
  await waitFor(() => !t.drawer() && t.win.location.hash === '#/people/users', 'close after Forward lands on the list');
  eq(t.errors, [], 'no router errors');
  await t.finish();
});

scenario('close after a direct visit replaces the entry instead of leaving the page', async () => {
  const t = await boot({ hash: '#/people/users/102' });
  await waitFor(() => t.drawerText().includes('Mika Reyes'), 'open');
  const before = t.win.history.length;
  t.button(t.drawer(), 'Close').click();
  await waitFor(() => !t.drawer() && t.win.location.hash === '#/people/users', 'list');
  eq(t.win.history.length, before, 'history length unchanged (replace)');
  await t.finish();
});

scenario('Escape closes the drawer and routes to the list', async () => {
  const t = await boot({ hash: '#/people/users/102' });
  await waitFor(() => t.drawerText().includes('Mika Reyes'), 'open');
  t.drawer().dispatchEvent(new t.win.Event('close'));    // native dialog Escape ends in a close event
  await waitFor(() => !t.drawer() && t.win.location.hash === '#/people/users', 'list after Escape');
  await t.finish();
});

scenario('administrative actions: drawer parks behind the legacy modal and reopens with fresh status', async () => {
  const t = await boot({ hash: '#/people/users/102' });
  await waitFor(() => t.drawerText().includes('Mika Reyes'), 'open');
  t.button(t.drawer(), 'Suspend').click();
  ok(!t.drawer(), 'drawer is parked while the legacy modal is shown');
  ok(t.$('#modal').style.display !== 'none' && t.$('#modal').textContent.includes('Suspend'), 'legacy suspend modal visible');
  eq(t.win.location.hash, '#/people/users/102', 'URL unchanged while parked');
  t.win.closeModal();                                         // Cancel
  await waitFor(() => t.drawer(), 'drawer reopens after cancel');
  await waitFor(() => t.drawerText().includes('Mika Reyes') && t.drawerText().includes('ACTIVE'), 'reloaded');

  t.button(t.drawer(), 'Suspend').click();
  t.$('#archiveReason').value = 'Testing';
  t.$('#confirmArchiveBtn').click();
  await waitFor(() => t.drawer() && t.drawerText().includes('SUSPENDED') && t.drawerText().includes('Testing'), 'drawer reopens showing SUSPENDED');
  await waitFor(() => t.rowFor(102).querySelector('.account-status').textContent === 'SUSPENDED', 'list row updated too');
  ok(t.calls.includes('POST /api/admin/archive-user'), 'existing archive endpoint was used');
  await t.finish();
});

scenario('unflag (no modal) also reopens the drawer', async () => {
  const t = await boot({ hash: '#/people/users/101' });
  await waitFor(() => t.drawerText().includes('Jordan Santos') && t.drawerText().includes('Suspicious upload'), 'open');
  t.button(t.drawer(), 'Unflag').click();
  await waitFor(() => t.drawer() && t.drawerText().includes('ACTIVE'), 'drawer reopened as ACTIVE');
  await t.finish();
});

scenario('usage editing reuses the legacy modal', async () => {
  const t = await boot({ hash: '#/people/users/102' });
  await waitFor(() => t.drawerText().includes('Quiz Submissions'), 'open');
  t.button(t.drawer(), 'Edit usage').click();
  await waitFor(() => t.$('#modal').textContent.includes('Daily usage'), 'legacy usage modal');
  await waitFor(() => t.$('[data-usage-save]'), 'usage rows rendered');
  t.win.closeModal();
  await waitFor(() => t.drawer(), 'drawer back');
  await t.finish();
});

scenario('unmount cleans up: drawer, render wrapper, listeners, stale notice', async () => {
  const t = await boot({ hash: '#/people/users/102' });
  await waitFor(() => t.drawer(), 'open');
  ok(t.win.renderUsers.__peopleOriginal, 'renderUsers is wrapped while mounted');
  await t.finish();
  ok(!t.win.document.querySelector('dialog'), 'no dialog left in the DOM');
  ok(!t.win.renderUsers.__peopleOriginal, 'original renderUsers restored');

  const n = await boot({ hash: '#/people/users/102' });
  n.$('#adminRouteNotice').textContent = 'Warning: User detail is not available yet: its backend endpoint does not exist.';
  n.$('#adminRouteNotice').hidden = false;
  await n.router.reload();
  await waitFor(() => n.drawer(), 'reopened');
  ok(n.$('#adminRouteNotice').hidden, 'stale "not available" notice is hidden once the drawer works');
  await n.finish();
});

for (const [name, fn] of scenarios) {
  try { await fn(); console.log('  ok   ' + name); }
  catch (error) { console.error('  FAIL ' + name + '\n', error); process.exitCode = 1; }
}
if (process.exitCode) { console.error('People/Users tests FAILED'); process.exit(1); }
console.log(`People/Users tests passed (${checks} assertions, ${scenarios.length} DOM scenarios; jsdom, not a real browser).`);
process.exit(0);
