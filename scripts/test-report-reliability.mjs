/**
 * Node-only checks for the dedicated Platform report and Reliability views.
 * No browser, no jsdom, no backend. Run: node scripts/test-report-reliability.mjs
 * Browser behaviour (rendering, print emulation, pagination) is in scripts/smoke-report-reliability.mjs.
 */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import routes, { aliases, defaultPath } from '../src/main/resources/static/admin/routes.js';
import * as M from '../src/main/resources/static/admin/views/platform-report-model.js';

const root = new URL('../src/main/resources/static/', import.meta.url);
const read = (p) => readFile(new URL(p, root), 'utf8');
let n = 0;
const ok = (c, m) => { assert.ok(c, m); n++; };
const eq = (a, b, m) => { assert.deepEqual(a, b, m); n++; };
const byPath = Object.fromEntries(routes.map((r) => [r.path, r]));
const src = (path) => String((byPath[path].shellLoad || byPath[path].load));

// ── 1. Registry: everything integrated before is still there ──────────────────────────────────
eq(routes.map((r) => r.path), [
  '/overview', '/people/users', '/people/users/:id', '/people/deletion-requests', '/people/banned-emails',
  '/content/materials', '/content/reported-questions', '/content/categories',
  '/study/participants', '/study/assigned-handouts', '/study/data-exports',
  '/system/usage-limits', '/system/configuration', '/system/ai-testing', '/system/reliability',
  '/audit/activity-log', '/audit/platform-report'], 'canonical paths and order unchanged');
eq({ ...aliases }, {
  overview: '/overview', users: '/people/users', topics: '/content/materials', reportedquestions: '/content/reported-questions',
  bannedemails: '/people/banned-emails', deletionrequests: '/people/deletion-requests', reports: '/audit/platform-report',
  activitylog: '/audit/activity-log', assignedhandouts: '/study/assigned-handouts', aitesting: '/system/ai-testing'
}, 'all ten legacy hash aliases, including #reports -> platform report');
eq(defaultPath, '/overview');

// Existing mappings (module + legacyTab + after), exactly as integrated.
const expected = {
  '/overview': ['overview.js', 'overview'],
  '/people/users': ['users.js', 'users'],
  '/people/deletion-requests': ['deletionrequests.js', 'deletionrequests'],
  '/people/banned-emails': ['bannedemails.js', 'bannedemails'],
  '/content/materials': ['topics.js', 'topics'],
  '/content/categories': ['topics.js', 'topics'],
  '/content/reported-questions': ['reportedquestions.js', 'reportedquestions'],
  '/study/participants': ['study-participants.js', undefined],
  '/study/assigned-handouts': ['assignedhandouts.js', undefined],   // Phase 5: dedicated view, no legacyTab
  '/study/data-exports': ['study-data-exports.js', undefined],
  '/system/usage-limits': ['users.js', 'users'],
  '/system/ai-testing': ['aitesting.js', 'aitesting'],
  '/audit/activity-log': ['activitylog.js', 'activitylog']
};
for (const [path, [file, tab]] of Object.entries(expected)) {
  ok(String(byPath[path].load).includes('./views/' + file), `${path} loads ${file}`);
  eq(byPath[path].legacyTab, tab, `${path} legacyTab`);
}
ok(String(byPath['/people/users/:id'].load).includes('user-detail.js') && String(byPath['/people/users/:id'].shellLoad).includes('./views/users.js'), 'user detail: own module, Users list mounted by the shell');
eq(byPath['/people/users/:id'].legacyTab, 'users');
eq(byPath['/content/materials'].after, { subTab: 'materials' });
eq(byPath['/content/categories'].after, { subTab: 'categories' });
eq(byPath['/system/usage-limits'].after, { modal: 'openDailyLimitsModal' });
ok(String(byPath['/system/configuration'].load).includes('./views/configuration.js'), 'Configuration loads its dedicated module');
eq(byPath['/system/configuration'].status, 'ready', 'Configuration is implemented');
for (const p of ['/study/participants', '/study/data-exports']) ok(!byPath[p].after, `${p} has no after-actions`);

// ── 2. The two new routes ──────────────────────────────────────────────────────────────────────
for (const [path, file] of [['/audit/platform-report', 'platform-report.js'], ['/system/reliability', 'reliability.js']]) {
  ok(src(path).includes('./views/' + file), `${path} loads ${file}`);
  ok(!src(path).includes('views/reports.js'), `${path} no longer loads the legacy Reports adapter`);
  ok(!('legacyTab' in byPath[path]), `${path} has no legacyTab, so renderCurrentTab()/setCategoryRange() cannot re-render the legacy screen over it`);
  ok(!byPath[path].shellLoad, `${path} mounts its own module`);
}
ok(!routes.some((r) => String(r.shellLoad || r.load).includes('views/reports.js')), 'no route mounts the legacy Reports adapter');
eq(byPath['/audit/platform-report'].status, 'ready');
eq(byPath['/audit/platform-report'].after, { focus: '#printTarget' });
eq(byPath['/system/reliability'].status, 'partial', 'Reliability stays honestly "partial"');
eq(byPath['/system/reliability'].after.focus, 'a[href="/api/admin/export/reliability.csv"]');
ok(/export-only/i.test(byPath['/system/reliability'].after.notice.text) && /only reliability feature/i.test(byPath['/system/reliability'].after.notice.text));
ok(!/existing study reports screen|dashboard is available/i.test(byPath['/system/reliability'].after.notice.text), 'notice no longer mentions the legacy Reports screen');
for (const p of ['/audit/platform-report', '/system/reliability']) {
  const view = (await byPath[p].load()).default;
  eq([typeof view.mount, typeof view.unmount], ['function', 'function'], `${p} honours mount/unmount`);
}

// ── 3. Legacy pieces are still present (compatibility) ─────────────────────────────────────────
const legacy = await read('admin/legacy/reports.js');
ok(/function renderReports\(/.test(legacy) && /function copyReportText\(/.test(legacy), 'legacy renderer untouched and available');
ok(!existsSync(new URL('../src/main/resources/static/admin/views/reports.js', import.meta.url)), 'unused legacy Reports adapter module has been removed');
const adminHtml = await read('admin.html');
ok(adminHtml.includes('admin/legacy/reports.js'), 'legacy reports bundle still loaded by admin.html');
for (const sel of ['#printExclude', '#participantsCard', '#printTarget .admin-card', '#printTarget .table-scroll', '#printTarget thead', '#printTarget tr', '#printTarget .admin-card-title', '#printTarget hr']) ok(adminHtml.includes(sel), `print stylesheet still has ${sel}`);
ok(/#printTarget,\s*#printTarget \.admin-card\s*\{[^}]*break-inside:\s*auto !important/.test(adminHtml), 'card still allowed to break across pages');
ok(/size:\s*A4 portrait/.test(adminHtml));

// ── 4. Platform report: sources & semantics ────────────────────────────────────────────────────
const viewSrc = await read('admin/views/platform-report.js');
const modelSrc = await read('admin/views/platform-report-model.js');
const relSrc = await read('admin/views/reliability.js');
for (const id of ['printExclude', 'printTarget', 'reportsRangeFilter']) ok(viewSrc.includes(`"${id}"`) || viewSrc.includes(`id: "${id}"`), `keeps #${id}`);
for (const text of ['Platform Administration Report', 'Confidential — Admin Use Only', '1. Platform Statistics', '2. Material Categorization Breakdown', '3. Security & Moderation Summary', '4. Banned Email Log', '5. Account Deletion Log', '6. Registered Users', '7. Active Topics', 'Print / Save as PDF', 'Copy Report Text', 'window.print()', 'Material categorization range: ']) ok(viewSrc.includes(text), `report keeps "${text}"`);
for (const text of ['Total Registered Users', 'Total Topics', 'Total Questions', 'Total Quiz Attempts', 'Total Uploaded Materials']) ok(modelSrc.includes(text));
ok(!/innerHTML/.test(viewSrc + relSrc + await read('admin/views/view-dom.js')), 'no innerHTML in the new views');

const strip = (t) => t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
const reportCode = strip(viewSrc + modelSrc).replace(/#\/study\/[a-z-]+/g, '<study-link>');
const forbidden = ['enrollEmails', 'enrollParticipants', 'createStudyAccounts', 'wipeParticipantKey', 'participantsCard', 'participantList', 'mapping.csv', 'participants', 'assigned-handouts', 'ahAssignBtn', '/api/admin/export/attempts', 'attempt_questions', 'question_exposures', 'sus.csv', 'review_questions', 'review_sources', 'Identity key', 'Enrol'];
for (const f of forbidden) ok(!reportCode.includes(f), `Platform report has no "${f}"`);
ok(!/\/api\/admin\/export\//.test(reportCode), 'Platform report has no export links at all');

const apiPaths = new Set([...reportCode.matchAll(/["'`](\/api\/[^"'`?$]*)/g)].map((m) => m[1]));
eq([...apiPaths].sort(), ['/api/admin/activity-log', '/api/admin/banned-emails', '/api/admin/material-categories', '/api/admin/overview', '/api/admin/topics', '/api/admin/users'], 'only existing, documented read endpoints; no writes, no new endpoints');
ok(!/method:\s*["'](POST|PUT|DELETE|PATCH)/i.test(reportCode), 'report is read-only');

eq(M.rangeLabel('week'), 'This Week (last 7 days)');
eq(M.rangeLabel('month'), 'This Month (last 30 days)');
eq(M.rangeLabel('all'), 'All Time (universal timeline)');
eq(M.rangeLabel('bogus'), 'All Time (universal timeline)', 'legacy fallback');
eq(M.normalizeRange('week'), 'week'); eq(M.normalizeRange('x'), 'all');
eq(M.CATEGORY_RANGES.map((r) => r.key), ['week', 'month', 'all']);
eq(M.statRows({ totalUsers: 4, totalQuestions: 9 }), [['Total Registered Users', 4], ['Total Topics', 0], ['Total Questions', 9], ['Total Quiz Attempts', 0], ['Total Uploaded Materials', 0]]);
const mc = { totalMaterials: 8, topCategory: 'Math', categoryBreakdown: [{ category: 'Math', count: 5 }, { category: 'Empty', count: 0 }, { category: 'Sci', count: 3 }], subCategoryBreakdown: [{ subCategory: 'Algebra', count: 3 }] };
eq(M.categoryRows(mc), [{ n: 1, name: 'Math', count: 5, pct: 63 }, { n: 2, name: 'Sci', count: 3, pct: 38 }], 'zero-count categories hidden, share = round(count/totalMaterials*100)');
eq(M.subCategoryRows(mc), [{ n: 1, name: 'Algebra', count: 3, pct: 38 }]);
eq(M.categoryRows({ categoryBreakdown: [{ category: 'A', count: 2 }] })[0].pct, 0, 'no totalMaterials -> 0%');
const users = [{ email: 'a@x', fullName: 'A', archived: true }, { email: 'B@x', fullName: 'B', flagged: true }, { email: 'c@x', fullName: 'C', isAdmin: true }, { email: 'd@x', fullName: 'D', flagged: true, archived: true }];
const sec = M.deriveSecurity({ users, banned: [{ email: 'b@X' }], activity: [{ type: 'delete', message: 'm' }, { type: 'login' }] });
eq([sec.suspended, sec.flagged, sec.deleted.length, sec.blocked.length], [['a@x', 'd@x'], ['B@x', 'd@x'], 1, 1]);
eq(users.map((u) => M.userStatus(u, sec)), ['Suspended', 'Email Banned', 'Active', 'Suspended'], 'status precedence: banned (case-insensitive) > suspended > flagged > active');

// loadReportData: requested URLs, range handling, failure isolation
const seen = [];
const mk = (fail = {}) => ({ get: async (path, opts) => {
  seen.push([path, !!(opts && opts.signal)]);
  const key = Object.keys(fail).find((k) => path.includes(k));
  if (key) throw new Error(fail[key]);
  if (path.includes('overview')) return { stats: { totalUsers: 1 } };
  if (path.includes('/users')) return { users: [] };
  if (path.includes('topics')) return ['T1'];
  if (path.includes('material-categories')) return mc;
  if (path.includes('banned')) return { bannedEmails: [] };
  return { log: [] };
} });
const sig = new AbortController().signal;
let d = await M.loadReportData(mk(), 'week', { signal: sig });
eq(seen.map((s) => s[0]).sort(), ['/api/admin/activity-log', '/api/admin/banned-emails', '/api/admin/material-categories?range=week', '/api/admin/overview', '/api/admin/topics', '/api/admin/users']);
ok(seen.every((s) => s[1]), 'requests are abortable');
eq(M.failedSources(d), []);
d = await M.loadReportData(mk({ banned: 'boom' }), 'all');
eq(M.failedSources(d), ['banned']); ok(d.stats.ok && d.users.ok && d.categories.ok, 'one failed source does not discard the others');
ok(d.banned.error.message === 'boom' && d.banned.data === null, 'failed source carries no fabricated data');
seen.length = 0;
const c = await M.loadCategories(mk(), 'month'); eq(seen.map((s) => s[0]), ['/api/admin/material-categories?range=month']); ok(c.ok);
ok(!(await M.loadCategories({ get: async () => 'nope' }, 'all')).ok, 'non-object categories response is a failure');
eq((await M.loadReportData({ get: async (p) => (p.includes('topics') ? { not: 'array' } : p.includes('overview') ? {} : p.includes('material') ? mc : {}) }, 'all')).topics.data, [], 'non-array topics -> [] as in the legacy loader');

// ── 5. Reliability ─────────────────────────────────────────────────────────────────────────────
ok(relSrc.includes('/api/admin/export/reliability.csv'), 'links the existing CSV');
const relCode = relSrc.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
const relApi = [...relCode.matchAll(/\/api\/[A-Za-z0-9_\/.-]+/g)].map((m) => m[0]);
ok(relApi.length >= 1 && relApi.every((u) => u === '/api/admin/export/reliability.csv'), 'only the one existing endpoint is referenced');
ok(!/apiFetch|ctx\.api|fetch\(/.test(relCode), 'Reliability makes no API requests');
ok(/export-only/i.test(relSrc) && /no reliability dashboard/i.test(relSrc), 'states the limitation');
ok(!/renderReports|printTarget|enrollEmails|attempts\.csv|participants/.test(relCode), 'no legacy Reports/Study content');
ok(!/uptime|latency|availability|SLA|\d+(\.\d+)?\s?%/i.test(relCode), 'no metric-like wording in rendered text');

console.log(`Report / Reliability checks passed (${n} assertions): registry + aliases + Study mappings intact, report sources/semantics, no Study duplication, Reliability export-only.`);
