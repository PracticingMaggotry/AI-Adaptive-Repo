// Real-browser smoke test for People > Deletion requests (admin-initiated dual control + self-requested queue),
// against the production admin.html with a mocked API.
// Run: node --experimental-default-type=module scripts/smoke-self-deletion-queue.mjs [--require-browser]
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

const COLLISION = "Can't restore \u2014 taylor@example.test is already used by a newer account. The user likely already re-registered; this old account can still be permanently deleted instead.";
const fresh = () => ({
  self: [
    { id: 104, fullName: 'Taylor Cruz', originalEmail: 'taylor@example.test', requestedAt: '2026-10-15T11:00:00' },
    { id: 110, fullName: 'Riley Older', originalEmail: 'riley@example.test', requestedAt: '2026-10-10T09:00:00' }
  ],
  dual: [{ id: 104, status: 'PENDING', targetName: 'Lee Pending', targetEmail: 'lee@example.test', reason: 'Repeated abuse', requestedBy: 'pat@example.test', requestedAt: '2026-10-12T10:00:00', approveCount: 1, requiredCount: 3, callerVoted: false, votes: [] }],
  collide: [104], requests: [], errors: []
});
function mockApi(state) {
  return async (route) => {
    const req = route.request(); const p = new URL(req.url()).pathname; const method = req.method();
    state.requests.push(`${method} ${p}`);
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (p === '/api/me') return json({ name: 'Test Admin', isAdmin: true });
    if (p === '/api/admin/self-deletion-requests' && method === 'GET') return state.failSelf ? json({ success: false, message: 'Database down' }, 500) : json({ success: true, requests: state.self });
    let m = p.match(/^\/api\/admin\/self-deletion-requests\/(\d+)\/(execute|restore)$/);
    if (m && method === 'POST') {
      const id = Number(m[1]);
      if (m[2] === 'restore' && state.collide.includes(id)) return json({ success: false, message: COLLISION }, 400);
      state.self = state.self.filter(x => x.id !== id);
      return json({ success: true, message: m[2] === 'execute' ? 'Account permanently deleted.' : 'Account restored.' });
    }
    if (p === '/api/admin/deletion-requests' && method === 'GET') return json({ success: true, requests: state.dual });
    if (p === '/api/admin/users') return json({ success: true, users: [] });
    if (p === '/api/admin/overview') return json({ stats: {} });
    if (p === '/api/admin/activity-log') return json({ success: true, log: [] });
    if (p === '/api/admin/reports/pending-count') return json({ count: 0 });
    if (p === '/api/admin/topics' || p === '/api/admin/materials' || p === '/api/admin/categories') return json([]);
    if (p === '/api/admin/material-categories') return json({ categoryBreakdown: [], subCategoryBreakdown: [] });
    return json({});
  };
}
async function newPage({ viewport = { width: 1280, height: 900 }, failSelf = false } = {}) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const state = fresh(); state.failSelf = failSelf;
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
const ready = page => page.waitForFunction(() => document.getElementById('adminContent').children.length > 0 && !/Checking admin access/.test(document.getElementById('adminContent').textContent));
const section = page => page.locator('#selfDeletionSection');
const posts = state => state.requests.filter(r => r.startsWith('POST'));
const shots = process.env.SHOTS_DIR;

{
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/deletion-requests'); await ready(page);
  await section(page).locator('li[data-user-id="104"]').waitFor();
  await check('both queues are on the page and visibly different workflows', async () => {
    await page.locator('#dualControlSection').waitFor();
    assert.match(await page.locator('#dualControlHeading').innerText(), /Admin-initiated deletion requests/);
    assert.match(await page.locator('#selfDeletionHeading').innerText(), /Self-requested deletions \(2\)/);
    assert.equal(await page.locator('.sdq-guide__card').count(), 2);
    await page.locator('#deletionList').getByText('Lee Pending').waitFor();
    assert.equal(await page.locator('#deletionStatusFilter button').count(), 6, 'dual-control filters preserved');
    assert.deepEqual(await section(page).locator('li[data-user-id]').evaluateAll(l => l.map(x => x.dataset.userId)), ['110', '104']);
  })();
  if (shots) await page.screenshot({ path: `${shots}/desktop-both.png`, fullPage: true });
  await check('delete: real <dialog> requires DELETE; wrong text sends nothing; right text deletes only that account', async () => {
    await section(page).locator('li[data-user-id="104"]').getByRole('button', { name: 'Delete permanently' }).click();
    const dlg = page.locator('dialog.admin-ui-dialog[open]'); await dlg.waitFor();
    assert.match(await dlg.innerText(), /irreversibly removed/);
    if (shots) await page.screenshot({ path: `${shots}/desktop-delete-dialog.png` });
    await dlg.locator('input').fill('nope');
    await dlg.getByRole('button', { name: 'Confirm destructive action' }).click();
    await page.waitForFunction(() => !document.querySelector('dialog[open]'));
    assert.deepEqual(posts(state), [], 'mismatch sent nothing');
    await section(page).locator('li[data-user-id="104"]').getByRole('button', { name: 'Delete permanently' }).click();
    await dlg.waitFor(); await dlg.locator('input').fill('DELETE');
    await dlg.getByRole('button', { name: 'Confirm destructive action' }).click();
    await page.waitForFunction(() => !document.querySelector('#selfDeletionSection li[data-user-id="104"]'));
    assert.deepEqual(posts(state), ['POST /api/admin/self-deletion-requests/104/execute']);
    assert.equal(await section(page).locator('li[data-user-id="110"]').count(), 1);
    assert.equal(await page.locator('#deletionList').getByText('Lee Pending').count(), 1, 'dual-control request with the SAME numeric id (104) is untouched');
    await page.locator('.admin-ui-toast--success', { hasText: 'Account permanently deleted.' }).waitFor();
  })();
  await check('restore collision: stays queued with the server explanation, deletion still available', async () => {
    state.self.push({ id: 104, fullName: 'Taylor Cruz', originalEmail: 'taylor@example.test', requestedAt: '2026-10-15T11:00:00' });
    await section(page).getByRole('button', { name: 'Refresh' }).click();
    const row = section(page).locator('li[data-user-id="104"]'); await row.waitFor();
    await row.getByRole('button', { name: 'Restore access' }).click();
    const dlg = page.locator('dialog.admin-ui-dialog[open]'); await dlg.waitFor();
    assert.match(await dlg.innerText(), /access is restored/);
    assert.equal(await dlg.locator('input').count(), 0);
    await dlg.getByRole('button', { name: 'Confirm' }).click();
    await row.locator('.sdq__note--conflict').waitFor();
    assert.match(await row.innerText(), /already used by a newer account/);
    assert.equal(await row.getByRole('button', { name: 'Delete permanently' }).getAttribute('aria-disabled'), 'false');
    if (shots) await page.screenshot({ path: `${shots}/desktop-conflict.png`, fullPage: true });
  })();
  await check('only existing endpoints, no page errors', async () => {
    const known = /^(GET \/api\/(me|admin\/(self-deletion-requests|deletion-requests|users|banned-emails|activity-log|overview|reports\/pending-count|topics|materials|categories|material-categories))|POST \/api\/admin\/self-deletion-requests\/\d+\/(execute|restore))$/;
    assert.ok(state.requests.every(r => known.test(r)), 'unexpected: ' + state.requests.filter(r => !known.test(r)).join(', '));
    assert.deepEqual(state.errors, []);
  })();
  await context.close();
}

{
  // Phase 5: the admin-initiated (dual-control) confirmations still use the legacy #modal host. Real Chromium keyboard behavior.
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/deletion-requests'); await ready(page);
  await page.locator('#deletionList .admin-card').first().waitFor();
  const where = () => page.evaluate(() => { const a = document.activeElement; return { id: a.id, text: (a.textContent || '').trim().slice(0, 24), inModal: !!a.closest('#modal') }; });
  const modalShown = () => page.evaluate(() => getComputedStyle(document.getElementById('modal')).display !== 'none');
  await check('dual-control Reject confirmation: dialog role, focus inside, Tab stays inside, Escape closes and returns focus, nothing sent', async () => {
    const reject = page.locator('#deletionList button', { hasText: 'Reject' }).first();
    await reject.focus(); await page.keyboard.press('Enter');
    await page.locator('#modal .modal-box[role="dialog"][aria-modal="true"]').waitFor();
    assert.equal((await where()).id, 'rejectDeletionNote');
    for (let i = 0; i < 7; i++) { await page.keyboard.press('Tab'); assert.equal((await where()).inModal, true, 'Tab #' + (i + 1) + ' left the dialog'); }
    for (let i = 0; i < 4; i++) { await page.keyboard.press('Shift+Tab'); assert.equal((await where()).inModal, true, 'Shift+Tab left the dialog'); }
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => getComputedStyle(document.getElementById('modal')).display === 'none');
    assert.equal(await modalShown(), false);
    assert.match((await where()).text, /Reject/, 'focus returned to the Reject button');
    assert.deepEqual(posts(state), [], 'Escape sent nothing');
  })();
  await check('dual-control Cancel Request: a double click sends one POST', async () => {
    const cancel = page.locator('#deletionList .btn-view').first();
    await cancel.dblclick();
    await page.waitForTimeout(250);
    assert.equal(posts(state).filter(r => /\/deletion-requests\/\d+\/cancel$/.test(r)).length, 1);
  })();
  await check('no page errors (dual-control keyboard)', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

{
  const { page, state, context } = await newPage({ failSelf: true });
  await page.goto(base + '/admin.html#/people/deletion-requests'); await ready(page);
  await check('self queue load error shows Retry; dual queue unaffected; Retry recovers', async () => {
    await section(page).getByText('Could not load self-requested deletions: Database down').waitFor();
    await page.locator('#deletionList').getByText('Lee Pending').waitFor();
    state.failSelf = false;
    await section(page).getByRole('button', { name: 'Retry' }).click();
    await section(page).locator('li[data-user-id="104"]').waitFor();
  })();
  await check('no page errors', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

{
  const { page, state, context } = await newPage({ viewport: { width: 390, height: 780 } });
  await page.goto(base + '/admin.html#/people/deletion-requests'); await ready(page);
  await section(page).locator('li[data-user-id="104"]').waitFor();
  await check('mobile width: no horizontal page scroll; actions are reachable', async () => {
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no horizontal page scroll');
    const box = await section(page).locator('li[data-user-id="104"]').getByRole('button', { name: 'Delete permanently' }).boundingBox();
    assert.ok(box && box.x >= 0 && box.x + box.width <= 391, JSON.stringify(box));
    if (shots) await page.screenshot({ path: `${shots}/mobile.png`, fullPage: true });
  })();
  await check('no page errors', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

await browser.close();
server.close();
let failed = 0;
for (const [pass, name, err] of results) { console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}`); if (!pass) { failed += 1; console.log('      ' + String(err.message).split('\n').slice(0, 6).join('\n      ')); } }
console.log(`${results.length - failed}/${results.length} browser checks passed (mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
