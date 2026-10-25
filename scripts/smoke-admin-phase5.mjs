/**
 * Phase 5 browser regression checks for the command palette and shell entry points on the REAL admin.html.
 *
 * Serves src/main/resources/static over a local HTTP server and drives the REAL admin.html in headless
 * Chromium. Only /api/* and /logout are mocked (Playwright request interception) and the three CDN
 * libraries are stubbed. It does not use the Spring backend, so it is NOT an authenticated end-to-end test:
 * it proves routing, shell behavior and that the legacy renderers still run, not server authorization.
 *
 * Run: node scripts/smoke-admin-phase5.mjs
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
const paletteVisible = (page) => page.locator('[data-cmdk-root]').isVisible();
const waitPalette = (page, open) => page.waitForFunction((o) => { const r = document.querySelector('[data-cmdk-root]'); return !!r && (!r.hidden === o); }, open);
const pressCtrlK = (page) => page.keyboard.press('Control+k');
const settledOn = (page, title) => page.waitForFunction((t) => document.getElementById('adminPageTitle').textContent === t && document.getElementById('adminContent').children.length > 0, title);

try {
  // ── 1. Shortcut is not taken from page controls or other dialogs ────────────────────────────
  {
    const { page, state, context } = await newPage();
    await page.goto(base + '/admin.html#/people/users'); await ready(page);
    await page.waitForSelector('[data-cmdk-root]', { state: 'attached' });
    await page.waitForSelector('#userSearchInput');
    await check('Ctrl+K inside a real page filter box is left to the box (palette stays closed, focus and text kept)', async () => {
      await page.locator('#userSearchInput').fill('sam'); await page.locator('#userSearchInput').focus();
      await pressCtrlK(page);
      await page.waitForTimeout(50);   // negative check: nothing is expected to happen
      assert.equal(await paletteVisible(page), false);
      assert.equal(await page.evaluate(() => document.activeElement.id), 'userSearchInput');
      assert.equal(await page.locator('#userSearchInput').inputValue(), 'sam');
    })();
    await check('IME composition: Ctrl+K while composing does not open it', async () => {
      await page.evaluate(() => { document.body.focus(); document.activeElement.blur(); document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', code: 'KeyK', ctrlKey: true, isComposing: true, bubbles: true, cancelable: true })); });
      await page.waitForTimeout(50);
      assert.equal(await paletteVisible(page), false);
    })();
    await check('with the Usage limits modal open, Ctrl+K does not open the palette', async () => {
      await page.locator('#adminNav a[data-route="/system/usage-limits"]').click();
      await page.waitForFunction(() => /Daily Limits/.test(document.getElementById('modal').innerText));
      await pressCtrlK(page);
      await page.waitForTimeout(50);
      assert.equal(await paletteVisible(page), false);
      await page.locator('#modal').getByRole('button', { name: 'Close' }).click();
    })();
    await check('with the user-detail drawer (native dialog) open, Ctrl+K does not open the palette', async () => {
      await page.goto(base + '/admin.html#/people/users/2'); await ready(page);
      await page.waitForSelector('dialog.admin-ui-drawer[open]');
      await pressCtrlK(page);
      await page.waitForTimeout(50);
      assert.equal(await paletteVisible(page), false);
      assert.equal(await page.locator('dialog.admin-ui-drawer[open]').count(), 1, 'drawer untouched');
    })();
    await check('no page errors', async () => assert.deepEqual(state.errors, []))();
    await context.close();
  }

  // ── 2. Open/close robustness, router navigation, Back/Forward, no results ─────────────────────
  {
    const { page, state, context } = await newPage();
    await page.goto(base + '/admin.html#/overview'); await ready(page);
    await page.waitForSelector('[data-cmdk-root]', { state: 'attached' });
    await check('40 rapid open/close cycles (Escape and Ctrl+K toggle): focus returns to the opener every time, one palette root, still opens afterwards', async () => {
      const link = page.locator('#adminNav a[data-route="/people/users"]');
      for (let i = 0; i < 40; i++) {
        await link.focus();
        await pressCtrlK(page); await waitPalette(page, true);
        assert.equal(await page.evaluate(() => document.activeElement.getAttribute('role')), 'combobox', 'input focused on open ' + i);
        if (i % 2) await page.keyboard.press('Escape'); else await pressCtrlK(page);
        await waitPalette(page, false);
        assert.equal(await page.evaluate(() => document.activeElement.getAttribute('data-route')), '/people/users', 'focus restored on cycle ' + i);
      }
      assert.equal(await page.locator('[data-cmdk-root]').count(), 1);
      await pressCtrlK(page); await waitPalette(page, true); await page.keyboard.press('Escape'); await waitPalette(page, false);
    })();
    await check('destinations equal the sidebar routes (registry-derived, no stale entries); every one is a real router target', async () => {
      await pressCtrlK(page); await waitPalette(page, true);
      const paths = await page.locator('[data-cmdk-root] [role=option][data-kind]:not([data-kind=action])').evaluateAll((o) => o.map((n) => n.getAttribute('data-path')));
      const nav = await page.locator('#adminNav a[data-route]').evaluateAll((a) => a.map((n) => n.getAttribute('data-route')));
      assert.deepEqual(paths.slice().sort(), nav.slice().sort());
      const bad = await page.evaluate((ps) => ps.filter((p) => adminRouter.resolve(p).kind !== 'ok'), paths);
      assert.deepEqual(bad, []);
      await page.keyboard.press('Escape'); await waitPalette(page, false);
    })();
    await check('search + Enter navigates through the router; Back and Forward work and leave the palette closed and reusable', async () => {
      await pressCtrlK(page); await waitPalette(page, true);
      await page.keyboard.type('banned'); await page.keyboard.press('Enter');
      await page.waitForFunction(() => location.hash === '#/people/banned-emails');
      await settledOn(page, 'Banned emails');
      assert.equal(await page.evaluate(() => adminRouter.current().path), '/people/banned-emails', 'router state, not just the hash');
      await page.goBack(); await page.waitForFunction(() => location.hash === '#/overview'); await settledOn(page, 'Overview');
      await page.goForward(); await page.waitForFunction(() => location.hash === '#/people/banned-emails'); await settledOn(page, 'Banned emails');
      assert.equal(await paletteVisible(page), false);
      await pressCtrlK(page); await waitPalette(page, true);
      assert.equal(await page.locator('[data-cmdk-root] input').inputValue(), '', 'query cleared on reopen');
      await page.keyboard.press('Escape'); await waitPalette(page, false);
    })();
    await check('Back while open closes it; no results: message, Enter does nothing, clearing restores the list', async () => {
      await pressCtrlK(page); await waitPalette(page, true);
      await page.goBack(); await page.waitForFunction(() => location.hash === '#/overview'); await waitPalette(page, false);
      await page.goForward(); await page.waitForFunction(() => location.hash === '#/people/banned-emails');
      await pressCtrlK(page); await waitPalette(page, true);
      await page.keyboard.type('zzqqxx'); await page.keyboard.press('Enter');
      await page.waitForTimeout(50);
      assert.equal(await page.evaluate(() => location.hash), '#/people/banned-emails');
      assert.equal(await paletteVisible(page), true);
      assert.match(await page.locator('.cmdk-empty').innerText(), /No pages or actions match/);
      assert.equal(await page.locator('[data-cmdk-root] [role=option]').count(), 0);
      await page.locator('[data-cmdk-root] input').fill('');
      assert.ok((await page.locator('[data-cmdk-root] [role=option]').count()) >= 16);
      await page.keyboard.press('Escape'); await waitPalette(page, false);
    })();
    await check('hostile route text typed as a query is shown as text, never parsed', async () => {
      await pressCtrlK(page); await waitPalette(page, true);
      await page.keyboard.type('<img src=x onerror="window.__p5=1">');
      assert.equal(await page.evaluate(() => window.__p5), undefined);
      assert.equal(await page.locator('[data-cmdk-root] img').count(), 0);
      await page.keyboard.press('Escape'); await waitPalette(page, false);
    })();
    await check('repeated initialization on the live page leaves one root and one working listener', async () => {
      await page.evaluate(async () => {
        const [pal, rt] = await Promise.all([import('/admin/search/command-palette.js'), import('/admin/routes.js')]);
        for (let i = 0; i < 3; i++) pal.initCommandPalette({ routes: rt.routes, sections: rt.sections, aliases: rt.aliases, navigate: (t) => adminRouter.navigate(t), resolve: (t) => adminRouter.resolve(t), getCurrentPath: () => adminRouter.current()?.path ?? null, trigger: document.getElementById('adminSearchTrigger') });
      });
      assert.equal(await page.locator('[data-cmdk-root]').count(), 1);
      await pressCtrlK(page); await waitPalette(page, true);
      await pressCtrlK(page); await waitPalette(page, false);   // one toggle = one listener (two would reopen)
      await page.waitForTimeout(50);
      assert.equal(await paletteVisible(page), false);
    })();
    await check('no page errors', async () => assert.deepEqual(state.errors, []))();
    await context.close();
  }

  // ── 3. Entry points: landing, access denied, 401 ──────────────────────────────────────────────
  {
    const { page, state, context } = await newPage({ me: { name: 'Student', isAdmin: false } });
    await page.goto(base + '/admin.html#/people/users');
    await page.waitForSelector('.access-denied');
    await check('non-admin: access-denied page, dashboard link, palette never starts, Ctrl+K inert, no admin data requested', async () => {
      assert.equal(await page.locator('.access-denied a[href="dashboard.html"]').count(), 1);
      await pressCtrlK(page); await page.waitForTimeout(50);
      assert.equal(await page.locator('[data-cmdk-root]').count(), 0);
      assert.deepEqual(state.requests.filter((r) => /\/api\/admin\//.test(r)), []);
    })();
    await context.close();
  }
  {
    const { page, context } = await newPage({ me: null });
    await check('401 on /api/me sends the browser to /login.html', async () => {
      await page.route('**/login.html', (r) => r.fulfill({ status: 200, contentType: 'text/html', body: '<title>login</title>' }));
      await page.goto(base + '/admin.html#/overview');
      await page.waitForURL(/\/login\.html$/);
    })();
    await context.close();
  }
  {
    const { page, context } = await newPage();
    await check('legacy landing page /admindashboard.html still redirects to the Overview, with no link back to it from admin.html', async () => {
      await page.goto(base + '/admindashboard.html');
      await page.waitForURL(/\/admin\.html#\/overview$/); await ready(page);
      assert.equal(await page.locator('a[href*="admindashboard"]').count(), 0);
      assert.equal(await page.evaluate(() => /admindashboard/.test(document.getElementById('adminNav').innerHTML)), false);
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
console.log(`\n${results.length - failed}/${results.length} browser checks passed (Chromium only; mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
