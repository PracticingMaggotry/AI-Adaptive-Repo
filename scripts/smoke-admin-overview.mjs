/**
 * Browser smoke test for the Overview + Needs-attention inbox + retired dashboard redirect.
 *
 * Serves src/main/resources/static and drives the REAL admin.html / admindashboard.html in headless Chromium.
 * ONLY /api/* and /logout are mocked (Playwright interception) and Chart.js is replaced by a counting stub, so this
 * proves client behavior and routing, NOT server authorization or an authenticated production session.
 *
 * Run: node scripts/smoke-admin-overview.mjs   (needs the playwright package + Chromium; prints SKIPPED if absent)
 */
import http from 'node:http';
import path from 'node:path';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';

const staticDir = fileURLToPath(new URL('../src/main/resources/static/', import.meta.url));
async function loadPlaywright() {
  try { return await import('playwright'); } catch { /* try global */ }
  try { return createRequire(path.join(execSync('npm root -g').toString().trim(), 'x.js'))('playwright'); } catch { return null; }
}
const pw = await loadPlaywright();
if (!pw) { console.log('SKIPPED: the playwright package is not installed.'); process.exit(process.argv.includes('--require-browser') ? 1 : 0); }

const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json' };
const server = http.createServer(async (req, res) => {
  const rel = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '') || 'index.html';
  const file = path.join(staticDir, rel);
  if (!file.startsWith(staticDir)) return res.writeHead(403).end();
  let body; try { body = await readFile(file); } catch { return res.writeHead(404).end('nf'); }
  res.writeHead(200, { 'content-type': mime[path.extname(file)] || 'application/octet-stream' }).end(body);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await pw.chromium.launch();
const results = [];
const check = (name, fn) => async (...a) => { try { await fn(...a); results.push([true, name]); } catch (e) { results.push([false, name, e]); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── mock backend ──────────────────────────────────────────────────────────────────────────────
const rep = (id, qid, extra = {}) => ({ id, questionId: qid, topic: 'Algebra', reason: 'Wrong answer', status: 'PENDING', reportedAt: `2026-05-0${id}T10:00:00`, questionText: `Question ${qid}`, ...extra });
const del = (id, extra = {}) => ({ id, targetName: `Target ${id}`, targetEmail: `t${id}@x.test`, requestedBy: 'boss@x.test', requestedAt: `2026-05-0${id}T10:00:00`, status: 'PENDING', approveCount: 1, requiredCount: 3, callerVoted: false, ...extra });
function dataset() {
  return {
    me: { name: 'Dana Admin', isAdmin: true },
    stats: { totalUsers: 6, totalTopics: 10, totalQuestions: 95, totalAttempts: 12, totalMaterials: 7 },
    users: [
      { id: 1, fullName: 'Dana Admin', email: 'dana@x.test', isAdmin: true },
      { id: 2, fullName: '<img src=x onerror="window.__xss=1">', email: 'bad@x.test', flagged: true, flagReason: '<b>spam</b>' },
      { id: 3, fullName: 'Sam', email: 'sam@x.test', archived: true },
      { id: 4, fullName: 'Pat', email: 'pat@x.test', flagged: true },
      { id: 5, fullName: 'Lee', email: 'lee@x.test' },
      { id: 6, fullName: 'Kim', email: 'KIM@x.test' }
    ],
    banned: [{ id: 1, email: 'kim@x.test', bannedAt: '2026-04-02T09:00:00' }],
    log: [1, 2, 3, 4, 5, 6].map((i) => ({ type: i === 1 ? 'delete' : 'ban-email', message: `Action ${i}`, detail: `detail ${i}`, time: `2026-05-0${i}T08:00:00` })),
    reports: [rep(1, 10), rep(2, 10), rep(3, 11), rep(3, 11), rep(4, 12, { status: 'FIXED' })],
    deletions: [del(1), del(2, { callerVoted: true }), del(3, { status: 'APPROVED', approveCount: 3 })],
    selfDeletions: [selfReq(1), selfReq(2, { fullName: '<img src=x onerror="window.__xss2=1">' }), selfReq(2)],
    selfBody: null,
    quiz: [{ topic: 'Algebra', difficulty: 'Easy', score: 90, date: '2026-05-03' }, { topic: 'algebra', difficulty: 'Hard', score: 40, date: '2026-05-02' }, { topic: 'Geometry', difficulty: 'Medium', score: 72, date: '2026-05-01' }],
    topics: ['Algebra', 'Geometry', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8', 'T9', 'T10'],
    categories: (range) => ({ topCategory: `Top-${range}`, topSubCategory: `Sub-${range}`, categoryBreakdown: [{ category: 'Math', count: 5 }, { category: 'Empty', count: 0 }], subCategoryBreakdown: [{ subCategory: 'Algebra', count: 3 }] }),
    fail: {}, delay: {}, requests: [], writes: []
  };
}
const selfReq = (id, extra = {}) => ({ id, fullName: `Student ${id}`, originalEmail: `s${id}@x.test`, requestedAt: `2026-06-0${id}T09:00:00`, ...extra });
const empty = () => ({ ...dataset(), selfDeletions: [], stats: { totalUsers: 0, totalTopics: 0, totalQuestions: 0, totalAttempts: 0, totalMaterials: 0 }, users: [], banned: [], log: [], reports: [], deletions: [], quiz: [], topics: [], categories: () => ({ categoryBreakdown: [], subCategoryBreakdown: [] }) });

function mockApi(st) {
  return async (route) => {
    const req = route.request(); const url = new URL(req.url()); const p = url.pathname; const q = url.searchParams;
    st.requests.push(`${req.method()} ${p}${url.search}`);
    if (req.method() !== 'GET') st.writes.push(`${req.method()} ${p}`);
    const key = p === '/api/admin/deletion-requests' ? 'del' + q.get('status') : p === '/api/admin/reports' ? 'reports' : p.replace('/api/admin/', '').replace('/api/', '');
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    const wait = st.delay[key] || st.delay[p] || 0; if (wait) await sleep(wait);
    const f = st.fail[key] || st.fail[p]; if (f) return json({ message: f === 401 ? 'unauthorized' : 'Simulated server failure' }, f);
    switch (p) {
      case '/api/me': return st.me ? json(st.me) : json({ message: 'no' }, 401);
      case '/api/admin/overview': return json({ stats: st.stats });
      case '/api/admin/users': return json({ users: st.users });
      case '/api/admin/banned-emails': return json({ bannedEmails: st.banned });
      case '/api/admin/activity-log': return json({ log: st.log });
      case '/api/admin/reports': { const s = q.get('status'); return json({ reports: st.reports.filter((r) => !s || s === 'ALL' || r.status === s) }); }
      case '/api/admin/reports/pending-count': return json({ count: new Set(st.reports.filter((r) => r.status === 'PENDING').map((r) => r.id)).size }); // the real backend never repeats a report id
      case '/api/admin/deletion-requests': { const s = q.get('status'); return json({ requests: st.deletions.filter((r) => !s || s === 'ALL' || r.status === s) }); }
      case '/api/admin/self-deletion-requests': return json(st.selfBody || { success: true, requests: st.selfDeletions });
      case '/api/admin/quiz-activity': return json({ quizHistory: st.quiz });
      case '/api/admin/topics': return json(st.topics);
      case '/api/admin/material-categories': return json(st.categories(q.get('range') || 'all'));
      case '/api/admin/daily-limits': return json({ limits: {} });
      default: return json({});
    }
  };
}
async function newPage(st, { viewport = { width: 1280, height: 900 }, javaScriptEnabled = true } = {}) {
  const context = await browser.newContext({ viewport, javaScriptEnabled });
  const page = await context.newPage();
  st.errors = [];
  page.on('pageerror', (e) => st.errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) st.errors.push('console: ' + m.text()); });
  await context.addInitScript(() => {
    window.__charts = { created: 0, destroyed: 0 };
    window.Chart = class { constructor() { window.__charts.created += 1; } destroy() { window.__charts.destroyed += 1; } update() {} };
    window.katex = { render() {} }; window.renderMathInElement = () => {}; window.hljs = { highlightElement() {}, highlightAll() {} };
  });
  await page.route(/^https?:\/\/(cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com)\//, (r) => r.fulfill({ status: 200, contentType: 'text/javascript', body: '' }));
  await page.route('**/api/**', mockApi(st));
  await page.route('**/logout', (r) => r.fulfill({ status: 200, body: '' }));
  return { page, context };
}
const openOverview = async (page, hash = '#/overview') => { await page.goto(`${base}/admin.html${hash}`); await page.waitForSelector('.ov-root', { timeout: 8000 }); };
const settled = (page) => page.waitForFunction(() => !document.querySelector('.ov-state:not(.ov-state--error)[role="status"]') && !/Refreshing/.test(document.querySelector('.ov-refresh')?.textContent || ''), null, { timeout: 8000 });
const attn = (page) => page.locator('.ov-attention');
const groupBy = (page, title) => page.locator('.ov-group', { has: page.locator('.ov-group-title', { hasText: title }) });

try {
  // ── 1. Populated ─────────────────────────────────────────────────────────────────────────────
  {
    const st = dataset(); const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('greets the signed-in admin and renders inside the shell (no duplicate Overview implementations)', async () => {
      assert.match(await page.locator('.ov-greeting').innerText(), /^Good (morning|afternoon|evening), Dana$/);
      assert.equal(await page.locator('.ov-root').count(), 1);
      assert.equal(await page.locator('#adminPageTitle').innerText(), 'Overview');
      assert.equal(await page.locator('text=Security Summary').count(), 0, 'legacy overview markup is not rendered');
    })();
    await check('Needs attention: reports are de-duplicated and grouped per question', async () => {
      const g = groupBy(page, 'Reported questions');
      assert.equal((await g.locator('.ov-count').innerText()).trim(), '3');        // 3 distinct pending reports (id 3 returned twice, FIXED excluded)
      assert.match(await g.locator('.ov-secondary').innerText(), /2 questions/);
      assert.equal(await g.locator('.ov-item').count(), 2);
      assert.match(await g.locator('.ov-item').first().innerText(), /Question 10[\s\S]*2 reports/);
    })();
    await check('Needs attention: deletion requests merge pending + approved without double counting', async () => {
      const g = groupBy(page, 'Deletion requests');
      assert.equal((await g.locator('.ov-count').innerText()).trim(), '3');
      const text = await g.innerText();
      assert.match(text, /1 awaiting your vote/); assert.match(text, /1 approved, ready to execute/);
      assert.match(await g.locator('.ov-item').first().innerText(), /Needs your vote/, 'what you can act on by voting comes first');
      assert.match(await g.locator('.ov-item').nth(1).innerText(), /Approved · ready/);
      assert.equal(await g.locator('.ov-item').count(), 3);
    })();
    await check('Needs attention: self-requested deletions are their own group, separate from dual-control', async () => {
      const g = groupBy(page, 'Self-requested deletions');
      assert.equal(await g.count(), 1);
      assert.equal((await g.locator('.ov-count').innerText()).trim(), '2', 'duplicate user id 2 counted once');
      assert.equal((await groupBy(page, 'Deletion requests').locator('.ov-count').innerText()).trim(), '3', 'dual-control count unaffected');
      const text = await g.innerText();
      assert.match(text, /Student 1[\s\S]*s1@x\.test[\s\S]*Self-requested/);
      assert.match(text, /single admin review/);
      assert.ok(!/awaiting your vote|approvals|Needs your vote/.test(text), 'no voting vocabulary');
      assert.equal(await g.locator('.ov-item').count(), 2);
      assert.equal(await g.locator('a.ov-open').getAttribute('href'), '#/people/deletion-requests');
      assert.ok((await g.locator('.ov-item').evaluateAll((as) => as.every((a) => a.getAttribute('href') === '#/people/deletion-requests'))));
      assert.match(await page.locator('.ov-summary').innerText(), /^10 open items across 4 areas$/);
    })();
    await check('Needs attention: flagged accounts listed; suspended-only accounts are not inbox items', async () => {
      const g = groupBy(page, 'Flagged accounts');
      assert.equal((await g.locator('.ov-count').innerText()).trim(), '2');
      assert.ok(!(await g.innerText()).includes('Sam'));
    })();
    await check('every inbox link targets an existing canonical route', async () => {
      const hrefs = await attn(page).locator('a').evaluateAll((as) => [...new Set(as.map((a) => a.getAttribute('href')))]);
      assert.deepEqual(hrefs.sort(), ['#/content/reported-questions', '#/people/deletion-requests', '#/people/users']); // self-requested items share the canonical deletion route
    })();
    await check('KPI tiles, ratios and account breakdown come from the returned data', async () => {
      assert.deepEqual(await page.locator('.ov-kpis .admin-tile-value').allInnerTexts(), ['6', '10', '95', '12', '7']);
      assert.match(await page.locator('.ov-kpis').innerText(), /5 students · 1 admins/);
      assert.match(await page.locator('.ov-ratios').innerText(), /10[\s\S]*2[\s\S]*1\.2/);
      const stats = await page.locator('.ov-stat-value').allInnerTexts();
      assert.deepEqual(stats, ['4', '1', '1', '2'], 'active, suspended, banned (kim matched case-insensitively), flagged');
      assert.match(await page.locator('.ov-facts').innerText(), /Banned emails\s*1[\s\S]*Deleted \(in recent log\)\s*1/);
    })();
    await check('quick actions are links to canonical hash routes and cover the old dashboard destinations', async () => {
      const hrefs = await page.locator('.ov-quick a').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
      for (const must of ['#/content/reported-questions', '#/people/users', '#/people/banned-emails', '#/people/deletion-requests', '#/content/materials', '#/audit/platform-report']) assert.ok(hrefs.includes(must), must);
      assert.ok(hrefs.every((h) => h.startsWith('#/')));
    })();
    await check('recent actions (newest 5), activity link and topics table with performance', async () => {
      const acts = await page.locator('.ov-act-title').allInnerTexts();
      assert.deepEqual(acts, ['Action 6', 'Action 5', 'Action 4', 'Action 3', 'Action 2']);
      assert.equal(await page.locator('.ov-activity').locator('xpath=ancestor::section').locator('a.ov-open').getAttribute('href'), '#/audit/activity-log');
      assert.equal(await page.locator('.ov-table tbody tr').count(), 8);
      assert.match(await page.locator('.ov-table').innerText(), /Algebra\s*2\s*OK · 65%/);
      assert.match(await page.locator('#ov-topics-title').locator('xpath=ancestor::section').innerText(), /…and 2 more/);
    })();
    await check('charts: category + quiz charts created through Chart.js', async () => {
      await page.waitForFunction(() => window.__charts.created === 4);
    })();
    await check('hostile account text is shown as text, never executed or parsed as HTML', async () => {
      assert.ok((await groupBy(page, 'Flagged accounts').innerText()).includes('<img src=x onerror="window.__xss=1">'));
      assert.equal(await page.evaluate(() => window.__xss), undefined);
      assert.ok((await groupBy(page, 'Self-requested deletions').innerText()).includes('<img src=x onerror="window.__xss2=1">'));
      assert.equal(await page.evaluate(() => window.__xss2), undefined);
      assert.equal(await page.locator('.ov-root img, .ov-root b').count(), 0);
    })();
    await check('sidebar badges are refreshed from the same data (reports 3, flagged 2, deletion requests 5 = 2 pending + 1 approved dual-control + 2 self-requested; ids 1 and 2 exist in both id spaces and are not merged)', async () => {
      await page.waitForFunction(() => document.getElementById('reportedBadge').textContent === '3' && document.getElementById('deletionBadge').textContent === '5' && document.getElementById('flaggedBadge').textContent === '2');
      assert.equal(await page.locator('#deletionBadge').getAttribute('data-badge-state'), 'known');
    })();
    await check('inbox item navigates through the router', async () => {
      await groupBy(page, 'Reported questions').locator('.ov-item').first().click();
      // location.hash changes synchronously with the click, but the router renders on the (asynchronous) hashchange
      // event: unmount, then title/breadcrumb/nav, then mount. Measured in Chromium, the title lags the hash by roughly
      // 6-14 ms, so asserting the title right after the hash was a race. Wait for the router to finish rendering the
      // target route (observable state, not a time), then assert exactly what was asserted before.
      await page.waitForFunction(() => location.hash === '#/content/reported-questions');
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Reported questions' && !document.querySelector('.ov-root'));
      assert.equal(await page.locator('#adminPageTitle').innerText(), 'Reported questions');
      assert.equal(await page.locator('.ov-root').count(), 0, 'overview unmounted');
      assert.equal(await page.locator('#adminNav a.active[data-route="/content/reported-questions"]').count(), 1, 'sidebar follows the route');
      // The link created a real history entry: Back returns to a freshly mounted Overview.
      await page.goBack();
      await page.waitForSelector('.ov-root');
      assert.equal(new URL(page.url()).hash, '#/overview');
      assert.equal(await page.locator('#adminPageTitle').innerText(), 'Overview');
    })();
    await check('Overview is read-only: every request was a GET, nothing unexpected called', async () => {
      assert.deepEqual(st.writes, []);
      assert.ok(st.requests.includes('GET /api/admin/self-deletion-requests'));
      assert.ok(!st.requests.some((r) => /self-deletion-requests\/|\/api\/admin\/users\/\d/.test(r)), 'no execute/restore/per-user calls');
    })();
    await check('no page errors', async () => assert.deepEqual(st.errors, []))();
    await context.close();
  }

  // ── 2. Empty ─────────────────────────────────────────────────────────────────────────────────
  {
    const st = empty(); const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('empty: explicit All clear, zeros from real data, no fabricated items', async () => {
      assert.match(await attn(page).innerText(), /All clear/); assert.match(await page.locator('.ov-summary').innerText(), /Nothing needs attention/);
      assert.match(await attn(page).innerText(), /self-requested deletions/);
      assert.equal(await page.locator('.ov-group').count(), 0);
      assert.deepEqual(await page.locator('.ov-kpis .admin-tile-value').allInnerTexts(), ['0', '0', '0', '0', '0']);
      assert.match(await page.locator('main, #adminContent').first().innerText(), /No quiz attempts recorded yet/);
      assert.match(await page.locator('#adminContent').innerText(), /No topics uploaded yet/);
      assert.match(await page.locator('#adminContent').innerText(), /No admin actions recorded yet/);
      assert.match(await page.locator('#adminContent').innerText(), /No categorized materials/);
      assert.equal(await page.evaluate(() => window.__charts.created), 0);
      assert.deepEqual(st.errors, []);
    })();
    await context.close();
  }

  // ── 3. Error, retry, partial failure ─────────────────────────────────────────────────────────
  {
    const st = dataset(); st.fail.reports = 500; st.fail['quiz-activity'] = 500; const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('a failing source shows an error with Retry, siblings still render, never "All clear" or a 0 count', async () => {
      const g = groupBy(page, 'Reported questions');
      assert.equal(await g.locator('[role="alert"]').count(), 1);
      assert.match(await g.innerText(), /Could not load reported questions\./);
      assert.equal(await g.locator('.ov-count').count(), 0);
      assert.equal((await groupBy(page, 'Deletion requests').locator('.ov-count').innerText()).trim(), '3');
      assert.match(await page.locator('.ov-summary').innerText(), /some sources could not be checked/);
      assert.equal(await attn(page).locator('.ov-allclear').count(), 0);
      assert.match(await page.locator('#ov-quiz-title').locator('xpath=ancestor::section').innerText(), /Could not load quiz analytics/);
      assert.match(await page.locator('#ov-topics-title').locator('xpath=ancestor::section').innerText(), /Quiz data could not be loaded, so attempts and performance are hidden/);
    })();
    await check('Try again re-fetches only that source and the group appears once the server recovers', async () => {
      delete st.fail.reports; const before = st.requests.filter((r) => r.includes('/api/admin/reports')).length;
      await groupBy(page, 'Reported questions').getByRole('button', { name: 'Try again' }).click();
      await page.waitForFunction(() => document.querySelector('.ov-group .ov-count') && /Reported questions/.test(document.querySelector('.ov-group-title')?.textContent || ''));
      assert.equal(st.requests.filter((r) => r.includes('/api/admin/reports')).length, before + 1);
      assert.equal((await groupBy(page, 'Reported questions').locator('.ov-count').innerText()).trim(), '3');
      assert.equal(await page.locator('[role="alert"]').count(), 1, 'only quiz remains failed');
    })();
    await check('half of the deletion data failing is disclosed, not hidden', async () => {
      st.fail.delAPPROVED = 500; await page.getByRole('button', { name: /Refresh/ }).click(); await settled(page);
      const g = groupBy(page, 'Deletion requests');
      assert.match(await g.innerText(), /Approved requests could not be loaded/);
      assert.equal((await g.locator('.ov-count').innerText()).trim(), '2');
      assert.equal(await attn(page).locator('.ov-allclear').count(), 0);
    })();
    await check('no page errors during failures', async () => assert.deepEqual(st.errors, []))();
    await context.close();
  }

  // ── 3b. Self-requested deletions: failure, malformed, empty, loading, retry, refresh ─────────
  {
    const st = empty(); st.fail['self-deletion-requests'] = 500; const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('self-deletion endpoint failure: error + Retry, no count, no All clear even though everything else is empty', async () => {
      const g = groupBy(page, 'Self-requested deletions');
      assert.equal(await g.locator('[data-error-kind="request"]').count(), 1);
      assert.match(await g.innerText(), /Could not load self-requested deletions\./);
      assert.equal(await g.locator('.ov-count').count(), 0);
      assert.equal(await attn(page).locator('.ov-allclear').count(), 0);
      assert.match(await page.locator('.ov-summary').innerText(), /some sources could not be checked/);
      assert.equal(await page.locator('.ov-group').count(), 1, 'the successfully empty groups stay hidden; only the failed one is shown');
    })();
    await check('Try again reloads only the self-deletion source; recovery with an empty list reaches All clear', async () => {
      delete st.fail['self-deletion-requests']; const before = st.requests.length;
      await groupBy(page, 'Self-requested deletions').getByRole('button', { name: 'Try again' }).click();
      await page.waitForFunction(() => /All clear/.test(document.querySelector('.ov-attention').innerText));
      const after = st.requests.slice(before);
      assert.deepEqual(after, ['GET /api/admin/self-deletion-requests']);
    })();
    await check('self-deletion recovery with data shows the real count', async () => {
      st.selfDeletions = [selfReq(1), selfReq(3)]; await page.getByRole('button', { name: /Refresh/ }).click(); await settled(page);
      assert.equal((await groupBy(page, 'Self-requested deletions').locator('.ov-count').innerText()).trim(), '2');
      assert.equal(await attn(page).locator('.ov-allclear').count(), 0);
    })();
    await check('no page errors', async () => assert.deepEqual(st.errors, []))();
    await context.close();
  }
  for (const [label, body] of [['no requests array', { success: true }], ['requests is not a list', { success: true, requests: 'none' }], ['success:false', { success: false, requests: [] }], ['empty object', {}]]) {
    const st = empty(); st.selfBody = body; const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check(`self-deletion malformed response (${label}): reported as unreadable, not as zero or All clear`, async () => {
      const g = groupBy(page, 'Self-requested deletions');
      assert.equal(await g.locator('[data-error-kind="malformed"]').count(), 1);
      assert.match(await g.innerText(), /Could not read self-requested deletions\./);
      assert.equal(await g.locator('.ov-count').count(), 0);
      assert.equal(await attn(page).locator('.ov-allclear').count(), 0);
      assert.deepEqual(st.errors, []);
    })();
    await context.close();
  }
  {
    const st = dataset(); st.selfBody = { success: true, requests: [selfReq(1), { fullName: 'No id' }, null] }; const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('self-deletion entries that cannot be read are not counted and the gap is disclosed with a retry', async () => {
      const g = groupBy(page, 'Self-requested deletions');
      assert.equal((await g.locator('.ov-count').innerText()).trim(), '1');
      assert.match(await g.innerText(), /2 self-requested deletion entries could not be read and are not counted/);
      assert.equal(await g.getByRole('button', { name: 'Retry' }).count(), 1);
      assert.match(await page.locator('.ov-summary').innerText(), /some sources could not be checked|still checking|open item/);
      assert.equal(await attn(page).locator('.ov-allclear').count(), 0);
    })();
    await context.close();
  }
  {
    const st = dataset(); st.delay['self-deletion-requests'] = 900; const { page, context } = await newPage(st);
    await page.goto(`${base}/admin.html#/overview`); await page.waitForSelector('.ov-root');
    await check('self-deletion loading: a loading line and no count while the request is in flight', async () => {
      await page.waitForSelector('text=Loading self-requested deletions…');
      const g = groupBy(page, 'Self-requested deletions');
      assert.equal(await g.locator('.ov-count').count(), 0);
      assert.match(await page.locator('.ov-summary').innerText(), /so far|still checking/);
      await page.waitForSelector('[data-group="selfDeletions"] .ov-count'); await settled(page);
      assert.equal(await page.locator('text=Loading self-requested deletions…').count(), 0);
    })();
    await context.close();
  }
  {
    const st = dataset(); st.delay['self-deletion-requests'] = 700; const { page, context } = await newPage(st);
    await page.goto(`${base}/admin.html#/overview`); await page.waitForSelector('.ov-root');
    await check('leaving mid-load: the self-deletion response is ignored and nothing is painted into the next page', async () => {
      await page.locator('#adminNav a[data-route="/people/banned-emails"]').click();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Banned emails');
      await sleep(1100);
      assert.equal(await page.locator('.ov-root, .ov-group').count(), 0);
      assert.deepEqual(st.errors, []);
    })();
    await context.close();
  }

  // ── 4. Loading state ─────────────────────────────────────────────────────────────────────────
  {
    const st = dataset(); st.delay.reports = 900; st.delay.topics = 900; const { page, context } = await newPage(st);
    await page.goto(`${base}/admin.html#/overview`); await page.waitForSelector('.ov-root');
    await check('loading state is shown per source while data is in flight, then replaced', async () => {
      await page.waitForSelector('text=Loading reported questions…');
      assert.equal(await groupBy(page, 'Reported questions').locator('.ov-count').count(), 0, 'no count before data arrives');
      assert.match(await page.locator('#adminContent').innerText(), /Loading topics…/);
      await page.waitForSelector('.ov-group .ov-count');
      await settled(page);
      assert.equal(await page.locator('text=Loading reported questions…').count(), 0);
    })();
    await context.close();
  }

  // ── 5. Refresh removes stale entries ─────────────────────────────────────────────────────────
  {
    const st = dataset(); const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('Refresh re-fetches the inbox and resolved items disappear (no stale entries)', async () => {
      assert.equal(await groupBy(page, 'Reported questions').count(), 1);
      st.reports = []; st.deletions = []; st.selfDeletions = []; st.users = st.users.map((u) => ({ ...u, flagged: false }));
      await page.getByRole('button', { name: /Refresh/ }).click(); await settled(page);
      assert.equal(await page.locator('.ov-group').count(), 0);
      assert.match(await attn(page).innerText(), /All clear/);
      assert.match(await page.locator('.ov-updated').innerText(), /^Updated /);
      await page.waitForFunction(() => ['reportedBadge', 'deletionBadge', 'flaggedBadge'].every((id) => document.getElementById(id).style.display === 'none'));
    })();
    await check('legacy renderCurrentTab() cannot overwrite the Overview while it is mounted', async () => {
      const before = st.requests.length;
      await page.evaluate(() => renderCurrentTab());
      await sleep(300);
      assert.equal(await page.locator('.ov-root').count(), 1);
      assert.equal(await page.locator('#adminContent >> text=Security Summary').count(), 0);
      assert.ok(st.requests.length > before, 'it refreshed instead');
    })();
    await context.close();
  }

  // ── 6. Range switch race ─────────────────────────────────────────────────────────────────────
  {
    const st = dataset(); const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('category range: a slow earlier response never overwrites a newer selection', async () => {
      st.delay['/api/admin/material-categories'] = 700;
      await page.getByRole('button', { name: 'This Week' }).click();
      st.delay['/api/admin/material-categories'] = 40;
      await page.getByRole('button', { name: 'This Month' }).click();
      await page.waitForFunction(() => /Top-month/.test(document.querySelector('#ov-cat-title').closest('section').innerText));
      await sleep(900);
      assert.match(await page.locator('#ov-cat-title').locator('xpath=ancestor::section').innerText(), /Top-month/);
      assert.equal(await page.getByRole('button', { name: 'This Month' }).getAttribute('aria-pressed'), 'true');
      assert.equal(await page.getByRole('button', { name: 'This Week' }).getAttribute('aria-pressed'), 'false');
      assert.ok(st.requests.some((r) => r.includes('range=week')) && st.requests.some((r) => r.includes('range=month')));
      assert.deepEqual(st.errors, []);
    })();
    await context.close();
  }

  // ── 7. Unmount cleanup ───────────────────────────────────────────────────────────────────────
  {
    const st = dataset(); const { page, context } = await newPage(st);
    await openOverview(page); await settled(page);
    await check('charts are destroyed and the legacy renderer is restored when leaving the Overview', async () => {
      await page.waitForFunction(() => window.__charts.created === 4);
      await page.locator('#adminNav a[data-route="/audit/activity-log"]').click();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Activity log');
      assert.deepEqual(await page.evaluate(() => window.__charts), { created: 4, destroyed: 4 });
      assert.equal(await page.evaluate(() => /adminData/.test(String(window.renderOverview))), true, 'original legacy renderOverview restored');
      assert.equal(await page.locator('#adminNav a[data-route="/overview"]').click().then(() => true), true);
      await page.waitForSelector('.ov-root'); await settled(page);
      assert.equal(await page.locator('.ov-root').count(), 1, 're-entering mounts a single fresh instance');
      assert.deepEqual(st.errors, []);
    })();
    await context.close();
  }
  {
    const st = dataset(); for (const k of ['reports', 'delPENDING', 'delAPPROVED', 'quiz-activity', 'topics', 'users', 'banned-emails', 'material-categories', 'activity-log', 'overview']) st.delay[k] = 700; const { page, context } = await newPage(st);
    await page.goto(`${base}/admin.html#/overview`); await page.waitForSelector('.ov-root');
    await check('leaving mid-load: in-flight responses are ignored, no errors, nothing painted into the next page', async () => {
      await page.locator('#adminNav a[data-route="/people/banned-emails"]').click();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Banned emails');
      await sleep(1200);
      assert.equal(await page.locator('.ov-root, .ov-group').count(), 0);
      assert.equal(await page.evaluate(() => window.__charts.created), 0);
      assert.deepEqual(st.errors, []);
    })();
    await context.close();
  }

  // ── 8. 401, non-admin ────────────────────────────────────────────────────────────────────────
  {
    const st = dataset(); st.fail.reports = 401; const { page, context } = await newPage(st);
    await page.goto(`${base}/admin.html#/overview`);
    await check('an expired session (401) sends the user to the login page, as the old dashboard did', async () => { await page.waitForURL(/\/login\.html/, { timeout: 8000 }); })();
    await context.close();
  }
  {
    const st = dataset(); st.me = { name: 'Student', isAdmin: false }; const { page, context } = await newPage(st);
    await page.goto(`${base}/admin.html#/overview`); await page.waitForSelector('.access-denied');
    await check('non-admin: Overview never mounts and no admin inbox endpoint is called', async () => {
      assert.equal(await page.locator('.ov-root').count(), 0);
      assert.equal(st.requests.filter((r) => r.includes('/api/admin/')).length, 0);
    })();
    await context.close();
  }

  // ── 9. Retired dashboard entry point ─────────────────────────────────────────────────────────
  {
    const st = dataset(); const { page, context } = await newPage(st);
    await page.goto(`${base}/admindashboard.html`);
    await check('admindashboard.html redirects to admin.html#/overview and the Overview renders', async () => {
      await page.waitForURL(/\/admin\.html#\/overview$/, { timeout: 8000 });
      await page.waitForSelector('.ov-root');
      assert.equal(await page.locator('#adminPageTitle').innerText(), 'Overview');
    })();
    await check('redirect is not a loop: it is replaced in history, so Back does not return to it', async () => {
      assert.equal(new URL(page.url()).pathname, '/admin.html');
      await page.goBack().catch(() => {});
      assert.ok(!page.url().includes('admindashboard'), page.url());
    })();
    await check('the redirect page itself makes no API calls', async () => {
      const first = st.requests.findIndex((r) => r.includes('/api/me'));
      assert.ok(first >= 0);
      const st2 = dataset(); const p2 = await newPage(st2);
      await p2.page.route('**/admin.html*', (r) => r.fulfill({ status: 200, contentType: 'text/html', body: '<title>stub</title>' }));
      await p2.page.goto(`${base}/admindashboard.html`); await p2.page.waitForURL(/admin\.html#\/overview$/);
      assert.deepEqual(st2.requests, []);
      await p2.context.close();
    })();
    await context.close();
  }
  {
    const st = dataset(); const { page, context } = await newPage(st, { javaScriptEnabled: false });
    await check('without JavaScript the meta refresh still points at the Overview', async () => {
      await page.goto(`${base}/admindashboard.html`); await page.waitForURL(/\/admin\.html#\/overview$/, { timeout: 8000 });
    })();
    await context.close();
  }

  // ── 9b. Login sends an admin straight to the Overview (no stop at the retired page) ─────────
  {
    const st = dataset(); const { page, context } = await newPage(st);
    const visited = []; page.on('framenavigated', (f) => { if (f === page.mainFrame()) visited.push(new URL(f.url()).pathname + new URL(f.url()).hash); });
    await page.route('**/login', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, isAdmin: true }) }));
    await page.goto(`${base}/login.html`);
    await check('login.js: an admin lands on admin.html#/overview directly, never visiting admindashboard.html', async () => {
      await page.locator('form').evaluate((f) => { f.querySelector('#email').value = 'admin@x.test'; f.querySelector('#pw').value = 'pw'; });
      await page.locator('form button[type=submit]').click();
      await page.waitForURL(/\/admin\.html#\/overview$/, { timeout: 8000 });
      await page.waitForSelector('.ov-root');
      assert.ok(!visited.some((v) => v.includes('admindashboard')), visited.join(' > '));
    })();
    await context.close();
  }
  {
    const st = dataset(); const { page, context } = await newPage(st);
    await page.route('**/login', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, isAdmin: false, redirect: '/dashboard.html' }) }));
    await page.route('**/dashboard.html', (r) => r.fulfill({ status: 200, contentType: 'text/html', body: '<title>student</title>' }));
    await page.goto(`${base}/login.html`);
    await check('login.js: a non-admin still follows the server-provided redirect', async () => {
      await page.locator('form').evaluate((f) => { f.querySelector('#email').value = 'admin@x.test'; f.querySelector('#pw').value = 'pw'; });
      await page.locator('form button[type=submit]').click();
      await page.waitForURL(/\/dashboard\.html$/, { timeout: 8000 });
    })();
    await context.close();
  }
  {
    const st = dataset(); const { page, context } = await newPage(st);
    await page.goto(`${base}/admin.html#/overview`); await page.waitForSelector('.ov-root');
    await check('the sidebar has no legacy dashboard link and still has Logout', async () => {
      assert.equal(await page.locator('#adminSidebar a[href*="admindashboard"]').count(), 0);
      assert.equal(await page.locator('#adminSidebar a.logout').count(), 1);
    })();
    await context.close();
  }

  // ── 10. Narrow screen and structure ──────────────────────────────────────────────────────────
  {
    const st = dataset(); const { page, context } = await newPage(st, { viewport: { width: 390, height: 800 } });
    await openOverview(page); await settled(page);
    await check('narrow: no horizontal page scroll and the inbox stays readable', async () => {
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
      assert.ok(await groupBy(page, 'Reported questions').isVisible());
    })();
    await check('structure: unique ids, named links/buttons, labelled sections, polite live summary', async () => {
      const dup = await page.evaluate(() => { const ids = [...document.querySelectorAll('[id]')].map((e) => e.id); return ids.filter((x, i) => ids.indexOf(x) !== i); });
      assert.deepEqual(dup, []);
      const unnamed = await page.locator('.ov-root a, .ov-root button').evaluateAll((els) => els.filter((e) => !e.textContent.trim() && !e.getAttribute('aria-label')).length);
      assert.equal(unnamed, 0);
      assert.equal(await page.locator('.ov-root section[aria-labelledby]').count() >= 8, true);
      assert.equal(await page.locator('.ov-summary').getAttribute('aria-live'), 'polite');
    })();
    await context.close();
  }
} finally {
  await browser.close(); server.close();
}

let failed = 0;
for (const [pass, name, err] of results) {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}`);
  if (!pass) { failed += 1; console.log('      ' + String((err && err.message) || err).split('\n').slice(0, 8).join('\n      ')); }
}
console.log(`\n${results.length - failed}/${results.length} browser checks passed (mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
