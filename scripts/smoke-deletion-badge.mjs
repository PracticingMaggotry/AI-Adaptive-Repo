// Real-browser smoke test for the COMBINED People > Deletion requests sidebar badge, against the production
// admin.html (real admin.js boot, real classic legacy script, real ES modules, real router) with a mocked API.
// Run: node --experimental-default-type=module scripts/smoke-deletion-badge.mjs [--require-browser]
// Needs the `playwright` package and a Chromium it can launch. Without it: SKIPPED (exit 0), or exit 1 with --require-browser.
// Only /api/* is mocked. There is no Spring backend and no authenticated session: this checks front-end behaviour only.
// What it adds over test-deletion-badge.mjs (jsdom): the real boot path, where admin.js calls the legacy
// refreshDeletionBadge() before any view exists and that classic script has to import() the ES module.
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
  let data;
  try { data = await readFile(file); } catch { res.writeHead(404).end('not found'); return; }   // read first: a missing file must be a clean 404
  res.writeHead(200, { 'content-type': mime[path.extname(file)] || 'application/octet-stream' }).end(data);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await pw.chromium.launch();
const results = [];
const check = (name, fn) => async (...a) => { try { await fn(...a); results.push([true, name]); } catch (e) { results.push([false, name, e]); } };

const dualReq = (id, status, extra = {}) => ({ id, status, targetName: `Target ${id}`, targetEmail: `t${id}@example.test`, reason: 'Reason', requestedBy: 'pat@example.test', requestedAt: '2026-10-12T10:00:00', approveCount: 1, requiredCount: 3, callerVoted: false, votes: [], ...extra });
const selfReq = id => ({ id, fullName: `Self ${id}`, originalEmail: `s${id}@example.test`, requestedAt: '2026-10-15T11:00:00' });
// dual-control request ids 104/105 and account id 104 overlap on purpose
const fresh = () => ({ dual: [dualReq(104, 'PENDING'), dualReq(105, 'APPROVED', { approveCount: 3 })], self: [selfReq(104), selfReq(110)], mode: {}, requests: [], errors: [] });

function mockApi(state) {
  return async (route) => {
    const req = route.request(); const url = new URL(req.url()); const p = url.pathname; const method = req.method();
    state.requests.push(`${method} ${p}${url.search}`);
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    const respond = (key, rows) => {
      const m = state.mode[key] || 'ok';
      if (m === 'fail') return json({ success: false, message: `${key} unavailable` }, 500);
      if (m === 'malformed') return json({ success: true });
      return json({ success: true, requests: rows });
    };
    if (p === '/api/me') return json({ name: 'Test Admin', isAdmin: true });
    if (p === '/api/admin/self-deletion-requests' && method === 'GET') return respond('self', state.self);
    let m = p.match(/^\/api\/admin\/self-deletion-requests\/(\d+)\/(execute|restore)$/);
    if (m && method === 'POST') { state.self = state.self.filter(x => x.id !== Number(m[1])); return json({ success: true, message: 'Done.' }); }
    if (p === '/api/admin/deletion-requests' && method === 'GET') {
      const s = url.searchParams.get('status') || 'ALL';
      return respond(s === 'PENDING' ? 'pending' : s === 'APPROVED' ? 'approved' : 'other', state.dual.filter(r => s === 'ALL' || r.status === s));
    }
    m = p.match(/^\/api\/admin\/deletion-requests\/(\d+)\/(vote|execute|cancel)$/);
    if (m && method === 'POST') {
      const r = state.dual.find(x => x.id === Number(m[1]));
      if (m[2] === 'cancel') r.status = 'CANCELLED';
      if (m[2] === 'execute') r.status = 'EXECUTED';
      return json({ success: true });
    }
    if (p === '/api/admin/users') return json({ success: true, users: [] });
    if (p === '/api/admin/overview') return json({ stats: {} });
    if (p === '/api/admin/activity-log') return json({ success: true, log: [] });
    if (p === '/api/admin/reports/pending-count') return json({ count: 0 });
    if (p === '/api/admin/banned-emails') return json({ bannedEmails: [] });
    if (p === '/api/admin/topics' || p === '/api/admin/materials' || p === '/api/admin/categories') return json([]);
    if (p === '/api/admin/material-categories') return json({ categoryBreakdown: [], subCategoryBreakdown: [] });
    return json({});
  };
}
async function newPage(mutate) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const state = fresh(); if (mutate) mutate(state);
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
const badge = page => page.locator('#deletionBadge');
const badgeIs = (page, text) => page.waitForFunction(t => { const b = document.getElementById('deletionBadge'); return b.textContent === t && b.style.display === 'inline-block'; }, text);
const badgeHidden = page => page.waitForFunction(() => document.getElementById('deletionBadge').style.display === 'none');
const gets = (state, suffix) => state.requests.filter(r => r === `GET ${suffix}`).length;
const PENDING = '/api/admin/deletion-requests?status=PENDING';
const APPROVED = '/api/admin/deletion-requests?status=APPROVED';
const SELF = '/api/admin/self-deletion-requests';
const settle = page => page.waitForTimeout(400);

{ // 1. boot on the Overview: the legacy boot refresh imports the module in a real browser
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/overview');
  await check('real boot path: legacy refreshDeletionBadge() imports the ES module and the badge is the combined total (2 dual-control + 2 self = 4)', async () => {
    await badgeIs(page, '4');
    assert.equal(await badge(page).getAttribute('data-badge-state'), 'known');
    assert.match((await badge(page).getAttribute('title')) || '', /2 admin-initiated requests.*2 self-requested deletions/);
    assert.equal(await page.evaluate(() => typeof window.adminDeletionBadge?.refresh), 'function', 'one shared instance on window');
  })();
  await check('Overview does not add a request for the badge (its own three sources + the boot round only)', async () => {
    await settle(page);
    // boot round (1 each) + Overview's own sources (1 each); the Overview then hands its responses to the badge
    assert.equal(gets(state, APPROVED), 2); assert.equal(gets(state, SELF), 2); assert.equal(gets(state, PENDING), 2);
  })();
  await check('no page errors; only existing endpoints; GET only for the badge', async () => {
    const known = /^(GET \/api\/(me|admin\/(self-deletion-requests|deletion-requests|users|banned-emails|activity-log|overview|reports|quiz-activity|reports\/pending-count|topics|materials|categories|material-categories)))/;
    assert.ok(state.requests.every(r => known.test(r)), 'unexpected: ' + state.requests.filter(r => !known.test(r)).join(', '));
    assert.deepEqual(state.errors, []);
  })();
  await context.close();
}

{ // 1b. a page with NO badge-aware view: only the boot-time legacy refresh can have painted the badge
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/banned-emails');
  await check('boot path alone (no Overview, no Deletion requests view): the classic script import()s the module and paints the combined total', async () => {
    await badgeIs(page, '4');
    assert.deepEqual(state.errors, []);
  })();
  await context.close();
}

{ // 2. landing directly on Deletion requests; both workflows update the badge
  const { page, state, context } = await newPage();
  await page.goto(base + '/admin.html#/people/deletion-requests');
  await badgeIs(page, '4');
  await page.locator('#selfDeletionSection li[data-user-id="104"]').waitFor();
  await check('the page keeps the two counts apart and says the badge is only a navigation shortcut', async () => {
    assert.match(await page.locator('#selfDeletionHeading').innerText(), /Self-requested deletions \(2\)/);
    assert.match(await page.locator('.sdq-guide__badge-note').innerText(), /navigation shortcut/);
    assert.equal(await page.locator('.sdq-guide__card').count(), 2);
  })();
  await check('landing on the page while the boot round runs does not start a duplicate round', async () => {
    await settle(page);
    assert.ok(gets(state, APPROVED) <= 2, `APPROVED requested ${gets(state, APPROVED)} times`);
  })();
  await check('self-requested: permanent deletion lowers the badge (4 -> 3); the dual-control request with the same number stays', async () => {
    await page.locator('#selfDeletionSection li[data-user-id="104"]').getByRole('button', { name: 'Delete permanently' }).click();
    const dlg = page.locator('dialog.admin-ui-dialog[open]'); await dlg.waitFor();
    await dlg.locator('input').fill('DELETE');
    await dlg.getByRole('button', { name: 'Confirm destructive action' }).click();
    await badgeIs(page, '3');
    assert.equal(await page.locator('#deletionList').getByText('Target 104').count(), 1);
  })();
  await check('self-requested: restore lowers the badge (3 -> 2)', async () => {
    await page.locator('#selfDeletionSection li[data-user-id="110"]').getByRole('button', { name: 'Restore access' }).click();
    const dlg = page.locator('dialog.admin-ui-dialog[open]'); await dlg.waitFor();
    await dlg.getByRole('button', { name: 'Confirm' }).click();
    await badgeIs(page, '2');
  })();
  await check('admin-initiated: cancel lowers the badge (2 -> 1); execute (approved) lowers it again (1 -> hidden)', async () => {
    await page.locator('#deletionList .admin-card', { hasText: 'Target 104' }).getByRole('button', { name: 'Cancel Request' }).click();
    await badgeIs(page, '1');
    await page.locator('#deletionStatusFilter').getByRole('button', { name: /Approved/ }).click();
    await page.locator('#deletionList .admin-card', { hasText: 'Target 105' }).getByRole('button', { name: /Execute Deletion/ }).click();
    await page.locator('#confirmExecuteBtn').click();
    await badgeHidden(page);
    assert.equal(await badge(page).getAttribute('data-badge-state'), 'known', 'a real zero from three healthy sources');
  })();
  await check('no page errors', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

{ // 3. an endpoint failing at boot: "?" rather than a false zero or a partial total; recovers
  const { page, state, context } = await newPage(s => { s.mode.self = 'fail'; });
  await page.goto(base + '/admin.html#/overview');
  await check('self endpoint failing at boot: the badge shows "?" (not hidden, not the dual-control-only 2)', async () => {
    await badgeIs(page, '?');
    assert.equal(await badge(page).getAttribute('data-badge-state'), 'unknown');
    assert.match((await badge(page).getAttribute('title')) || '', /unavailable/);
  })();
  await check('recovery: the next complete round (Overview Refresh) publishes the real total', async () => {
    state.mode.self = 'ok';
    await page.getByRole('button', { name: /Refresh/ }).click();
    await badgeIs(page, '4');
  })();
  await check('after a complete count, a later failure keeps it, flagged stale', async () => {
    state.mode.approved = 'malformed'; state.dual.push(dualReq(300, 'PENDING'));
    await page.getByRole('button', { name: /Refresh/ }).click();
    await page.waitForFunction(() => document.getElementById('deletionBadge').dataset.badgeState === 'stale');
    assert.equal(await badge(page).textContent(), '4', 'not the partial 5, not 0');
    assert.match((await badge(page).getAttribute('title')) || '', /out of date/);
  })();
  await check('no page errors (failed requests are warnings, not errors)', async () => assert.deepEqual(state.errors, []))();
  await context.close();
}

await browser.close();
server.close();
let failed = 0;
for (const [pass, name, err] of results) { console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}`); if (!pass) { failed += 1; console.log('      ' + String(err.message).split('\n').slice(0, 6).join('\n      ')); } }
console.log(`${results.length - failed}/${results.length} browser checks passed (mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
