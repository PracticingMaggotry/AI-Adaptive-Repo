// Real-browser smoke test for the People > Users detail drawer, against the production admin.html with a mocked API.
// Run: node --experimental-default-type=module scripts/smoke-people-users.mjs [--require-browser]
// Needs the `playwright` package and a Chromium it can launch. Without it: SKIPPED (exit 0), or exit 1 with --require-browser.
// There is no Spring backend and no authenticated session here: this checks front-end behaviour only.
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
if (!pw) { console.log('SKIPPED: the playwright package is not installed.'); process.exit(process.argv.includes('--require-browser') ? 1 : 0); }

const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg' };
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const file = path.join(staticDir, decodeURIComponent(url.pathname).replace(/^\/+/, '') || 'index.html');
  if (!file.startsWith(staticDir)) { res.writeHead(403).end(); return; }
  // /api/* is answered by Playwright's page.route. A request that is still in flight when its context is closed (an
  // app refresh fired by the last action) loses that route and lands here: answer it, never throw. Read the file BEFORE
  // writing the status line: writeHead(200) followed by a failed read used to call writeHead(404) a second time, which
  // throws ERR_HTTP_HEADERS_SENT inside this async handler and kills the whole test process (the intermittent failure).
  if (url.pathname.startsWith('/api/')) { res.writeHead(503, { 'content-type': 'application/json' }).end('{"success":false,"message":"mock API route is no longer active"}'); return; }
  let body;
  try { body = await readFile(file); } catch { res.writeHead(404).end('not found'); return; }
  res.writeHead(200, { 'content-type': mime[path.extname(file)] || 'application/octet-stream' }).end(body);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await pw.chromium.launch();
const results = [];
const check = (name, fn) => async (...a) => { try { await fn(...a); results.push([true, name]); } catch (e) { results.push([false, name, e]); } };

const freshUsers = () => [
  { id: 1, fullName: 'Ada Admin', email: 'ada@example.test', isAdmin: true, flagged: false, archived: false },
  { id: 2, fullName: 'Sam Student', email: 'sam@example.test', isAdmin: false, flagged: true, flagReason: 'Suspicious upload', archived: false },
  { id: 3, fullName: 'Pat Pending', email: 'pat@example.test', isAdmin: false, flagged: false, archived: false },
  { id: 4, fullName: 'Bo Banned', email: 'bo@example.test', isAdmin: false, flagged: false, archived: false },
  { id: 5, fullName: 'Del Self', email: 'deleted+5+1@deleted.invalid', isAdmin: false, flagged: false, archived: true, archiveReason: 'Account deletion requested by the user' }
];
function mockApi(state) {
  return async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    state.requests.push(`${req.method()} ${p}`);
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (p === '/api/me') return json({ name: 'Test Admin', isAdmin: true });
    if (p === '/api/admin/overview') return json({ stats: {} });
    if (p === '/api/admin/users') return state.failUsers ? json({ message: 'Server exploded' }, 500) : json({ success: true, users: state.users });
    if (p === '/api/admin/banned-emails') return json({ bannedEmails: [{ id: 1, email: 'BO@example.test', reason: 'Abuse', bannedBy: 'ada@example.test', bannedAt: '2026-10-01T10:00:00' }] });
    if (p === '/api/admin/self-deletion-requests') return json({ requests: [{ id: 5, fullName: 'Del Self', originalEmail: 'del@example.test', requestedAt: '2026-10-15T11:00:00' }] });
    if (p === '/api/admin/deletion-requests') return json({ requests: [{ id: 3, targetEmail: 'pat@example.test', status: 'PENDING', reason: 'Abuse', requestedBy: 'ada@example.test', approveCount: 0, requiredCount: 2 }] });
    if (/^\/api\/admin\/users\/\d+\/daily-usage$/.test(p)) return json({ success: true, usage: [{ actionType: 'quiz-submit', globalLimit: 10, personalLimit: null, limit: 10, used: 4 }] });
    if (p === '/api/admin/archive-user' && req.method() === 'POST') {
      const body = JSON.parse(req.postData() || '{}');
      const u = state.users.find(x => x.email === body.email); u.archived = true; u.archiveReason = body.reason;
      return json({ success: true });
    }
    if (p === '/api/admin/topics' || p === '/api/admin/materials' || p === '/api/admin/categories') return json([]);
    if (p === '/api/admin/material-categories') return json({ categoryBreakdown: [], subCategoryBreakdown: [] });
    if (p === '/api/admin/activity-log') return json({ success: true, log: [] });
    if (p === '/api/admin/reports/pending-count') return json({ count: 0 });
    if (p === '/api/materials/cancel-upload') return route.fulfill({ status: 204 });
    return json({});
  };
}
async function newPage({ viewport = { width: 1280, height: 800 }, failUsers = false } = {}) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const state = { requests: [], errors: [], users: freshUsers(), failUsers };
  page.on('pageerror', e => state.errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) state.errors.push('console: ' + m.text()); });
  await context.addInitScript(() => {
    window.Chart = class { constructor() {} destroy() {} update() {} };
    window.katex = { render() {} }; window.renderMathInElement = () => {}; window.hljs = { highlightElement() {}, highlightAll() {} };
  });
  await page.route(/^https?:\/\/(cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com)\//, r => r.fulfill({ status: 200, contentType: r.request().url().endsWith('.css') ? 'text/css' : 'text/javascript', body: '' }));
  await page.route('**/api/**', mockApi(state));
  return { page, context, state };
}
const drawer = page => page.locator('dialog.admin-ui-drawer[open]');
const hashOf = page => page.evaluate(() => location.hash);
const ready = page => page.waitForFunction(() => document.getElementById('adminContent').children.length > 0 && !/Checking admin access/.test(document.getElementById('adminContent').textContent));

// 1. deep link, content, no stale notice, existing endpoints only
{
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/users/2'); await ready(page);
  await check('deep link opens the drawer over the user list', async () => {
    await drawer(page).waitFor();
    assert.equal(await drawer(page).locator('.admin-ui-drawer__title').innerText(), 'Sam Student');
    assert.equal(await page.locator('#userSearchInput').count(), 1, 'list is rendered underneath');
    await drawer(page).getByText('Suspicious upload').waitFor();
    await drawer(page).locator('.account-status--flagged').waitFor();
    await drawer(page).getByText('Quiz Submissions').waitFor();
  })();
  await check('the "User detail is not available" notice is gone and no missing endpoint is requested', async () => {
    assert.equal(await page.locator('#adminRouteNotice').isHidden(), true);
    assert.deepEqual(state.requests.filter(r => /\/api\/admin\/users\/\d+$/.test(r)), []);
  })();
  await check('list shows one normalized badge per row', async () => {
    await page.waitForFunction(() => document.querySelectorAll('#adminContent .admin-table .account-status').length === 5);
    const labels = await page.$$eval('#adminContent .admin-table tbody tr', rows => rows.map(r => r.querySelector('.account-status')?.textContent));
    assert.deepEqual(labels, ['ACTIVE', 'FLAGGED', 'DELETION PENDING', 'BANNED', 'DELETION PENDING']);
  })();
  await check('no page errors', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

// 2. list -> detail -> close, Back/Forward, search context
{
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/users'); await ready(page);
  await check('opening a user pushes the canonical hash; Close returns to the list with the search kept and focus on the row', async () => {
    await page.fill('#userSearchInput', 'pat');
    await page.locator('#adminContent a.user-link', { hasText: 'Pat Pending' }).click();
    await drawer(page).waitFor();
    assert.equal(await hashOf(page), '#/people/users/3');
    await drawer(page).getByText('1 of 1').or(drawer(page).getByText('0 of 2 approvals')).first().waitFor();
    await drawer(page).getByRole('button', { name: 'Close' }).click();
    await page.waitForFunction(() => location.hash === '#/people/users' && !document.querySelector('dialog[open]'));
    assert.equal(await page.inputValue('#userSearchInput'), 'pat');
    assert.equal(await page.locator('#adminContent tbody tr').count(), 1);
    assert.equal(await page.evaluate(() => document.activeElement?.textContent), 'Pat Pending');
  })();
  await check('browser Back and Forward close and reopen the drawer', async () => {
    await page.locator('#adminContent a.user-link', { hasText: 'Pat Pending' }).click();
    await drawer(page).waitFor();
    await page.goBack();
    await page.waitForFunction(() => location.hash === '#/people/users' && !document.querySelector('dialog[open]'));
    await page.goForward();
    await drawer(page).waitFor();
    assert.equal(await hashOf(page), '#/people/users/3');
  })();
  await check('Escape closes the drawer', async () => {
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => location.hash === '#/people/users' && !document.querySelector('dialog[open]'));
  })();
  await check('refresh on the detail hash reopens the same user', async () => {
    await page.locator('#adminContent a.user-link', { hasText: 'Pat Pending' }).click();
    await drawer(page).waitFor();
    await page.reload(); await ready(page);
    await drawer(page).waitFor();
    assert.equal(await drawer(page).locator('.admin-ui-drawer__title').innerText(), 'Pat Pending');
  })();
  await check('no page errors', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

// 3. administrative actions through the legacy modal (the drawer must not sit on top of it)
{
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/users/2'); await ready(page);
  await check('Suspend: drawer steps aside, the legacy modal is usable, and the drawer reopens showing SUSPENDED', async () => {
    await drawer(page).getByText('Quiz Submissions').waitFor();
    await drawer(page).getByRole('button', { name: 'Suspend' }).click();
    await page.waitForFunction(() => !document.querySelector('dialog[open]'));
    await page.fill('#archiveReason', 'Smoke test');             // fails by timeout if the modal is covered/inert
    await page.click('#confirmArchiveBtn');
    await drawer(page).locator('.account-status--suspended').waitFor();
    await drawer(page).getByText('Smoke test').waitFor();
    assert.equal(await hashOf(page), '#/people/users/2');
    assert.ok(state.requests.includes('POST /api/admin/archive-user'));
  })();
  await check('Cancel in the legacy modal brings the drawer back', async () => {
    await drawer(page).getByRole('button', { name: 'Restore' }).click();
    await page.waitForFunction(() => !document.querySelector('dialog[open]'));
    await page.getByRole('button', { name: 'Cancel' }).click();
    await drawer(page).waitFor();
  })();
  await check('self-deletion account: status-changing actions are withheld', async () => {
    await page.evaluate(() => { location.hash = '#/people/users/5'; });
    await page.waitForFunction(() => document.querySelector('dialog[open] .admin-ui-drawer__title')?.textContent === 'Del Self');
    await drawer(page).getByText('del@example.test').waitFor();
    assert.equal(await drawer(page).getByRole('button', { name: /Suspend|Restore|Ban email|Unflag|Flag/ }).count(), 0);
    assert.equal(await drawer(page).getByRole('button', { name: 'Open deletion requests' }).count(), 1);
  })();
  await check('no page errors', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

// 4. not found, API error, mobile width
{
  let { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/users/42'); await ready(page);
  await check('unknown id shows User not found over the list, with a way back', async () => {
    await drawer(page).getByText('No user with ID 42').waitFor();
    assert.equal(await page.locator('#adminRouteNotice').isHidden(), true);
    await drawer(page).getByRole('button', { name: 'Back to users' }).click();
    await page.waitForFunction(() => location.hash === '#/people/users' && !document.querySelector('dialog[open]'));
  })();
  await context.close();

  ({ page, state, context } = await newPage({ viewport: { width: 390, height: 780 } }));
  await page.goto(base + '/admin.html#/people/users/4'); await ready(page);
  await check('mobile width: drawer fits the viewport and shows BANNED', async () => {
    await drawer(page).locator('.account-status--banned').waitFor();
    const box = await drawer(page).locator('.admin-ui-drawer__panel').boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 391, `panel overflows: ${JSON.stringify(box)}`);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no horizontal page scroll');
  })();
  await context.close();
}

await browser.close();
server.close();
let failed = 0;
for (const [pass, name, err] of results) { console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}`); if (!pass) { failed += 1; console.log('      ' + String(err.message).split('\n').slice(0, 6).join('\n      ')); } }
console.log(`${results.length - failed}/${results.length} browser checks passed (mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
