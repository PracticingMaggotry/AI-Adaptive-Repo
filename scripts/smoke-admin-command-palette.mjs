/**
 * Browser tests for the admin command palette (Ctrl/Cmd+K navigation search).
 *
 * Part A drives the palette module in isolation (real routes.js data, real router.js, real window hash,
 * stub views) to cover keyboard, focus, IME, hostile text and repeated initialization.
 * Part B loads the REAL admin.html with /api/* mocked (Playwright interception, CDN libraries stubbed)
 * to prove the palette is wired into the shell and uses the router.
 *
 * This is NOT an authenticated end-to-end test: there is no Spring backend and no real session. It runs
 * in headless Chromium only; Firefox, Safari, real macOS keyboards and screen readers are not covered.
 *
 * Run: node scripts/smoke-admin-command-palette.mjs
 * Needs `playwright` and Chromium (PLAYWRIGHT_BROWSERS_PATH and NODE_PATH are honoured).
 * Exits 2 (not a pass) if no driver is available; --require-browser is accepted for symmetry.
 */
import http from 'node:http';
import path from 'node:path';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';

const staticDir = fileURLToPath(new URL('../src/main/resources/static/', import.meta.url));

async function loadPlaywright() {
  try { return await import('playwright'); } catch { /* fall through */ }
  const dirs = [...(process.env.NODE_PATH || '').split(path.delimiter).filter(Boolean)];
  try { dirs.push(execSync('npm root -g', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()); } catch { /* ignore */ }
  for (const dir of dirs) { try { return createRequire(path.join(dir, 'x.js'))('playwright'); } catch { /* next */ } }
  return null;
}
const pw = await loadPlaywright();
if (!pw) { console.error('SKIPPED (exit 2): the playwright package could not be loaded. Nothing was verified.'); process.exit(2); }
const chromium = pw.chromium || pw.default?.chromium;

const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg' };
const TEST_PAGE = `<!doctype html><meta charset="utf-8"><title>palette test</title>
<link rel="stylesheet" href="/admin/search/command-palette.css">
<body>
<header><button type="button" id="adminSearchTrigger" class="shell-search-btn" hidden><span aria-hidden="true">S</span><span class="shell-search-label">Search</span><kbd class="shell-search-hint" data-cmdk-hint aria-hidden="true"></kbd></button></header>
<button id="before">before</button>
<input id="plain" type="text"><textarea id="ta"></textarea><div id="ce" contenteditable="true" tabindex="0">x</div>
<div class="cm-editor"><div id="cm" tabindex="0">code</div></div>
<div id="swallow" tabindex="0">swallower</div><input type="checkbox" id="cb">
<h1 id="adminPageTitle" tabindex="-1">Title</h1><main id="mount"></main></body>`;
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/__palette-test.html') { res.writeHead(200, { 'content-type': 'text/html' }).end(TEST_PAGE); return; }
  const rel = decodeURIComponent(url.pathname).replace(/^\/+/, '') || 'index.html';
  const file = path.join(staticDir, rel);
  if (!file.startsWith(staticDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end('not found'); return; }
  res.writeHead(200, { 'content-type': mime[path.extname(file)] || 'application/octet-stream' }).end(fs.readFileSync(file));
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const launch = { headless: true, args: ['--no-sandbox'] };
if (process.env.CHROME_PATH) launch.executablePath = process.env.CHROME_PATH;
const browser = await chromium.launch(launch);
const results = [];
const test = (name, fn) => ({ name, fn });
async function run(list, ctx) {
  for (const { name, fn } of list) {
    try { await fn(ctx); results.push([true, name]); } catch (e) { results.push([false, name, e]); }
  }
}

// ═════════════════════════════ Part A: module in isolation ═════════════════════════════════
const ctxA = await (async () => {
  const context = await browser.newContext({ viewport: { width: 1100, height: 800 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(() => {
    // Records every listener registered with an AbortSignal so tests can count the LIVE ones.
    window.__sig = [];
    const orig = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (type, fn, opts) {
      if (opts && typeof opts === 'object' && opts.signal) window.__sig.push({ type, signal: opts.signal, target: this === document ? 'document' : this === window ? 'window' : 'node' });
      return orig.call(this, type, fn, opts);
    };
  });
  await page.goto(base + '/__palette-test.html');
  await page.evaluate(async () => {
    const { createCommandPalette, initCommandPalette } = await import('/admin/search/command-palette.js');
    const { createRouter } = await import('/admin/router.js');
    const real = await import('/admin/routes.js');
    const stub = () => Promise.resolve({ default: { mount(el) { el.textContent = 'view'; }, unmount() {} } });
    const routes = real.routes.map((r) => ({ ...r, load: stub, shellLoad: stub }));
    const router = createRouter({ routes, aliases: real.aliases, defaultPath: real.defaultPath, mount: document.getElementById('mount') });
    window.T = { createCommandPalette, initCommandPalette, createRouter, router, routes, sections: real.sections, aliases: real.aliases, real };
    window.created = [];
    window.mk = (over = {}) => {
      const p = createCommandPalette({
        routes, sections: real.sections, aliases: real.aliases,
        navigate: (t) => router.navigate(t), resolve: (t) => router.resolve(t),
        getCurrentPath: () => { const m = router.current(); return m ? m.path : null; },
        trigger: document.getElementById('adminSearchTrigger'), ...over
      });
      window.created.push(p);
      return p;
    };
    await router.start();
  });
  return { page, errors };
})();

const key = (page, combo) => page.keyboard.press(combo);
const visible = (page) => page.evaluate(() => { const r = document.querySelector('[data-cmdk-root]'); return !!r && !r.hidden && r.getClientRects().length > 0; });
const active = (page) => page.evaluate(() => { const a = document.activeElement; return a ? (a.id || a.className || a.tagName) : null; });
const hash = (page) => page.evaluate(() => location.hash);
const optionTitles = (page) => page.evaluate(() => [...document.querySelectorAll('.cmdk-option .cmdk-opt-title')].map((n) => n.textContent));
const waitPath = (page, p) => page.waitForFunction((x) => window.T.router.current() && window.T.router.current().path === x, p);
async function reset(page) {
  await page.evaluate(async () => {
    for (const p of window.created.splice(0)) p.destroy();
    document.querySelectorAll('[data-cmdk-root]').forEach((n) => n.remove());
    document.querySelectorAll('dialog').forEach((d) => d.remove());
    delete window.__pwned;
    await window.T.router.navigate('/overview');
  });
  await waitPath(page, '/overview');
  await page.evaluate(() => document.getElementById('before').focus());
}

const A = [
  test('trigger is hidden before init, shown with the platform hint after, hidden again after destroy', async ({ page }) => {
    await reset(page);
    const before = await page.evaluate(() => document.getElementById('adminSearchTrigger').hidden);
    assert.equal(before, true);
    await page.evaluate(() => window.mk({ mac: false }));
    const s = await page.evaluate(() => { const b = document.getElementById('adminSearchTrigger'); return { hidden: b.hidden, hint: b.querySelector('[data-cmdk-hint]').textContent, ks: b.getAttribute('aria-keyshortcuts'), popup: b.getAttribute('aria-haspopup') }; });
    assert.deepEqual(s, { hidden: false, hint: 'Ctrl K', ks: 'Control+K', popup: 'dialog' });
    await page.evaluate(() => window.created[0].destroy());
    assert.equal(await page.evaluate(() => document.getElementById('adminSearchTrigger').hidden), true);
  }),
  test('Ctrl+K opens, focuses the input, exposes dialog + combobox + listbox semantics', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    assert.equal(await visible(page), true);
    assert.equal(await active(page), 'cmdk-input');
    const s = await page.evaluate(() => {
      const panel = document.querySelector('.cmdk-panel'), input = document.querySelector('.cmdk-input'), list = document.querySelector('.cmdk-list');
      const opts = [...list.querySelectorAll('[role=option]')];
      return {
        dialog: panel.getAttribute('role'), modal: panel.getAttribute('aria-modal'), labelled: document.getElementById(panel.getAttribute('aria-labelledby')).textContent,
        combo: input.getAttribute('role'), expanded: input.getAttribute('aria-expanded'), controls: input.getAttribute('aria-controls') === list.id,
        autocomplete: input.getAttribute('aria-autocomplete'), name: input.getAttribute('aria-label'), listbox: list.getAttribute('role'),
        n: opts.length, selected: opts.filter((o) => o.getAttribute('aria-selected') === 'true').length,
        activedesc: input.getAttribute('aria-activedescendant') === opts[0].id, uniqueIds: new Set(opts.map((o) => o.id)).size === opts.length,
        expected: window.T.routes.filter((r) => !r.path.includes(':')).length,
        scope: document.querySelector('.cmdk-scope').textContent
      };
    });
    assert.equal(s.dialog, 'dialog'); assert.equal(s.modal, 'true'); assert.equal(s.labelled, 'Search admin pages');
    assert.equal(s.combo, 'combobox'); assert.equal(s.expanded, 'true'); assert.equal(s.controls, true); assert.equal(s.autocomplete, 'list');
    assert.equal(s.listbox, 'listbox'); assert.equal(s.selected, 1); assert.equal(s.activedesc, true); assert.equal(s.uniqueIds, true);
    assert.equal(s.n, s.expected, 'empty query lists every registry destination');
    assert.match(s.scope, /does not search users, materials, questions or other records/);
  }),
  test('Ctrl+K again closes it (toggle) and restores focus', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k'); await key(page, 'Control+k');
    assert.equal(await visible(page), false);
    assert.equal(await active(page), 'before');
  }),
  test('Escape closes, restores focus to the opener, and is not seen by other Escape handlers', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => { window.__esc = 0; document.addEventListener('keydown', (e) => { if (e.key === 'Escape') window.__esc += 1; }); window.mk({ mac: false }); document.getElementById('swallow').focus(); });
    await key(page, 'Control+k');
    assert.equal(await visible(page), true);
    await key(page, 'Escape');
    assert.equal(await visible(page), false);
    assert.equal(await active(page), 'swallow');
    assert.equal(await page.evaluate(() => window.__esc), 0, 'a legacy document-level Escape handler (nav drawer, review modal) must not fire');
    await key(page, 'Escape');
    assert.equal(await page.evaluate(() => window.__esc), 1, 'with the palette closed, Escape reaches other handlers again');
  }),
  test('visible Close button and backdrop click close it and restore focus', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    const closeText = await page.locator('.cmdk-close').innerText();
    assert.match(closeText, /Close/);
    await page.locator('.cmdk-close').click();
    assert.equal(await visible(page), false); assert.equal(await active(page), 'before');
    await key(page, 'Control+k');
    await page.mouse.click(5, 790);               // outside the panel, on the backdrop
    assert.equal(await visible(page), false); assert.equal(await active(page), 'before');
  }),
  test('trigger button opens it; closing returns focus to the button', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await page.locator('#adminSearchTrigger').click();
    assert.equal(await visible(page), true);
    await key(page, 'Escape');
    assert.equal(await active(page), 'adminSearchTrigger');
  }),
  test('arrow keys move the selection (with wrap), Enter navigates through the router', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    const sel = () => page.evaluate(() => { const i = document.querySelector('.cmdk-input'); const o = [...document.querySelectorAll('[role=option]')]; const k = o.findIndex((x) => x.getAttribute('aria-selected') === 'true'); return { k, ad: i.getAttribute('aria-activedescendant') === o[k].id, count: o.filter((x) => x.getAttribute('aria-selected') === 'true').length }; });
    await key(page, 'ArrowDown'); await key(page, 'ArrowDown');
    assert.deepEqual(await sel(), { k: 2, ad: true, count: 1 });
    await key(page, 'ArrowUp'); await key(page, 'ArrowUp'); await key(page, 'ArrowUp');
    const last = await page.evaluate(() => document.querySelectorAll('[role=option]').length - 1);
    assert.deepEqual(await sel(), { k: last, ad: true, count: 1 }, 'ArrowUp wraps to the last item');
    await key(page, 'ArrowDown');
    assert.equal((await sel()).k, 0, 'ArrowDown wraps to the first item');
    await key(page, 'ArrowDown');                       // second destination: Users
    await key(page, 'Enter');
    await waitPath(page, '/people/users');
    assert.equal(await hash(page), '#/people/users');
    assert.equal(await visible(page), false);
    assert.equal(await active(page), 'before', 'focus is not lost when the page content does not re-focus anything');
  }),
  test('typing filters; Enter opens a canonical hash route; Back and Forward still work', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    await page.keyboard.type('ban');
    assert.deepEqual(await optionTitles(page), ['Banned emails']);
    await key(page, 'Enter');
    await waitPath(page, '/people/banned-emails');
    assert.equal(await hash(page), '#/people/banned-emails');
    await key(page, 'Control+k'); await page.keyboard.type('activity'); await key(page, 'Enter');
    await waitPath(page, '/audit/activity-log');
    await page.goBack(); await waitPath(page, '/people/banned-emails');
    assert.equal(await hash(page), '#/people/banned-emails', 'Back returns to the previous palette destination');
    await page.goBack(); await waitPath(page, '/overview');
    await page.goForward(); await waitPath(page, '/people/banned-emails');
    assert.equal(await hash(page), '#/people/banned-emails', 'Forward works');
  }),
  test('old-hash aliases and section names find pages ("reports", "deletionrequests", "people")', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    await page.keyboard.type('reports');
    assert.equal((await optionTitles(page))[0], 'Platform report');
    await page.fill('.cmdk-input', 'deletionrequests');
    assert.deepEqual(await optionTitles(page), ['Deletion requests']);
    await page.fill('.cmdk-input', 'people');
    assert.deepEqual(await optionTitles(page), ['Users', 'Deletion requests', 'Banned emails']);
    await page.fill('.cmdk-input', 'study');
    assert.deepEqual(await optionTitles(page), ['Participants', 'Assigned handouts', 'Data exports']);
  }),
  test('status tags: Current page, Partial, Opens dialog', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    const tags = await page.evaluate(() => Object.fromEntries([...document.querySelectorAll('.cmdk-option')].map((o) => [o.querySelector('.cmdk-opt-title').textContent, [...o.querySelectorAll('.cmdk-tag')].map((t) => t.textContent)])));
    assert.deepEqual(tags['Overview'], ['Current page']);
    assert.deepEqual(tags['Configuration'], []);
    assert.deepEqual(tags['Reliability'], ['Partial']);
    assert.deepEqual(tags['Usage limits'], ['Opens dialog']);
  }),
  test('pointer: hover moves the selection, click activates', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    const target = page.locator('.cmdk-option', { hasText: 'Banned emails' });
    await target.hover();
    assert.equal(await target.getAttribute('aria-selected'), 'true');
    await target.click();
    await waitPath(page, '/people/banned-emails');
    assert.equal(await visible(page), false);
  }),
  test('no results: message, collapsed listbox, Enter does nothing, palette stays open', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    await page.keyboard.type('zzzz');
    const s = await page.evaluate(() => ({
      empty: !document.querySelector('.cmdk-empty').hidden, text: document.querySelector('.cmdk-empty').textContent, listHidden: document.querySelector('.cmdk-list').hidden,
      expanded: document.querySelector('.cmdk-input').getAttribute('aria-expanded'), ad: document.querySelector('.cmdk-input').hasAttribute('aria-activedescendant'),
      live: document.querySelector('[role=status]').textContent, options: document.querySelectorAll('[role=option]').length
    }));
    assert.equal(s.empty, true); assert.match(s.text, /No pages or actions match “zzzz”\./); assert.equal(s.listHidden, true);
    assert.equal(s.expanded, 'false'); assert.equal(s.ad, false); assert.match(s.live, /No results for zzzz/); assert.equal(s.options, 0);
    const before = await hash(page);
    await key(page, 'Enter'); await key(page, 'ArrowDown');
    assert.equal(await hash(page), before); assert.equal(await visible(page), true);
    await page.fill('.cmdk-input', '');
    assert.equal(await page.evaluate(() => document.querySelector('.cmdk-empty').hidden), true, 'clearing the query restores the list');
  }),
  test('empty registry: "no pages available" state', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false, routes: [] }));
    await key(page, 'Control+k');
    assert.match(await page.locator('.cmdk-empty').innerText(), /No pages are available to search\./);
  }),
  test('focus stays inside while open: Tab / Shift+Tab cycle, outside focus is pulled back', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    const seen = new Set();
    for (let i = 0; i < 6; i += 1) { await key(page, 'Tab'); seen.add(await active(page)); }
    for (let i = 0; i < 6; i += 1) { await key(page, 'Shift+Tab'); seen.add(await active(page)); }
    assert.deepEqual([...seen].sort(), ['cmdk-close', 'cmdk-input']);
    await page.evaluate(() => document.getElementById('before').focus());
    assert.equal(await active(page), 'cmdk-input', 'programmatic focus on the page behind is redirected');
    await page.evaluate(() => document.getElementById('plain').focus());
    assert.equal(await active(page), 'cmdk-input');
  }),
  test('Enter on the Close button closes instead of activating the selected result', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k'); await key(page, 'Shift+Tab');
    assert.equal(await active(page), 'cmdk-close');
    await key(page, 'Enter');
    assert.equal(await visible(page), false); assert.equal(await hash(page), '#/overview');
  }),
  test('shortcut is not intercepted in text inputs, textareas, contenteditable or embedded editors', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => { window.mk({ mac: false }); window.__prevented = []; document.addEventListener('keydown', (e) => { if (e.key === 'k') window.__prevented.push(e.defaultPrevented); }); });
    for (const id of ['plain', 'ta', 'ce', 'cm']) {
      await page.evaluate((x) => document.getElementById(x).focus(), id);
      await key(page, 'Control+k');
      assert.equal(await visible(page), false, `#${id} keeps Ctrl+K`);
    }
    assert.deepEqual(await page.evaluate(() => window.__prevented), [false, false, false, false], 'the event was not cancelled either');
    await page.evaluate(() => document.getElementById('cb').focus());
    await key(page, 'Control+k');
    assert.equal(await visible(page), true, 'a checkbox is not a text control');
  }),
  test('a component that already handled the shortcut (preventDefault) wins', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => { window.mk({ mac: false }); const d = document.createElement('div'); d.id = 'swallow2'; d.tabIndex = 0; d.addEventListener('keydown', (e) => { if (e.ctrlKey && e.key === 'k') e.preventDefault(); }); document.body.append(d); d.focus(); });
    await key(page, 'Control+k');
    assert.equal(await visible(page), false);
    await page.evaluate(() => document.getElementById('swallow2').remove());
  }),
  test('does not open over another modal (native dialog, legacy #modal, #crModal)', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => { window.mk({ mac: false }); const d = document.createElement('dialog'); d.id = 'dlg'; d.innerHTML = '<button id="dbtn">ok</button>'; document.body.append(d); d.showModal(); });
    await key(page, 'Control+k');
    assert.equal(await visible(page), false, 'native modal dialog open');
    await page.evaluate(() => document.getElementById('dlg').close());
    await key(page, 'Control+k'); assert.equal(await visible(page), true); await key(page, 'Escape');
    await page.evaluate(() => { const m = document.createElement('div'); m.id = 'modal'; m.style.cssText = 'display:flex'; m.innerHTML = '<div>legacy</div>'; document.body.append(m); });
    await key(page, 'Control+k'); assert.equal(await visible(page), false, 'legacy #modal visible');
    await page.evaluate(() => { document.getElementById('modal').style.display = 'none'; const c = document.createElement('div'); c.id = 'crModal'; c.className = 'open'; document.body.append(c); });
    await key(page, 'Control+k'); assert.equal(await visible(page), false, '#crModal.open');
    await page.evaluate(() => { document.getElementById('crModal').remove(); document.getElementById('modal').remove(); });
    await key(page, 'Control+k'); assert.equal(await visible(page), true, 'opens again once nothing else is modal');
  }),
  test('IME composition: shortcut, Enter and Escape are ignored while composing', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    const fire = (init) => page.evaluate((i) => { const e = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...i }); (document.activeElement || document.body).dispatchEvent(e); return e.defaultPrevented; }, init);
    await page.evaluate(() => document.getElementById('swallow').focus());
    assert.equal(await fire({ key: 'k', ctrlKey: true, isComposing: true }), false);
    assert.equal(await visible(page), false, 'Ctrl+K while composing');
    await key(page, 'Control+k');
    await page.keyboard.type('users');
    const h = await hash(page);
    await fire({ key: 'Enter', isComposing: true });
    await fire({ key: 'Enter', keyCode: 229 });
    assert.equal(await hash(page), h); assert.equal(await visible(page), true, 'Enter that confirms an IME candidate does not activate');
    await fire({ key: 'Escape', isComposing: true });
    assert.equal(await visible(page), true, 'Escape that cancels an IME candidate does not close');
    await fire({ key: 'ArrowDown', isComposing: true });
    assert.equal(await page.evaluate(() => document.querySelector('[role=option][aria-selected=true]').dataset.index), '0', 'arrows ignored while composing');
    await fire({ key: 'Escape' });
    assert.equal(await visible(page), false);
  }),
  test('macOS flavour: Cmd+K opens, Ctrl+K does not, hint reads ⌘K', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: true }));
    assert.equal(await page.evaluate(() => document.querySelector('[data-cmdk-hint]').textContent), '⌘K');
    assert.equal(await page.evaluate(() => document.getElementById('adminSearchTrigger').getAttribute('aria-keyshortcuts')), 'Meta+K');
    await key(page, 'Control+k'); assert.equal(await visible(page), false);
    await key(page, 'Meta+k'); assert.equal(await visible(page), true);
    await key(page, 'Meta+k'); assert.equal(await visible(page), false);
    await page.evaluate(() => window.created.splice(0).forEach((p) => p.destroy()));
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Meta+k'); assert.equal(await visible(page), false, 'Cmd+K is not the Windows/Linux shortcut');
  }),
  test('other modifier combinations do not open it', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    for (const combo of ['Control+Shift+k', 'Control+Alt+k', 'Control+j', 'k']) { await key(page, combo); assert.equal(await visible(page), false, combo); }
  }),
  test('hostile query is shown as text only', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    const q = '<img src=x onerror="window.__pwned=1"><script>window.__pwned=2</script>';
    await page.fill('.cmdk-input', q);
    const s = await page.evaluate(() => ({ imgs: document.querySelectorAll('[data-cmdk-root] img, [data-cmdk-root] script, [data-cmdk-root] svg').length, text: document.querySelector('.cmdk-empty-title').textContent, pwned: window.__pwned }));
    assert.equal(s.imgs, 0); assert.equal(s.pwned, undefined); assert.equal(s.text, `No pages or actions match “${q}”.`);
  }),
  test('hostile route titles/sections/actions are rendered as text; highlight uses <mark> only', async ({ page }) => {
    await reset(page);
    const hostile = '<img src=x onerror="window.__pwned=1">';
    await page.evaluate((h) => {
      window.mk({
        mac: false,
        routes: [{ path: '/system/x', section: 'system', title: h, status: 'ready', load() {} }, { path: '/system/b', section: 'system', title: '<b>bold</b> page', status: 'ready', load() {} }],
        sections: [{ id: 'system', label: '<i>System</i>' }], aliases: {},
        actions: [{ id: 'a', title: '<script>window.__pwned=3</script>', description: '<u>desc</u>', run() {} }],
        navigate() {}
      });
    }, hostile);
    await key(page, 'Control+k');
    await page.keyboard.type('b');
    const s = await page.evaluate(() => ({
      tags: [...new Set([...document.querySelectorAll('[data-cmdk-root] *')].map((n) => n.tagName))].sort(),
      titles: [...document.querySelectorAll('.cmdk-opt-title')].map((n) => n.textContent), pwned: window.__pwned,
      marks: [...document.querySelectorAll('mark')].map((m) => m.textContent)
    }));
    assert.equal(s.pwned, undefined);
    for (const bad of ['IMG', 'SCRIPT', 'B', 'I', 'U']) assert.ok(!s.tags.includes(bad), `no <${bad}> element created from data`);
    assert.ok(s.titles.includes('<b>bold</b> page'));
    assert.ok(s.marks.length > 0 && s.marks.every((m) => m.toLowerCase() === 'b'));
    await page.fill('.cmdk-input', '');
    const all = await page.evaluate(() => document.querySelector('.cmdk-list').textContent);
    assert.ok(all.includes(hostile) && all.includes('<i>System</i>') && all.includes('<script>window.__pwned=3</script>') && all.includes('<u>desc</u>'), 'raw strings appear verbatim as text');
  }),
  test('"user 12" offers the parameterised User detail route and navigates to its canonical hash', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    await page.keyboard.type('user 12');
    assert.deepEqual(await optionTitles(page), ['User detail 12']);
    assert.match(await page.locator('.cmdk-option').innerText(), /not looked up until the page loads/);
    await key(page, 'Enter');
    await waitPath(page, '/people/users/12');
    assert.equal(await hash(page), '#/people/users/12');
    await key(page, 'Control+k'); await page.keyboard.type('user <b>');
    assert.equal((await optionTitles(page)).length, 0, 'invalid parameter value is never offered');
  }),
  test('actions run the host function; a throwing or rejecting navigate does not break the palette', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => { window.__ran = 0; window.__errs = []; window.mk({ mac: false, onError: (e) => window.__errs.push(String(e && e.message)), actions: [{ id: 'r', title: 'Reload current page', keywords: ['refresh'], run: () => { window.__ran += 1; } }] }); });
    await key(page, 'Control+k'); await page.keyboard.type('refresh'); await key(page, 'Enter');
    assert.equal(await page.evaluate(() => window.__ran), 1);
    assert.equal(await visible(page), false); assert.equal(await active(page), 'before');
    await page.evaluate(() => window.created.splice(0).forEach((p) => p.destroy()));
    await page.evaluate(() => window.mk({ mac: false, onError: (e) => window.__errs.push(String(e && e.message)), navigate: () => { throw new Error('sync boom'); } }));
    await key(page, 'Control+k'); await page.keyboard.type('users'); await key(page, 'Enter');
    await page.evaluate(() => window.created.splice(0).forEach((p) => p.destroy()));
    await page.evaluate(() => window.mk({ mac: false, onError: (e) => window.__errs.push(String(e && e.message)), navigate: () => Promise.reject(new Error('async boom')) }));
    await key(page, 'Control+k'); await page.keyboard.type('users'); await key(page, 'Enter');
    await page.waitForFunction(() => window.__errs.length === 2);
    assert.deepEqual(await page.evaluate(() => window.__errs), ['sync boom', 'async boom']);
    await key(page, 'Control+k'); assert.equal(await visible(page), true, 'still usable afterwards');
  }),
  test('selecting the page already shown closes and keeps focus', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await page.evaluate(() => document.getElementById('swallow').focus());
    await key(page, 'Control+k'); await page.keyboard.type('overview'); await key(page, 'Enter');
    assert.equal(await visible(page), false); assert.equal(await active(page), 'swallow'); assert.equal(await hash(page), '#/overview');
  }),
  test('hashchange while open (Back button) closes it', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await page.evaluate(async () => { await window.T.router.navigate('/people/users'); });
    await waitPath(page, '/people/users');
    await key(page, 'Control+k'); assert.equal(await visible(page), true);
    await page.goBack(); await waitPath(page, '/overview');
    assert.equal(await visible(page), false);
  }),
  test('repeated initialization leaves ONE root and ONE set of live listeners; destroy removes them', async ({ page }) => {
    await reset(page);
    const live = () => page.evaluate(() => window.__sig.filter((r) => !r.signal.aborted).map((r) => r.target + ':' + r.type).sort());
    await page.evaluate(() => { window.T.initCommandPalette({ routes: window.T.routes, sections: window.T.sections, aliases: window.T.aliases, navigate: (t) => window.T.router.navigate(t), trigger: document.getElementById('adminSearchTrigger'), mac: false }); });
    const one = await live();
    assert.ok(one.length >= 6, 'baseline listeners registered: ' + one.join(','));
    await page.evaluate(() => { for (let i = 0; i < 4; i += 1) window.T.initCommandPalette({ routes: window.T.routes, sections: window.T.sections, aliases: window.T.aliases, navigate: (t) => window.T.router.navigate(t), trigger: document.getElementById('adminSearchTrigger'), mac: false }); });
    assert.deepEqual(await live(), one, 'five initializations hold exactly the listeners of one');
    assert.equal(await page.evaluate(() => document.querySelectorAll('[data-cmdk-root]').length), 1);
    await key(page, 'Control+k');
    assert.equal(await page.evaluate(() => document.querySelectorAll('[data-cmdk-root]:not([hidden])').length), 1);
    await key(page, 'Escape');
    await page.locator('#adminSearchTrigger').click();      // would open twice if the click handler had been duplicated
    assert.equal(await page.evaluate(() => document.querySelectorAll('[data-cmdk-root]:not([hidden])').length), 1);
    await key(page, 'Escape');
    await page.evaluate(() => window[Symbol.for('admin.commandPalette.active')].destroy());
    assert.deepEqual(await live(), [], 'destroy() removes every listener');
    assert.equal(await page.evaluate(() => document.querySelectorAll('[data-cmdk-root]').length), 0);
    assert.equal(await page.evaluate(() => document.getElementById('adminSearchTrigger').hidden), true);
    await key(page, 'Control+k'); assert.equal(await visible(page), false, 'shortcut is dead after destroy');
    await page.evaluate(() => { const p = window[Symbol.for('admin.commandPalette.active')]; if (p) throw new Error('registry not cleared'); });
  }),
  test('destroy while open restores focus; pagehide closes, and only a non-persisted pagehide destroys', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    await page.evaluate(() => window.created[0].destroy());
    assert.equal(await active(page), 'before');
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
    assert.equal(await visible(page), false);
    assert.equal(await page.evaluate(() => window.created[1].isDestroyed()), false, 'bfcache-eligible pagehide keeps the instance');
    await key(page, 'Control+k'); assert.equal(await visible(page), true); await key(page, 'Escape');
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: false })));
    assert.equal(await page.evaluate(() => window.created[1].isDestroyed()), true);
    assert.equal(await page.evaluate(() => window.__sig.filter((r) => !r.signal.aborted).length), 0);
  }),
  test('no network requests and no page errors from the palette module', async ({ page }) => {
    await reset(page);
    const requests = [];
    page.on('request', (r) => requests.push(r.url()));
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k'); await page.keyboard.type('users'); await key(page, 'Escape');
    assert.deepEqual(requests, []);
  }),
  test('dark/forced-colour safe basics: selected option is marked by more than colour', async ({ page }) => {
    await reset(page);
    await page.evaluate(() => window.mk({ mac: false }));
    await key(page, 'Control+k');
    const s = await page.evaluate(() => { const o = document.querySelector('[role=option][aria-selected=true]'); const cs = getComputedStyle(o); return { border: cs.borderLeftWidth, sel: o.getAttribute('aria-selected') }; });
    assert.equal(s.border, '3px'); assert.equal(s.sel, 'true');
  })
];
await run(A, ctxA);
if (ctxA.errors.length) results.push([false, 'Part A: no page errors', new Error(ctxA.errors.join('\n'))]); else results.push([true, 'Part A: no page errors or console errors']);

// ═════════════════════════════ Part B: real admin.html, mocked API ═════════════════════════
const users = [{ id: 1, fullName: 'Ada Admin', email: 'ada@example.test', isAdmin: true, flagged: false, archived: false }, { id: 2, fullName: 'Sam Student', email: 'sam@example.test', isAdmin: false, flagged: true, flagReason: 't', archived: false }];
function mockApi(state) {
  return async (route) => {
    const req = route.request(); const url = new URL(req.url()); const p = url.pathname;
    state.requests.push(`${req.method()} ${p}`);
    const json = (b, s = 200) => route.fulfill({ status: s, contentType: 'application/json', body: JSON.stringify(b) });
    if (p === '/api/me') return state.me ? json(state.me) : json({ message: 'unauthorized' }, 401);
    if (p === '/api/admin/overview') return json({ stats: { totalUsers: 2, totalTopics: 1, totalQuestions: 4, totalAttempts: 1, totalMaterials: 1 } });
    if (p === '/api/admin/users') return json({ success: true, users });
    if (p === '/api/admin/topics') return json([]);
    if (p === '/api/admin/material-categories') return json({ categoryBreakdown: [], subCategoryBreakdown: [] });
    if (p === '/api/admin/banned-emails') return json({ bannedEmails: [] });
    if (p === '/api/admin/activity-log') return json({ success: true, log: [] });
    if (p === '/api/admin/reports/pending-count') return json({ count: 0 });
    if (p === '/api/admin/reports') return json({ reports: [] });
    if (p === '/api/admin/deletion-requests') return json({ requests: [] });
    if (p === '/api/admin/self-deletion-requests') return json({ success: true, requests: [] });
    if (p === '/api/admin/assigned-handouts') return json({ handouts: [] });
    if (p === '/api/admin/participants') return json({ participants: [] });
    if (p === '/api/admin/daily-limits') return json({ limits: {} });
    if (p === '/api/materials/cancel-upload') return route.fulfill({ status: 204 });
    return json({});
  };
}
async function newAdminPage({ me = { name: 'Test Admin', isAdmin: true }, viewport = { width: 1280, height: 800 }, platform } = {}) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const state = { me, requests: [], errors: [] };
  page.on('pageerror', (e) => state.errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) state.errors.push('console: ' + m.text()); });
  await context.addInitScript((plat) => {
    window.Chart = class { constructor() {} destroy() {} update() {} };
    window.katex = { render() {} }; window.renderMathInElement = () => {}; window.hljs = { highlightElement() {}, highlightAll() {} };
    if (plat) {
      Object.defineProperty(Navigator.prototype, 'platform', { get: () => plat });
      Object.defineProperty(Navigator.prototype, 'userAgentData', { get: () => ({ platform: 'macOS' }) });
    }
  }, platform);
  await page.route(/^https?:\/\/(cdn\.jsdelivr\.net|cdnjs\.cloudflare\.com)\//, (r) => r.fulfill({ status: 200, contentType: r.request().url().endsWith('.css') ? 'text/css' : 'text/javascript', body: '' }));
  await page.route('**/api/**', mockApi(state));
  await page.route('**/logout', (r) => r.fulfill({ status: 200, body: '' }));
  return { page, context, state };
}
const adminReady = (page) => page.waitForFunction(() => document.title.endsWith('| Admin Panel | Learning System') && !/Checking admin access/.test(document.getElementById('adminContent').innerText), null, { timeout: 8000 });
const paletteAlive = (page) => page.waitForFunction(() => !document.getElementById('adminSearchTrigger').hidden, null, { timeout: 8000 });

const B = [
  test('admin.html: trigger appears after the shell starts; Ctrl+K opens the palette with every sidebar destination', async () => {
    const { page, state, context } = await newAdminPage();
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    assert.equal(await page.locator('#adminSearchTrigger').getAttribute('aria-keyshortcuts'), 'Control+K');
    await page.locator('#adminNav a[data-route]').first().focus();
    await key(page, 'Control+k');
    assert.equal(await visible(page), true);
    const sidebar = await page.evaluate(() => [...document.querySelectorAll('#adminNav a[data-route]')].map((a) => a.dataset.route));
    const palette = await page.evaluate(() => [...document.querySelectorAll('.cmdk-option[data-kind=route]')].map((o) => o.dataset.path));
    assert.deepEqual(palette, sidebar, 'palette destinations equal the sidebar destinations, in order');
    assert.ok(await page.evaluate(() => [...document.querySelectorAll('.cmdk-option[data-kind=action]')].some((o) => /Reload current page/.test(o.textContent))));
    await key(page, 'Escape');
    assert.equal(await page.evaluate(() => document.activeElement === document.querySelector('#adminNav a[data-route]')), true, 'focus returned to the sidebar link');
    assert.deepEqual(state.errors, []);
    await context.close();
  }),
  test('admin.html: "ban" + Enter goes to Banned emails via the router; Back/Forward work; page title and breadcrumb follow', async () => {
    const { page, state, context } = await newAdminPage();
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    await key(page, 'Control+k'); await page.keyboard.type('ban'); await key(page, 'Enter');
    await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Banned emails');
    assert.equal(await hash(page), '#/people/banned-emails');
    assert.equal(await page.locator('#adminPageTitle').evaluate((n) => document.activeElement === n), true, 'the router moves focus to the new page heading');
    assert.equal(await page.evaluate(() => document.querySelector('#adminNav a[aria-current=page]').dataset.route), '/people/banned-emails');
    await page.goBack(); await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Overview');
    await page.goForward(); await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Banned emails');
    assert.deepEqual(state.errors, []);
    await context.close();
  }),
  test('admin.html: palette search itself makes no API requests', async () => {
    const { page, state, context } = await newAdminPage();
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    await page.waitForTimeout(400);
    const before = state.requests.length;
    await key(page, 'Control+k'); await page.keyboard.type('users'); await page.keyboard.type(' zzz'); await key(page, 'Escape');
    assert.equal(state.requests.length, before);
    await context.close();
  }),
  test('admin.html: "user 7" opens the user-detail route; palette closes; no page errors', async () => {
    const { page, state, context } = await newAdminPage();
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    await key(page, 'Control+k'); await page.keyboard.type('user 7'); await key(page, 'Enter');
    await page.waitForFunction(() => location.hash === '#/people/users/7');
    assert.equal(await visible(page), false);
    await page.waitForTimeout(500);
    assert.deepEqual(state.errors, []);
    await context.close();
  }),
  test('admin.html: "usage limits" opens the legacy limits dialog route, and the palette then refuses to open over it', async () => {
    const { page, state, context } = await newAdminPage();
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    await key(page, 'Control+k'); await page.keyboard.type('usage'); await key(page, 'Enter');
    await page.waitForFunction(() => location.hash === '#/system/usage-limits');
    await page.waitForFunction(() => getComputedStyle(document.getElementById('modal')).display !== 'none', null, { timeout: 5000 });
    await key(page, 'Control+k');
    assert.equal(await visible(page), false, 'legacy modal open -> shortcut is left alone');
    assert.deepEqual(state.errors, []);
    await context.close();
  }),
  test('admin.html: Reload current page action re-renders the current route', async () => {
    const { page, state, context } = await newAdminPage();
    await page.goto(base + '/admin.html#/people/banned-emails'); await adminReady(page); await paletteAlive(page);
    await page.evaluate(() => { window.__marker = document.getElementById('adminContent').firstElementChild; });
    await key(page, 'Control+k'); await page.keyboard.type('reload'); await key(page, 'Enter');
    await page.waitForFunction(() => window.__marker && !window.__marker.isConnected, null, { timeout: 5000 });
    assert.equal(await hash(page), '#/people/banned-emails');
    assert.deepEqual(state.errors, []);
    await context.close();
  }),
  test('admin.html: Escape with the palette open does not also close the mobile navigation drawer', async () => {
    const { page, state, context } = await newAdminPage({ viewport: { width: 700, height: 800 } });
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    await page.evaluate(() => document.getElementById('adminNavToggle').click());
    assert.equal(await page.evaluate(() => document.body.classList.contains('admin-nav-open')), true);
    await page.evaluate(() => document.getElementById('adminSearchTrigger').focus());
    await key(page, 'Control+k');
    assert.equal(await visible(page), true);
    await key(page, 'Escape');
    assert.equal(await visible(page), false);
    assert.equal(await page.evaluate(() => document.body.classList.contains('admin-nav-open')), true, 'the drawer is untouched by the palette Escape');
    assert.deepEqual(state.errors, []);
    await context.close();
  }),
  test('admin.html (narrow): trigger is an icon button with an accessible name and the palette fits the viewport', async () => {
    const { page, context } = await newAdminPage({ viewport: { width: 380, height: 700 } });
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    const s = await page.evaluate(() => { const b = document.getElementById('adminSearchTrigger'); const r = b.getBoundingClientRect(); return { w: r.width, label: getComputedStyle(b.querySelector('.shell-search-label')).display, name: b.textContent.includes('Search') }; });
    assert.equal(s.label, 'none'); assert.ok(s.w >= 40 && s.w <= 48);
    await page.locator('#adminSearchTrigger').click();
    const box = await page.locator('.cmdk-panel').boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 380, 'panel inside viewport');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= 380), true, 'no horizontal page scroll');
    await context.close();
  }),
  test('admin.html: macOS platform -> Cmd+K opens, Ctrl+K does not, hint is ⌘K', async () => {
    const { page, state, context } = await newAdminPage({ platform: 'MacIntel' });
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    assert.equal(await page.locator('[data-cmdk-hint]').textContent(), '⌘K');
    await key(page, 'Control+k'); assert.equal(await visible(page), false);
    await key(page, 'Meta+k'); assert.equal(await visible(page), true);
    assert.deepEqual(state.errors, []);
    await context.close();
  }),
  test('admin.html: a non-admin gets no search button and Ctrl+K does nothing', async () => {
    const { page, context } = await newAdminPage({ me: { name: 'Student', isAdmin: false } });
    await page.goto(base + '/admin.html#/overview');
    await page.waitForFunction(() => /Admin access required/.test(document.getElementById('adminContent').innerText));
    await key(page, 'Control+k');
    assert.equal(await page.evaluate(() => document.getElementById('adminSearchTrigger').hidden), true);
    assert.equal(await page.evaluate(() => document.querySelectorAll('[data-cmdk-root]').length), 0);
    await context.close();
  }),
  test('admin.html: printing hides the palette and its trigger', async () => {
    const { page, context } = await newAdminPage();
    await page.goto(base + '/admin.html#/overview'); await adminReady(page); await paletteAlive(page);
    await key(page, 'Control+k');
    await page.emulateMedia({ media: 'print' });
    assert.deepEqual(await page.evaluate(() => [getComputedStyle(document.querySelector('[data-cmdk-root]')).display, getComputedStyle(document.getElementById('adminSearchTrigger')).display]), ['none', 'none']);
    await context.close();
  }),
  test('admin.html: a failed palette module load leaves the shell working and the button hidden', async () => {
    const { page, state, context } = await newAdminPage();
    await page.route('**/admin/search/command-palette.js', (r) => r.fulfill({ status: 500, body: 'nope' }));
    await page.goto(base + '/admin.html#/people/banned-emails'); await adminReady(page);
    await page.waitForTimeout(500);
    assert.equal(await page.evaluate(() => document.getElementById('adminPageTitle').textContent), 'Banned emails');
    assert.equal(await page.evaluate(() => document.getElementById('adminSearchTrigger').hidden), true);
    await page.locator('#adminNav a[data-route="/people/users"]').click();
    await page.waitForFunction(() => document.getElementById('adminPageTitle').textContent === 'Users');
    assert.ok(state.errors.every((e) => /Admin search failed to initialise|command-palette|Failed to fetch dynamically imported module|500/i.test(e)), state.errors.join('\n'));
    await context.close();
  })
];
const ctxB = {};
await run(B, ctxB);

await browser.close();
server.close();
let failed = 0;
for (const [pass, name, error] of results) {
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}`);
  if (!pass) { failed += 1; console.log('      ' + String(error && (error.stack || error.message || error)).split('\n').slice(0, 6).join('\n      ')); }
}
console.log(`\n${results.length - failed}/${results.length} browser checks passed (Chromium only; mocked API; no Spring backend; no authenticated session).`);
process.exit(failed ? 1 : 0);
