/**
 * Browser smoke test for the admin sidebar shell + hash router (Phase 2).
 *
 * Serves src/main/resources/static over a local HTTP server and drives the REAL admin.html in headless
 * Chromium. Only /api/* and /logout are mocked (Playwright request interception) and the three CDN
 * libraries are stubbed. It does not use the Spring backend, so it is NOT an authenticated end-to-end test:
 * it proves routing, shell behavior and that the legacy renderers still run, not server authorization.
 *
 * Run: node scripts/smoke-admin-shell.mjs
 * Needs the `playwright` package and an installed Chromium (PLAYWRIGHT_BROWSERS_PATH honored).
 * Prints SKIPPED and exits 0 if Playwright is not installed; pass --require-browser to make that fail.
 */
import http from 'node:http';
import path from 'node:path';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const staticDir = fileURLToPath(new URL('../src/main/resources/static/', import.meta.url));

async function loadPlaywright() {
  try { return await import('playwright'); } catch { /* try global install */ }
  try {
    const globalRoot = (await import('node:child_process')).execSync('npm root -g').toString().trim();
    return createRequire(path.join(globalRoot, 'x.js'))('playwright');
  } catch { return null; }
}
const pw = await loadPlaywright();
if (!pw) {
  console.log('SKIPPED: the playwright package is not installed.');
  process.exit(process.argv.includes('--require-browser') ? 1 : 0);
}

const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg' };
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const rel = decodeURIComponent(url.pathname).replace(/^\/+/, '') || 'index.html';
  const file = path.join(staticDir, rel);
  if (!file.startsWith(staticDir)) { res.writeHead(403).end(); return; }
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'content-type': mime[path.extname(file)] || 'application/octet-stream' }).end(body);
  } catch { res.writeHead(404).end('not found'); }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await pw.chromium.launch();
const results = [];
let step = 0;
const check = (name, fn) => async (...a) => { step += 1; try { await fn(...a); results.push([true, name]); } catch (e) { results.push([false, name, e]); } };

// ── mock API ─────────────────────────────────────────────────────────────────────────────────
const users = [
  { id: 1, fullName: 'Ada Admin', email: 'ada@example.test', isAdmin: true, flagged: false, archived: false },
  { id: 2, fullName: 'Sam Student', email: 'sam@example.test', isAdmin: false, flagged: true, flagReason: 'test', archived: false },
  { id: 3, fullName: 'Pat Pending', email: 'pat@example.test', isAdmin: false, flagged: false, archived: false }
];
function mockApi(state) {
  return async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    state.requests.push(`${req.method()} ${p}${url.search}`);
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (p === '/api/me') return state.me ? json(state.me) : json({ message: 'unauthorized' }, 401);
    if (p === '/api/admin/overview') return json({ stats: { totalUsers: 3, totalTopics: 2, totalQuestions: 40, totalAttempts: 9, totalMaterials: 5 } });
    if (p === '/api/admin/users') return json({ success: true, users });
    if (p === '/api/admin/topics') return json([]);
    if (p === '/api/admin/material-categories') return json({ categoryBreakdown: [], subCategoryBreakdown: [] });
    if (p === '/api/admin/banned-emails') return json({ bannedEmails: [] });
    if (p === '/api/admin/activity-log') return json({ success: true, log: [] });
    if (p === '/api/admin/reports/pending-count') return json({ count: 4 });
    if (p === '/api/admin/reports') return json({ reports: [1, 2, 3, 4].map((id) => ({ id, questionId: id, topic: 'T', reason: 'r', status: 'PENDING', reportedAt: '2026-05-01T10:00:00', questionText: 'Q' + id })) }); // consistent with pending-count 4
    if (p === '/api/admin/deletion-requests') return json({ requests: [{ id: 1 }, { id: 2 }] });
    if (p === '/api/admin/self-deletion-requests') return json({ success: true, requests: [] });
    if (p === '/api/admin/assigned-handouts') return json({ handouts: [] });
    if (p === '/api/admin/participants') return json({ participants: [] });
    if (p === '/api/admin/categories') return json([]);
    if (p === '/api/admin/materials') return json([]);
    if (p === '/api/admin/daily-limits') return json({ limits: {} });
    if (p === '/api/admin/configuration') return json([{ id: 1, configKey: 'MAX_UPLOADS_PER_DAY', configValue: '4', dataType: 'INTEGER', description: 'x' }, { id: 2, configKey: 'FEATURE_X', configValue: 'on', dataType: 'STRING', description: 'unknown' }]);
    if (p === '/api/admin/configuration/current/all') return json({ maxUploadsPerDay: 4 });
    if (p === '/api/materials/cancel-upload') return route.fulfill({ status: 204 });
    if (p.startsWith('/api/admin/users/') && /^\/api\/admin\/users\/\d+$/.test(p)) return json({ message: 'not found' }, 404);
    return json({});
  };
}
async function newPage({ me = { name: 'Test Admin', isAdmin: true }, viewport = { width: 1280, height: 800 } } = {}) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const state = { me, requests: [], errors: [] };
  page.on('pageerror', (e) => state.errors.push('pageerror: ' + e.message + ' @ ' + String(e.stack).split('\n').slice(1,3).join(' | ')));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) state.errors.push('console: ' + m.text()); });
  await context.addInitScript(() => {
    window.Chart = class { constructor() {} destroy() {} update() {} };
    window.katex = { render() {} }; window.renderMathInElement = () => {}; window.hljs = { highlightElement() {}, highlightAll() {} };
  });
  await page.route(/^https?:\/\/(cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com)\//, (r) => r.fulfill({ status: 200, contentType: r.request().url().endsWith('.css') ? 'text/css' : 'text/javascript', body: '' }));
  await page.route('**/api/**', mockApi(state));
  await page.route('**/logout', (r) => r.fulfill({ status: 200, body: '' }));
  return { page, context, state };
}
const ready = (page) => page.waitForFunction(() => document.title.endsWith('| Admin Panel | Learning System') && document.getElementById('adminContent').children.length > 0 && !/Checking admin access/.test(document.getElementById('adminContent').innerText), null, { timeout: 8000 });
const hashOf = (page) => page.evaluate(() => location.hash);
const text = (page, sel) => page.locator(sel).first().innerText();

const routesTable = [
  // path, nav label, h1, group
  ['/overview', 'Overview', 'Overview'],
  ['/people/users', 'Users', 'Users'],
  ['/people/deletion-requests', 'Deletion requests', 'Deletion requests'],
  ['/people/banned-emails', 'Banned emails', 'Banned emails'],
  ['/content/materials', 'Materials', 'Materials'],
  ['/content/reported-questions', 'Reported questions', 'Reported questions'],
  ['/content/categories', 'Categories', 'Categories'],
  ['/study/participants', 'Participants', 'Participants'],
  ['/study/assigned-handouts', 'Assigned handouts', 'Assigned handouts'],
  ['/study/data-exports', 'Data exports', 'Data exports'],
  ['/system/usage-limits', 'Usage limits', 'Usage limits'],
  ['/system/configuration', 'Configuration', 'Configuration'],
  ['/system/ai-testing', 'AI testing', 'AI testing'],
  ['/system/reliability', 'Reliability', 'Reliability'],
  ['/audit/activity-log', 'Activity log', 'Activity log'],
  ['/audit/platform-report', 'Platform report', 'Platform report']
];

try {
  // 1. First load, structure, no tab strip ───────────────────────────────────────────────────
  {
    const { page, state, context } = await newPage();
    await page.goto(base + '/admin.html'); await ready(page);
    await check('empty hash is rewritten to #/overview', async () => assert.equal(await hashOf(page), '#/overview'))();
    await check('six sidebar groups with the required headings', async () => {
      const groups = await page.$$eval('#adminNav .shell-nav-group', (g) => g.map((x) => x.dataset.group));
      assert.deepEqual(groups, ['overview', 'people', 'content', 'study', 'system', 'audit']);
      const headings = await page.$$eval('#adminNav .shell-nav-heading', (h) => h.map((x) => x.textContent));
      assert.deepEqual(headings, ['People', 'Content', 'Study', 'System', 'Audit']);
    })();
    await check('no horizontal tab strip anywhere', async () => assert.equal(await page.locator('.admin-tab, .admin-tabs, .admin-tabs-wrap, #adminTabsScroll').count(), 0))();
    await check('overview heading, breadcrumb, aria-current', async () => {
      assert.equal(await text(page, '#adminPageTitle'), 'Overview');
      assert.equal((await text(page, '#adminBreadcrumb')).replace(/\s+/g, ' ').trim(), 'Admin Overview');
      assert.equal(await page.locator('#adminNav a[aria-current="page"]').getAttribute('data-route'), '/overview');
      assert.equal(await page.locator('.admin-tiles').count(), 1);
    })();
    await check('badges are shown by the unchanged legacy refreshers (users 1, reported 4, deletions 2)', async () => {
      await page.waitForFunction(() => document.getElementById('reportedBadge').style.display === 'inline-block' && document.getElementById('deletionBadge').style.display === 'inline-block');
      assert.equal(await text(page, '#flaggedBadge'), '1');
      assert.equal(await text(page, '#reportedBadge'), '4');
      assert.equal(await text(page, '#deletionBadge'), '2');
    })();
    await check('no page errors on first load', async () => assert.deepEqual(state.errors, []))();

    // 2. Click-through of every sidebar entry ────────────────────────────────────────────────
    for (const [route, label, heading] of routesTable) {
      await check(`sidebar → ${route}`, async () => {
        const alreadyThere = await page.evaluate((r) => location.hash === '#' + r, route);   // clicking the current page is a router no-op
        await page.locator(`#adminNav a[data-route="${route}"]`).click();
        await page.waitForFunction((r) => location.hash === '#' + r, route);
        await page.waitForFunction((h) => document.getElementById('adminPageTitle').textContent === h, heading);
        // The title/breadcrumb/nav are applied BEFORE the view loads and mounts, so the title alone does not mean the page
        // is rendered. The router's afterMount step (admin.js finishRoute) moves focus to the route's focus target or to the
        // page heading for every non-modal route, so that is the observable "route finished" signal. Wait for it instead of
        // sleeping (the old fixed 80 ms sleep raced the mount on a loaded machine).
        if (!alreadyThere && route !== '/system/usage-limits') {
          await page.waitForFunction(() => { const a = document.activeElement; return !!a && (a.id === 'adminPageTitle' || a.dataset.routeFocus === 'true'); });
        }
        await page.waitForFunction(() => document.getElementById('adminContent').children.length > 0);
        assert.equal(await page.locator('#adminNav a[aria-current="page"]').getAttribute('data-route'), route);
        assert.equal(await page.locator('#adminNav a[aria-current="page"]').count(), 1);
        assert.ok(await page.evaluate(() => document.getElementById('adminContent').children.length > 0), 'content rendered');
        assert.equal(await page.evaluate(() => document.title), `${heading} | Admin Panel | Learning System`);
        const focusSel = { '/audit/platform-report': '#printTarget' }[route];
        if (focusSel) {
          assert.ok(await page.evaluate((s) => document.activeElement === document.querySelector(s) && document.querySelector(s).dataset.routeFocus === 'true', focusSel), `${focusSel} focused`);
          assert.equal(await page.locator('#printTarget').count(), 1, 'platform report renders the legacy reports screen once');
        }
        if (route === '/study/participants') {
          assert.equal(await page.locator('#enrollEmails').count(), 1, 'Participants has its dedicated enrollment workflow');
          assert.equal(await page.locator('#ahAssignBtn').count(), 0, 'Participants does not render Assigned Handouts');
          assert.equal(await page.locator('a[href="/api/admin/export/attempts.csv"]').count(), 0, 'Participants does not duplicate Data Exports');
        }
        if (route === '/study/assigned-handouts') {
          assert.equal(await page.locator('#ahAssignBtn').count(), 1, 'Assigned Handouts has its dedicated assignment workflow');
          assert.equal(await page.locator('#enrollEmails').count(), 0, 'Assigned Handouts does not render Participants');
        }
        if (route === '/study/data-exports') {
          assert.equal(await page.locator('a[href="/api/admin/export/attempts.csv"]').count(), 1, 'Data Exports has the existing CSV downloads');
          assert.equal(await page.locator('#enrollEmails').count(), 0, 'Data Exports does not render Participants');
          assert.equal(await page.locator('#ahAssignBtn').count(), 0, 'Data Exports does not render Assigned Handouts');
        }
        if (route === '/content/materials') {
          assert.ok(await page.locator('#topicsMaterialsView').isVisible()); assert.ok(!(await page.locator('#topicsCategoriesView').isVisible()));
        }
        if (route === '/content/categories') {
          assert.ok(await page.locator('#topicsCategoriesView').isVisible()); assert.ok(!(await page.locator('#topicsMaterialsView').isVisible()));
        }
        if (route === '/system/reliability') {
          assert.equal(await page.evaluate(() => document.activeElement.getAttribute('href')), '/api/admin/export/reliability.csv');
          assert.match(await text(page, '#adminRouteNotice'), /export-only/);
        } else if (route !== '/people/users/:id') {
          assert.ok(await page.locator('#adminRouteNotice').isHidden(), 'no stray notice');
        }
        if (route === '/system/usage-limits') {
          await page.waitForFunction(() => /Daily Limits/.test(document.getElementById('modal').innerText));
          assert.equal(await page.evaluate(() => getComputedStyle(document.getElementById('modal')).display), 'flex');
          assert.ok(await page.locator('#userSearchInput').count() === 1, 'users screen rendered behind the modal');
          await page.locator('#modal').getByRole('button', { name: 'Close' }).click(); // the legacy modal covers the sidebar until closed
          assert.equal(await page.evaluate(() => getComputedStyle(document.getElementById('modal')).display), 'none');
        } else {
          assert.equal(await page.evaluate(() => getComputedStyle(document.getElementById('modal')).display), 'none', 'modal closed when leaving usage limits');
        }
        if (route === '/system/configuration') {
          const t = await text(page, '#adminContent');
          await page.waitForSelector('[data-view=configuration] [data-key="MAX_UPLOADS_PER_DAY"]');
          assert.ok(!/not yet implemented/i.test(t), 'the placeholder is gone');
          assert.equal(await page.locator('[data-view=configuration] [data-key="FEATURE_X"] button, [data-view=configuration] [data-key="FEATURE_X"] input').count(), 0, 'unknown record is read-only');
          assert.equal(await page.locator('#adminNav a[data-route="/system/configuration"] .nav-tag').count(), 0, 'implemented Configuration has no Not built tag');
        }
        if (route === '/system/ai-testing') assert.ok(await page.locator('.ait-title').count() >= 1);
      })();
    }
    await check('Back while the usage-limits modal is open closes it (route cleanup)', async () => {
      await page.locator('#adminNav a[data-route="/system/usage-limits"]').click();
      await page.waitForFunction(() => /Daily Limits/.test(document.getElementById('modal').innerText));
      await page.goBack();
      await page.waitForFunction(() => getComputedStyle(document.getElementById('modal')).display === 'none');
    })();
    await check('no page errors while visiting all 16 pages', async () => assert.deepEqual(state.errors, []))();
    await check('click-through issued no write requests', async () => assert.deepEqual(state.requests.filter((r) => !r.startsWith('GET ')), []))();
    await context.close();
  }

  // 3. Back / Forward / refresh / quick actions ──────────────────────────────────────────────
  {
    const { page, state, context } = await newPage();
    await page.goto(base + '/admin.html#/people/users'); await ready(page);
    await check('refresh on a hash route stays on it', async () => {
      assert.equal(await hashOf(page), '#/people/users'); assert.equal(await page.locator('#userSearchInput').count(), 1);
      await page.reload(); await ready(page);
      assert.equal(await hashOf(page), '#/people/users'); assert.equal(await text(page, '#adminPageTitle'), 'Users');
    })();
    await check('Back and Forward re-render the right page', async () => {
      await page.locator('#adminNav a[data-route="/audit/activity-log"]').click();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Activity log');
      await page.goBack();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Users');
      assert.equal(await page.locator('#userSearchInput').count(), 1);
      await page.goForward();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Activity log');
      assert.equal(await hashOf(page), '#/audit/activity-log');
    })();
    await check('Overview quick action navigates to Users via a canonical hash link', async () => {
      await page.locator('#adminNav a[data-route="/overview"]').click();
      await page.waitForSelector('.ov-root');
      const manageUsers = page.getByRole('link', { name: /Manage users/i }).first();
      assert.equal(await manageUsers.getAttribute('href'), '#/people/users');
      await manageUsers.click();
      await page.waitForFunction(() => location.hash === '#/people/users');
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Users'); // the title is applied a few ms after the hash; do not read it first
      assert.equal(await text(page, '#adminPageTitle'), 'Users');
    })();
    await check('legacy Materials/Categories buttons keep URL, nav and breadcrumb in step without a new history entry', async () => {
      await page.locator('#adminNav a[data-route="/content/materials"]').click();
      await page.waitForSelector('#topicsSubtabBar');
      const before = await page.evaluate(() => history.length);
      await page.getByRole('button', { name: /Categories/ }).first().click();
      await page.waitForFunction(() => location.hash === '#/content/categories');
      assert.equal(await page.locator('#adminNav a[aria-current="page"]').getAttribute('data-route'), '/content/categories');
      assert.equal(await text(page, '#adminPageTitle'), 'Categories');
      assert.equal(await page.evaluate(() => history.length), before);
      await page.reload(); await ready(page);
      // ready() fires once the legacy Topics screen is written; the route's sub-tab is applied by afterMount a moment later,
      // so wait for the Categories sub-view rather than reading its visibility once. Still fails if it never appears.
      await page.waitForSelector('#topicsCategoriesView', { state: 'visible' });
      assert.ok(await page.locator('#topicsCategoriesView').isVisible(), 'refresh on #/content/categories shows Categories');
      assert.ok(!(await page.locator('#topicsMaterialsView').isVisible()), 'refresh on #/content/categories hides Materials');
    })();
    await check('no page errors', async () => assert.deepEqual(state.errors, []))();
    await context.close();
  }

  // 4. Old hashes ────────────────────────────────────────────────────────────────────────────
  const old = { overview: '/overview', users: '/people/users', topics: '/content/materials', bannedemails: '/people/banned-emails', deletionrequests: '/people/deletion-requests', reports: '/audit/platform-report', activitylog: '/audit/activity-log', aitesting: '/system/ai-testing', reportedquestions: '/content/reported-questions', assignedhandouts: '/study/assigned-handouts' };
  for (const [alias, target] of Object.entries(old)) {
    const { page, state, context } = await newPage();
    await check(`old link admin.html#${alias} → #${target} (no extra history entry)`, async () => {
      await page.goto(`${base}/admin.html#${alias}`); await ready(page);
      assert.equal(await hashOf(page), '#' + target);
      assert.equal(await page.locator('#adminNav a[aria-current="page"]').getAttribute('data-route'), target);
      assert.deepEqual(state.errors, []);
      await page.goBack().catch(() => {});
      assert.ok(!(await page.url()).includes('#users'), 'alias not left in history');
    })();
    await context.close();
  }
  {
    const { page, context } = await newPage();
    await page.goto(base + '/admin.html#/overview'); await ready(page);
    await check('typing an old hash into a running page is normalized', async () => {
      await page.evaluate(() => { location.hash = '#bannedemails'; });
      await page.waitForFunction(() => location.hash === '#/people/banned-emails');
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Banned emails');
    })();
    await context.close();
  }

  // 5. User detail (no backend) and unknown routes ───────────────────────────────────────────
  {
    const { page, state, context } = await newPage();
    await page.goto(base + '/admin.html#/people/users/42'); await ready(page);
    await check('user detail deep link renders the user list with the detail drawer (not-found for an unknown id), no missing endpoint called', async () => {
      await page.waitForSelector('dialog.admin-ui-drawer[open]');
      assert.match(await text(page, 'dialog.admin-ui-drawer[open]'), /User not found/);
      assert.equal(await page.locator('#adminRouteNotice').isHidden(), true);
      assert.equal(await page.locator('#userSearchInput').count(), 1);
      assert.equal(await page.locator('#adminNav a[aria-current="page"]').getAttribute('data-route'), '/people/users');
      assert.equal((await text(page, '#adminBreadcrumb')).replace(/\s+/g, ' ').trim(), 'Admin › People › Users › User detail 42'.replace(/ › /g, ' '));
      assert.equal(state.requests.filter((r) => /\/api\/admin\/users\/42/.test(r)).length, 0, 'the missing endpoint is never called');
    })();
    await page.goto(base + '/admin.html#/nope/at/all'); await ready(page).catch(() => {});
    await page.waitForSelector('text=Page not found');
    await check('unknown route shows Page not found, keeps the URL, no error, and recovers', async () => {
      assert.equal(await hashOf(page), '#/nope/at/all');
      assert.equal(await page.locator('#adminNav a[aria-current="page"]').count(), 0);
      await page.getByRole('link', { name: 'Go to Overview' }).click();
      await page.waitForSelector('.admin-tiles');
      assert.equal(await hashOf(page), '#/overview');
      assert.deepEqual(state.errors, []);
    })();
    await context.close();
  }

  // 6. AI Testing upload cleanup on leaving the route ────────────────────────────────────────
  {
    const { page, state, context } = await newPage();
    await page.goto(base + '/admin.html#/system/ai-testing'); await ready(page);
    await check('leaving AI testing while an upload is in flight sends the cancel beacon; staying does not', async () => {
      await page.evaluate(() => { aitUploadInProgress = true; aitActiveUploadId = 'upload-123'; window.__aitLastTopic = 't'; });
      await page.locator('#adminNav a[data-route="/system/ai-testing"]').click();
      await page.waitForTimeout(150);
      assert.equal(state.requests.filter((r) => r.includes('cancel-upload')).length, 0, 'clicking the current page does not cancel');
      await page.locator('#adminNav a[data-route="/audit/activity-log"]').click();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Activity log');
      await page.waitForFunction(() => aitUploadInProgress === false);
      await page.waitForTimeout(150);
      assert.equal(state.requests.filter((r) => r.includes('cancel-upload')).length, 1, 'exactly one cancel beacon');
    })();
    await check('pagehide cleanup is still registered', async () => {
      await page.evaluate(() => { aitUploadInProgress = true; aitActiveUploadId = 'upload-456'; });
      await page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
      await page.waitForTimeout(150);
      assert.equal(state.requests.filter((r) => r.includes('cancel-upload')).length, 2);
    })();
    await context.close();
  }

  // 7. Narrow screen drawer, print ───────────────────────────────────────────────────────────
  {
    const { page, state, context } = await newPage({ viewport: { width: 390, height: 800 } });
    await page.goto(base + '/admin.html#/people/users'); await ready(page);
    await check('narrow: sidebar is a closed drawer, menu button visible, no horizontal page scroll', async () => {
      assert.ok(await page.locator('#adminNavToggle').isVisible());
      assert.ok(!(await page.locator('#adminSidebar').isVisible()));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    })();
    await check('narrow: menu opens, link navigates and closes it, Escape closes and restores focus', async () => {
      await page.locator('#adminNavToggle').click();
      assert.ok(await page.locator('#adminSidebar').isVisible());
      assert.equal(await page.locator('#adminNavToggle').getAttribute('aria-expanded'), 'true');
      await page.locator('#adminNav a[data-route="/audit/activity-log"]').click();
      await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Activity log');
      await page.waitForFunction(() => getComputedStyle(document.getElementById('adminSidebar')).visibility === 'hidden');
      await page.locator('#adminNavToggle').click();
      await page.keyboard.press('Escape');
      await page.waitForFunction(() => getComputedStyle(document.getElementById('adminSidebar')).visibility === 'hidden');
      assert.equal(await page.evaluate(() => document.activeElement.id), 'adminNavToggle');
    })();
    await check('print media hides sidebar, breadcrumb and notice', async () => {
      await page.emulateMedia({ media: 'print' });
      for (const sel of ['#adminSidebar', '#adminBreadcrumb', '#adminRouteNotice', '#adminNavToggle']) assert.equal(await page.evaluate((s) => getComputedStyle(document.querySelector(s)).display, sel), 'none', sel);
    })();
    await check('narrow: no page errors', async () => assert.deepEqual(state.errors, []))();
    await context.close();
  }

  // 8. Access denied / 401 ───────────────────────────────────────────────────────────────────
  {
    const { page, state, context } = await newPage({ me: { name: 'Student', isAdmin: false } });
    await page.goto(base + '/admin.html#/people/users');
    await page.waitForSelector('.access-denied');
    await check('non-admin: access denied, navigation hidden, no admin data fetched, router never started', async () => {
      assert.equal(await page.evaluate(() => getComputedStyle(document.getElementById('adminNav')).display), 'none');
      assert.equal(state.requests.filter((r) => r.includes('/api/admin/')).length, 0);
      assert.equal(await page.locator('#userSearchInput').count(), 0);
      assert.deepEqual(state.errors, []);
    })();
    await context.close();
  }
  {
    const { page, context } = await newPage({ me: null });
    await page.goto(base + '/admin.html#/overview');
    await check('401 still redirects to the login page', async () => {
      await page.waitForURL(/\/login\.html/, { timeout: 8000 });
    })();
    await context.close();
  }
} finally {
  await browser.close();
  server.close();
}

let failed = 0;
for (const [pass, name, err] of results) {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}`);
  if (!pass) { failed += 1; console.log('      ' + String(err && err.message || err).split('\n').slice(0, 6).join('\n      ')); }
}
console.log(`\n${results.length - failed}/${results.length} browser checks passed (mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
