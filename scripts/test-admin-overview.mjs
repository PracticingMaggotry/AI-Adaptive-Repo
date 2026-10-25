/**
 * Focused Overview tests (model, route targets, backend endpoint existence, dev mock API, view/redirect static checks).
 * Run: node scripts/test-admin-overview.mjs
 * DOM behavior (loading/error/empty states, dedupe on screen, charts, redirect in a browser) is covered by
 * scripts/smoke-admin-overview.mjs, which needs Playwright + Chromium.
 */
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import routes, { aliases } from '../src/main/resources/static/admin/routes.js';
import * as M from '../src/main/resources/static/admin/overview-model.js';
import overviewView, { OVERVIEW_SOURCES } from '../src/main/resources/static/admin/views/overview.js';
import vm from 'node:vm';

const root = new URL('../', import.meta.url);
const read = (rel) => readFile(new URL(rel, root), 'utf8');
const stripComments = (t) => t.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '').replace(/<!--[\s\S]*?-->/g, '');
let n = 0;
const ok = (v, m) => { n += 1; assert.ok(v, m); };
const eq = (a, b, m) => { n += 1; assert.deepEqual(a, b, m); };

// ── Route targets ─────────────────────────────────────────────────────────────────────────────
const navPaths = new Set(routes.filter((r) => r.nav !== false).map((r) => r.path));
for (const [name, path] of Object.entries(M.ROUTES)) ok(navPaths.has(path), `ROUTES.${name} -> ${path} is a canonical sidebar route`);
for (const a of M.QUICK_ACTIONS) { ok(navPaths.has(a.path), `quick action "${a.label}" -> ${a.path}`); ok(!a.path.startsWith('#') && !a.path.startsWith('/admin/'), 'internal hash path'); }
eq(M.toHref('/people/users'), '#/people/users');
// Everything the old dashboard and the old Overview offered as a destination is still reachable from Quick actions.
const oldDestinations = ['reportedquestions', 'users', 'topics', 'bannedemails', 'deletionrequests', 'reports'];
for (const old of oldDestinations) ok(M.QUICK_ACTIONS.some((a) => a.path === aliases[old]), `old destination "${old}" kept as a quick action`);
eq(new Set(M.QUICK_ACTIONS.map((a) => a.label)).size, M.QUICK_ACTIONS.length, 'no duplicate quick actions');
eq(M.CATEGORY_RANGES.map((r) => r.id), ['week', 'month', 'all']);

// ── KPIs and ratios ───────────────────────────────────────────────────────────────────────────
const stats = { totalUsers: 12, totalTopics: 4, totalQuestions: 41, totalAttempts: 30, totalMaterials: 9 };
const users = [{ id: 1, isAdmin: true }, { id: 2 }, { id: 3 }];
const kpis = M.buildKpis(stats, users);
eq(kpis.map((k) => k.label), ['Registered Users', 'Topics', 'Questions', 'Quiz Attempts', 'Uploaded Materials'], 'five KPI tiles, legacy labels');
eq(kpis.map((k) => k.value), [12, 4, 41, 30, 9]);
eq(kpis[0].hint, '11 students · 1 admins');
eq(kpis[1].hint, '9 uploaded materials'); eq(kpis[2].hint, '10 avg per topic');
eq(M.buildKpis(null, null).map((k) => k.value), [null, null, null, null, null], 'missing stats stay null, never 0');
eq(M.buildKpis({ totalUsers: 5 }, null)[0].hint, null, 'no user hint without the users list');
eq(M.buildKpis({ totalUsers: 5, totalTopics: 0, totalQuestions: 3 }, null)[2].hint, null, 'no divide-by-zero hint');
eq(M.platformRatios(stats).map((r) => r.value), ['10', '3', '0.8']);
eq(M.platformRatios({ totalUsers: 0, totalTopics: 0, totalQuestions: 5, totalAttempts: 5, totalMaterials: 5 }).map((r) => r.value), [null, null, null], 'zero denominators -> null');
eq(M.platformRatios(null), []);

// ── Accounts ──────────────────────────────────────────────────────────────────────────────────
const accts = [
  { id: 1, email: 'A@x.test' }, { id: 2, email: 'b@x.test', archived: true }, { id: 3, email: 'c@x.test', flagged: true },
  { id: 4, email: 'd@x.test', archived: true, flagged: true }, { id: 5, email: 'e@x.test' }
];
const br = M.accountBreakdown(accts, [{ email: 'a@X.test' }, { email: 'd@x.test' }, { email: 'zz@x.test' }], [{ type: 'delete' }, { type: 'flag' }, { type: 'delete' }]);
eq(br, { active: 2, suspended: 1, banned: 2, flagged: 2, bannedEmails: 3, deletedInLog: 2 }, 'banned matching is case-insensitive; suspended excludes banned; banned-list size is independent');
eq(M.accountBreakdown(null, null, null), { active: 0, suspended: 0, banned: 0, flagged: 0, bannedEmails: 0, deletedInLog: 0 });
eq(M.activityPresentation('ban-email'), { icon: '🚫', tone: 'red' }); eq(M.activityPresentation('mystery'), { icon: '📝', tone: 'gray' });
const act = M.recentActivity([{ message: 'old', time: '2026-01-01T00:00:00' }, { message: 'new', time: '2026-03-01T00:00:00' }, { message: '' }, { message: 'mid', time: '2026-02-01T00:00:00' }], 2);
eq(act.map((a) => a.message), ['new', 'mid'], 'newest first, blanks dropped, limited');
eq(M.recentBanned([{ email: 'a', bannedAt: '2026-01-01T00:00:00' }, { email: 'b', bannedAt: '2026-02-01T00:00:00' }, {}], 1).map((b) => b.email), ['b']);

// ── Quiz analytics ────────────────────────────────────────────────────────────────────────────
const hist = [{ topic: 'Algebra', difficulty: 'easy', score: 90 }, { topic: 'algebra', difficulty: 'HARD', score: 40 }, { topic: 'Bio', difficulty: 'weird', score: 60 }, { topic: 'Bio', difficulty: '', score: 70 }];
eq(M.difficultyBuckets(hist), { Easy: 1, Medium: 0, Hard: 1, Other: 1 }, 'blank difficulty is ignored, unknown goes to Other');
eq(M.scoreBuckets(hist), { '0-49%': 1, '50-64%': 1, '65-79%': 1, '80-100%': 1 });
eq(M.averageScore(hist), 65); eq(M.averageScore([]), null);
const ts = M.topicStats(hist);
eq(ts.get('algebra'), { attempts: 2, avgScore: 65 }, 'topic keys are case-insensitive');
eq([M.topicPerformance(ts.get('algebra')).tone, M.topicPerformance({ attempts: 1, avgScore: 85 }).tone, M.topicPerformance({ attempts: 1, avgScore: 55 }).tone, M.topicPerformance({ attempts: 1, avgScore: 10 }).tone, M.topicPerformance(undefined).tone], ['ok', 'strong', 'weak', 'bad', 'none']);

// ── Inbox: reported questions ─────────────────────────────────────────────────────────────────
const rep = (id, qid, extra = {}) => ({ id, questionId: qid, topic: 'Algebra', reason: 'Wrong answer', status: 'PENDING', reportedAt: `2026-05-0${id}T10:00:00`, questionText: `Q${qid}`, ...extra });
const rg = M.reportsGroup({ reports: [rep(1, 10), rep(2, 10), rep(3, 11), rep(3, 11), rep(4, 12, { status: 'FIXED' })] });
eq([rg.count, rg.secondary, rg.items.length], [3, '2 questions', 2], 'duplicate report ids collapse; non-PENDING excluded');
eq(rg.items[0], { key: 'q10', title: 'Q10', meta: 'Algebra · Wrong answer', badge: '2 reports', when: rg.items[0].when }, 'most-reported question first, reports grouped per question');
ok(rg.items[0].when instanceof Date); eq(rg.route, '/content/reported-questions');
eq(M.reportsGroup({ reports: [] }).count, 0); eq(M.reportsGroup(null).items, []);
eq(M.reportsGroup({ reports: Array.from({ length: 9 }, (_, i) => rep(i + 1, i + 100)) }).extra, 4, 'overflow beyond 5 items is counted, not rendered');
ok(M.reportsGroup({ reports: [rep(1, 1, { questionText: 'x'.repeat(400) })] }).items[0].title.length <= 110, 'long text truncated');
eq(M.reportsGroup({ reports: [rep(1, 1, { currentQuestionText: 'edited', questionText: 'orig' })] }).items[0].title, 'edited', 'current question text preferred');

// ── Inbox: deletion requests ──────────────────────────────────────────────────────────────────
const del = (id, extra = {}) => ({ id, targetName: `User ${id}`, targetEmail: `u${id}@x.test`, requestedBy: 'boss@x.test', requestedAt: `2026-05-0${id}T10:00:00`, status: 'PENDING', approveCount: 1, requiredCount: 3, callerVoted: false, ...extra });
const dg = M.deletionsGroup({ requests: [del(1), del(2, { callerVoted: true }), del(3, { status: 'REJECTED' })] }, { requests: [del(4, { status: 'APPROVED', approveCount: 3 }), del(1, { status: 'APPROVED' })] });
eq(dg.count, 3, 'REJECTED dropped; id 1 present in both lists is counted once (later APPROVED wins)');
eq(dg.items.map((i) => i.key), ['d4', 'd1', 'd2'], 'approved first (newest first), then pending you already voted on');
eq(new Set(dg.items.map((i) => i.key)).size, dg.items.length, 'no duplicate entries');
eq(dg.secondary, '2 approved, ready to execute', 'awaiting-your-vote only counts PENDING items you have not voted on');
eq(dg.items[0].badge, 'Approved · ready');
const dg2 = M.deletionsGroup({ requests: [del(1), del(2, { callerVoted: true })] }, { requests: [] });
eq([dg2.count, dg2.secondary, dg2.items[0].badge, dg2.items[1].badge], [2, '1 awaiting your vote', 'Needs your vote', 'Pending']);
eq(dg2.items[0].meta, 'u1@x.test · requested by boss@x.test · 1/3 approvals');
eq(M.deletionsGroup(null, { requests: [del(9, { status: 'APPROVED' })] }).notes, ['Pending requests could not be loaded.'], 'a missing half is disclosed, not hidden');
eq(M.deletionsGroup({ requests: [] }, null).notes, ['Approved requests could not be loaded.']);
eq(M.deletionsGroup({ requests: [{ status: 'PENDING' }] }, { requests: [] }).count, 0, 'entries without an id are ignored');

// ── Inbox: flagged accounts ───────────────────────────────────────────────────────────────────
const fg = M.flaggedUsersGroup([{ id: 1, fullName: 'Zed', email: 'z@x.test', flagged: true, flagReason: 'spam' }, { id: 1, fullName: 'Zed', flagged: true }, { id: 2, fullName: 'Amy', flagged: true, archived: true }, { id: 3, fullName: 'Ok' }, { email: 'e@x.test', flagged: true }, { email: 'E@x.test', flagged: true }]);
eq(fg.count, 3, 'duplicate id and duplicate email collapse; unflagged excluded');
eq(fg.items.map((i) => i.title), ['Amy', 'e@x.test', 'Zed'], 'sorted by name');
eq(fg.items.find((i) => i.title === 'Zed').meta, 'z@x.test · Reason: spam');
eq(fg.items.find((i) => i.title === 'Amy').badge, 'Flagged · suspended');
eq(fg.route, '/people/users');
ok(!M.flaggedUsersGroup([{ id: 1, archived: true }]).count, 'suspended alone is not an inbox item');

// ── Inbox: aggregation and states ─────────────────────────────────────────────────────────────
const st = (status, data, error) => ({ status, data, error });
const okAll = { reports: st('ok', { reports: [rep(1, 1)] }), delPending: st('ok', { requests: [del(1)] }), delApproved: st('ok', { requests: [] }), selfDeletions: st('ok', { success: true, requests: [] }), users: st('ok', [{ id: 1, flagged: true }]) };
let a = M.buildAttention(okAll);
eq([a.total, a.complete, a.empty, a.groups.map((g) => g.id)], [3, true, false, ['reports', 'deletions', 'selfDeletions', 'flagged']]);
a = M.buildAttention({ reports: st('ok', { reports: [] }), delPending: st('ok', { requests: [] }), delApproved: st('ok', { requests: [] }), selfDeletions: st('ok', { success: true, requests: [] }), users: st('ok', []) });
eq([a.empty, a.complete, a.total], [true, true, 0], 'empty only when every source loaded and returned nothing');
a = M.buildAttention({ ...okAll, reports: st('error', null, new Error('boom')) });
eq([a.empty, a.complete, a.failures, a.groups[0].state, a.total], [false, false, ['reports'], 'error', 2], 'a failed source is an error, never "0 items", and blocks "all clear"');
a = M.buildAttention({ reports: st('ok', { reports: [] }), delPending: st('error', null, new Error('x')), delApproved: st('error', null, new Error('x')), selfDeletions: st('ok', { requests: [] }), users: st('ok', []) });
eq([a.empty, a.failures], [false, ['deletions']], 'both deletion halves failing is one error group');
a = M.buildAttention({ ...okAll, delApproved: st('error', null, new Error('x')) });
eq([a.groups[1].state, a.groups[1].count, a.groups[1].notes.length, a.complete], ['ok', 1, 1, false], 'one half failing keeps the other half and discloses the gap');
a = M.buildAttention({ ...okAll, users: st('loading') });
eq([a.loading, a.empty, a.complete, a.groups[3].state], [['flagged'], false, false, 'loading']);
eq(M.buildAttention({ reports: st('loading'), delPending: st('loading'), delApproved: st('loading'), selfDeletions: st('loading'), users: st('loading') }).loading.length, 4);
eq(M.buildAttention({ reports: st('ok', { reports: [] }), delPending: st('ok', { requests: [] }), delApproved: st('ok', { requests: [] }), users: st('ok', []) }).empty, false, 'a source that was never supplied is treated as still loading, never as empty');

// ── Inbox: self-requested deletions (user-initiated, GET /api/admin/self-deletion-requests) ───────
const self = (id, extra = {}) => ({ id, fullName: `Student ${id}`, originalEmail: `s${id}@x.test`, requestedAt: `2026-06-0${id}T09:00:00`, ...extra });
const sg = M.selfDeletionsGroup({ success: true, requests: [self(1), self(2), self(2), self(3, { fullName: null }), self(4, { fullName: '', originalEmail: null }), self(5, { requestedAt: null })] });
eq([sg.id, sg.title, sg.route, sg.noun, sg.count, sg.notes], ['selfDeletions', 'Self-requested deletions', '/people/deletion-requests', 'self-requested deletion', 5, []], 'own group, canonical route, duplicate user ids collapse');
eq(sg.items.map((i) => i.key), ['s4', 's3', 's2', 's1', 's5'].slice(0, 5), 'newest request first, undated last');
eq(sg.items.map((i) => i.title), ['Unknown account', 's3@x.test', 'Student 2', 'Student 1', 'Student 5'], 'title falls back to the original email, then to a neutral label; nothing is invented');
eq(sg.items.find((i) => i.key === 's1'), { key: 's1', title: 'Student 1', meta: 's1@x.test · requested by the user', badge: 'Self-requested', tone: 'blue', when: sg.items.find((i) => i.key === 's1').when });
ok(sg.items.find((i) => i.key === 's1').when instanceof Date && sg.items.find((i) => i.key === 's5').when === null, 'requestedAt becomes a Date only when it parses');
eq(sg.secondary, 'requested by the account owner · single admin review', 'states how this differs from dual-control');
eq(M.selfDeletionsGroup({ requests: [] }), { ...M.selfDeletionsGroup({ requests: [] }), count: 0, items: [], secondary: null, extra: 0, notes: [] }, 'a successful empty list is a real zero with no notes');
eq(M.selfDeletionsGroup({ requests: Array.from({ length: 8 }, (_, i) => self(i + 10)) }).extra, 3, 'overflow beyond 5 items is counted, not rendered');
eq(M.selfDeletionsGroup({ requests: [self(1)] }).secondary, 'requested by the account owner · single admin review');
eq(M.selfDeletionsGroup({ requests: [self('7'), self(' 7 ')] }).count, 1, 'string ids are trimmed before de-duplication');

// unreadable entries are never counted and never silently swallowed
const dirty = M.selfDeletionsGroup({ requests: [self(1), null, 'text', 42, {}, { id: null }, { id: '' }, { id: NaN }, { id: {} }, self(2)] });
eq([dirty.count, dirty.items.map((i) => i.key)], [2, ['s2', 's1']], 'only entries with a usable id are counted');
eq(dirty.notes, ['8 self-requested deletion entries could not be read and are not counted.'], 'the discarded entries are disclosed');
eq(M.selfDeletionsGroup({ requests: [self(1), {}] }).notes, ['1 self-requested deletion entry could not be read and is not counted.']);
const bad = M.selfDeletionsGroup({ requests: 'nope' });
eq([bad.count, bad.items, bad.notes.length], [0, [], 1], 'a body without a requests array is a note, never a quiet zero');
for (const input of [null, undefined, 7, 'x', [], {}]) ok(M.selfDeletionsGroup(input).notes.length === 1 && M.selfDeletionsGroup(input).count === 0, `malformed input ${JSON.stringify(input)} is flagged`);
eq(M.buildAttention({ ...okAll, selfDeletions: st('ok', { requests: 'nope' }) }).complete, false, 'a malformed body that reaches the model still blocks "complete" and "all clear"');
eq(M.buildAttention({ reports: st('ok', { reports: [] }), delPending: st('ok', { requests: [] }), delApproved: st('ok', { requests: [] }), selfDeletions: st('ok', { requests: [{}] }), users: st('ok', []) }).empty, false, 'unreadable entries block all clear');

// hostile text passes through untouched (the view renders it with textContent)
eq(M.selfDeletionsGroup({ requests: [self(1, { fullName: '<img src=x onerror=alert(1)>' })] }).items[0].title, '<img src=x onerror=alert(1)>', 'model returns the raw string; escaping is the renderer\'s job');

// never merged with the dual-control group
const dualOnly = M.deletionsGroup({ requests: [del(1), del(2)] }, { requests: [] });
const bothAtOnce = M.buildAttention({ ...okAll, delPending: st('ok', { requests: [del(1), del(2)] }), selfDeletions: st('ok', { requests: [self(1), self(2), self(3)] }) });
const gById = Object.fromEntries(bothAtOnce.groups.map((g) => [g.id, g]));
eq([gById.deletions.count, gById.selfDeletions.count, dualOnly.count], [2, 3, 2], 'dual-control and self-requested counts stay separate; self data never changes the dual-control count');
eq([gById.deletions.items.map((i) => i.key), gById.selfDeletions.items.map((i) => i.key)], [['d2', 'd1'], ['s3', 's2', 's1']], 'item keys cannot collide even when ids match (d1 vs s1)');
eq([gById.deletions.route, gById.selfDeletions.route], ['/people/deletion-requests', '/people/deletion-requests'], 'both point at the one canonical route');
ok(gById.deletions.title !== gById.selfDeletions.title && gById.deletions.noun !== gById.selfDeletions.noun, 'distinct names');
eq(bothAtOnce.total, 1 + 2 + 3 + 1, 'the overall total is a sum of independent groups');
ok(!/awaiting your vote|approved, ready|approvals/.test(JSON.stringify(gById.selfDeletions)), 'no dual-control vocabulary on self-requested items');

// failure vs loading vs empty vs data
const failSelf = M.buildAttention({ ...okAll, selfDeletions: st('error', null, Object.assign(new Error('Simulated server failure'), { status: 500 })) });
const gSelfFail = failSelf.groups.find((g) => g.id === 'selfDeletions');
eq([gSelfFail.state, 'count' in gSelfFail, failSelf.failures, failSelf.empty, failSelf.complete], ['error', false, ['selfDeletions'], false, false], 'endpoint failure: an error group with NO count property, never a zero');
eq(failSelf.total, 3, 'the other three sources are still counted (1 report + 1 deletion + 1 flagged); the failed one contributes nothing, not a zero');
const loadSelf = M.buildAttention({ ...okAll, selfDeletions: st('loading') });
eq([loadSelf.groups.find((g) => g.id === 'selfDeletions').state, loadSelf.loading, loadSelf.empty], ['loading', ['selfDeletions'], false], 'loading: no count either');
const allEmptyBut = (selfState) => M.buildAttention({ reports: st('ok', { reports: [] }), delPending: st('ok', { requests: [] }), delApproved: st('ok', { requests: [] }), selfDeletions: selfState, users: st('ok', []) });
eq(allEmptyBut(st('ok', { requests: [] })).empty, true, 'successful empty result everywhere is the only way to reach all-clear');
eq(allEmptyBut(st('error', null, new Error('x'))).empty, false, 'a failed self-deletion source alone blocks all-clear');
eq(allEmptyBut(st('loading')).empty, false, 'a loading self-deletion source alone blocks all-clear');
eq(allEmptyBut(st('ok', { requests: [self(1)] })).empty, false, 'a self-requested deletion alone ends all-clear');

// ── Endpoints used by the view exist on the backend ───────────────────────────────────────────
const viewSrc = await read('src/main/resources/static/admin/views/overview.js');
const viewCode = stripComments(viewSrc);
const javaDir = 'src/main/java/com/adaptivelearning/adaptivelearningbackend/';
const admin = await read(javaDir + 'AdminController.java');
const reports = await read(javaDir + 'QuestionReportController.java');
const backendGets = new Set([...admin.matchAll(/@GetMapping\("([^"]+)"\)/g)].map((m) => '/api/admin' + m[1]).concat([...reports.matchAll(/@GetMapping\("([^"]+)"\)/g)].map((m) => m[1])));
ok(/@RequestMapping\("\/api\/admin"\)/.test(admin), 'AdminController base path');
const apiPaths = [...viewCode.matchAll(/["`](\/api\/[^"`?$]+)/g)].map((m) => m[1]);
eq(new Set(apiPaths), new Set(['/api/admin/overview', '/api/admin/users', '/api/admin/banned-emails', '/api/admin/activity-log', '/api/admin/reports', '/api/admin/deletion-requests', '/api/admin/self-deletion-requests', '/api/admin/quiz-activity', '/api/admin/topics', '/api/admin/material-categories']), 'the view uses exactly the documented existing endpoints');
for (const p of new Set(apiPaths)) ok(backendGets.has(p), `${p} has a @GetMapping in the backend`);
ok(!/["'`]\/api\/[^"'`]*\/(users|reports|deletion-requests|self-deletion-requests)\/[^"'`]*["'`]/.test(viewCode.replace(/\$\{[^}]*\}/g, '')), 'no per-item or invented sub-endpoints (in particular no execute/restore for self-requested deletions)');
ok(/status=PENDING/.test(viewCode) && /status=APPROVED/.test(viewCode), 'deletion/report filters use the status values the backend supports');
ok(/status\.equalsIgnoreCase\("ALL"\)/.test(reports) && /findByStatusOrderByRequestedAtDesc/.test(admin), 'backend supports the status filters');

// ── View contract and safety ──────────────────────────────────────────────────────────────────
eq([typeof overviewView.mount, typeof overviewView.unmount], ['function', 'function'], 'mount(el, ctx) / unmount() contract');
await assert.rejects(() => overviewView.mount(null, {}), TypeError); n += 1;
overviewView.unmount(); n += 1;                       // safe before/without mount
ok(!/\.innerHTML|outerHTML|insertAdjacentHTML|document\.write|\beval\(|new Function/.test(viewCode), 'no HTML-injection sinks in the view');
ok(!/adminData|renderCurrentTab|switchTab|getElementById\("adminContent"\)/.test(viewCode), 'does not depend on legacy globals or the legacy re-render path');
ok(/controller\.abort\(\)/.test(viewCode) && /removeEventListener\("visibilitychange"/.test(viewCode) && /\.destroy\(\)/.test(viewCode), 'unmount aborts requests, removes listeners and destroys charts');
ok(!/localStorage|sessionStorage|navigator\.sendBeacon|method:\s*["']POST/.test(viewCode), 'Overview is read-only: no writes, no storage');
ok(/\/login\.html/.test(viewCode), '401 handling preserved from the old dashboard');
for (const h of [...viewCode.matchAll(/href:\s*toHref\(([^)]+)\)/g)]) ok(/path|g\.route|a\.path/.test(h[1]), 'links are built from canonical route constants');
ok(!/href:\s*["'`]#?(?!\/)/.test(viewCode.replace(/href:\s*toHref/g, '')), 'no hand-written non-route hrefs');

// ── Self-requested deletion source in the view ────────────────────────────────────────────────
ok(OVERVIEW_SOURCES.selfDeletions && OVERVIEW_SOURCES.selfDeletions.path({}) === '/api/admin/self-deletion-requests', 'view loads the existing self-deletion endpoint');
const selfV = OVERVIEW_SOURCES.selfDeletions.validate;
for (const good of [{ success: true, requests: [] }, { requests: [self(1)] }]) ok(selfV(good), 'valid body accepted: ' + JSON.stringify(good));
for (const badBody of [null, undefined, [], 'x', 5, {}, { success: true }, { requests: null }, { requests: 'x' }, { requests: {} }, { success: false, requests: [] }]) ok(!selfV(badBody), 'malformed body rejected (not treated as empty): ' + JSON.stringify(badBody));
ok(/ATTENTION_SOURCES = \[[^\]]*"selfDeletions"/.test(viewCode), 'self-requested deletions refresh with the rest of the inbox');
ok(/selfDeletions: \["selfDeletions"\]/.test(viewCode) && /selfDeletions: \["attention"\]/.test(viewCode), 'retry reloads only this source; its result repaints only the inbox');
ok(/malformed: true/.test(viewCode) && /Could not read \$\{label\}/.test(viewCode) && /Could not load \$\{label\}/.test(viewCode), 'malformed response and request failure use different messages');
ok(/mine !== src\.token/.test(viewCode) && /src\.controller\.abort\(\)/.test(viewCode), 'stale-response guard and abort apply to every source, including the new one');

// ── Dev mock API answers every Overview source in the shape the view validates ─────────────────
const mockSrc = await read('src/main/resources/static/admin/mock-api.js');
const loadMock = (search = '') => { const win = { location: { origin: 'http://localhost', search } }; vm.runInNewContext(mockSrc, { window: win, URL, URLSearchParams, Promise, JSON, Object, Math, Number, String, Array }); return win; };
const mock = loadMock();
for (const [name, def] of Object.entries(OVERVIEW_SOURCES)) {
  const res = await mock.apiFetch(def.path({ range: 'all' }));
  ok(def.validate(res), `dev mock answers ${name} (${def.path({ range: 'all' })}) with a body the Overview accepts`);
}
const mockSelf = await mock.apiFetch('/api/admin/self-deletion-requests');
ok(mockSelf.requests.length >= 1 && mockSelf.requests.every((r) => r.id != null && r.fullName && r.originalEmail && r.requestedAt), 'mock self-deletion fixture carries the documented fields');
eq((await mock.apiFetch('/api/admin/quiz-activity')).quizHistory, [], 'no quiz attempts are invented');
eq(Object.keys(mock.__adminMockFailures), [], 'nothing fails unless asked to');
// intentional failures stay failures
const failing = loadMock('?mockFail=/api/admin/self-deletion-requests:500&mockFail=/api/admin/quiz-activity:malformed');
await assert.rejects(() => failing.apiFetch('/api/admin/self-deletion-requests'), (e) => e.status === 500); n += 1;
const malformedQuiz = await failing.apiFetch('/api/admin/quiz-activity');
ok(!OVERVIEW_SOURCES.quiz.validate(malformedQuiz), 'a malformed fixture is still rejected by the Overview');
ok(OVERVIEW_SOURCES.reports.validate(await failing.apiFetch('/api/admin/reports?status=PENDING')), 'unrelated sources are unaffected by a configured failure');
mock.__adminMockFailures['/api/admin/self-deletion-requests'] = 503;
await assert.rejects(() => mock.apiFetch('/api/admin/self-deletion-requests'), (e) => e.status === 503); n += 1;

// ── Redirect page ─────────────────────────────────────────────────────────────────────────────
const dash = await read('src/main/resources/static/admindashboard.html');
const dashCode = dash.replace(/<!--[\s\S]*?-->/g, '');
ok(/window\.location\.replace\("admin\.html#\/overview"\)/.test(dashCode), 'redirects with replace() to the canonical Overview hash');
ok(/<noscript><meta http-equiv="refresh" content="0; url=admin\.html#\/overview"/.test(dashCode), 'works without JavaScript');
ok(!/apiFetch|api\.js|Chart|\/api\//.test(dashCode), 'no data loading left on the retired page');
ok(dash.length < 3000, 'page is a small redirect, not a second dashboard');
for (const f of ['admin.html', 'admin.js', 'admin/routes.js', 'admin/router.js', 'admin/views/overview.js', 'login.js']) {
  const code = stripComments(await read('src/main/resources/static/' + f));
  const redirectsToDash = /location(\.href|\.replace|\.assign)?\s*(=|\()\s*["'`]\/?admindashboard/.test(code);
  ok(!redirectsToDash, `${f} never redirects to admindashboard.html (no loop, no extra hop)`);
}
const interceptor = await read(javaDir + 'AuthInterceptor.java');
ok(/ADMIN_ONLY_PAGES[\s\S]{0,200}"\/admin\.html", "\/admindashboard\.html"/.test(interceptor), 'server still gates both pages as admin-only');

// ── Admin landing redirects go straight to the Overview ───────────────────────────────────────
const login = stripComments(await read('src/main/resources/static/login.js'));
ok(/if \(data\.isAdmin\) \{\s*window\.location\.href = "\/admin\.html#\/overview";\s*\} else \{\s*window\.location\.href = data\.redirect \|\| "\/dashboard\.html";/.test(login), 'login.js: admins go to admin.html#/overview, everyone else keeps their redirect');
ok(!/admindashboard/.test(login), 'login.js no longer mentions the legacy page');
const ic = stripComments(interceptor);
ok(/ADMIN_LANDING = "\/admin\.html#\/overview";/.test(ic), 'AuthInterceptor defines the single admin landing target');
eq([...ic.matchAll(/sendRedirect\(([^;]*)\);/g)].map((m) => m[1]), [
  '"/login.html?suspended=1"',
  '!loggedIn ? "/login.html" : (isAdmin ? ADMIN_LANDING : "/dashboard.html")',
  '"/login.html"',
  'isAdmin ? ADMIN_LANDING : "/dashboard.html"',
  'ADMIN_LANDING',
  '"/dashboard.html"'
], 'the complete list of redirects: only the three admin-landing ones changed; logged-out, suspended and non-admin outcomes are as before');
ok(!/sendRedirect\([^;]*admindashboard/.test(ic), 'no server redirect targets the legacy page');
ok(/STUDENT_ONLY_PAGES = Set\.of\(\s*"\/dashboard\.html", "\/quizhub\.html", "\/learninghub\.html",\s*"\/quizfinish\.html", "\/reports\.html", "\/quizpage\.html",\s*"\/profile\.html", "\/sus\.html"\s*\);/.test(ic), 'student-only page list unchanged');
ok(/if \(!isAdmin && ADMIN_ONLY_PAGES\.contains\(path\)\) \{\s*response\.sendRedirect\("\/dashboard\.html"\);/.test(ic), 'non-admins are still turned away from admin-only pages');
ok(/"1"\.equals\(request\.getParameter\("adminPreview"\)\)/.test(ic) && /"\/quizpage\.html"\.equals\(path\)/.test(ic), 'admin quiz-preview exception unchanged and still narrow');
ok(/\.html/.test(dash) && dash.includes('location.replace("admin.html#/overview")'), 'admindashboard.html is kept as a redirect');
const adminHtml = await read('src/main/resources/static/admin.html');
ok(!/admindashboard/.test(adminHtml) && !/Dashboard \(legacy page\)/.test(adminHtml), 'sidebar no longer links to the legacy dashboard');
ok(adminHtml.includes('class="nav-item logout"'), 'logout link kept');
for (const f of ['dashboard.html', 'learninghub.html', 'profile.html', 'quizfinish.html', 'quizhub.html', 'quizpage.html', 'reports.html']) ok((await read('src/main/resources/static/' + f)).includes('/admindashboard.html'), `${f} still links to the retained redirect page (out of scope; the page must stay)`);

console.log(`Admin overview tests passed (${n} checks): model, route targets, backend endpoints, inbox aggregation/dedupe/states, view safety, redirect.`);
