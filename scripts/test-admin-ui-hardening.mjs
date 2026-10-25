// Browser regression test for admin/ui.js table hardening.
// Run: node --experimental-default-type=module scripts/test-admin-ui-hardening.mjs
// Needs a real browser engine (dialogs, focus, layout). It uses `playwright` or `puppeteer` if resolvable
// (also looked up in NODE_PATH entries). Set CHROME_PATH to a Chromium/Chrome binary if it is not auto-found.
// If neither package is available the script exits with code 2 (NOT a pass).
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src/main/resources/static');

async function loadDriver() {
  for (const name of ['playwright', 'puppeteer']) {
    try { return { name, mod: await import(name) }; } catch {}
    for (const dir of (process.env.NODE_PATH || '').split(path.delimiter).filter(Boolean)) {
      try { return { name, mod: createRequire(path.join(dir, 'x.js'))(name) }; } catch {}
    }
  }
  return null;
}
const driver = await loadDriver();
if (!driver) { console.error('SKIPPED (exit 2): neither playwright nor puppeteer could be loaded.'); process.exit(2); }

const types = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/__test.html') { res.setHeader('content-type', 'text/html'); return res.end('<!doctype html><meta charset="utf-8"><body>'); }
  const file = path.join(root, path.normalize(url.pathname));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.statusCode = 404; return res.end(); }
  res.setHeader('content-type', types[path.extname(file)] || 'application/octet-stream'); res.end(fs.readFileSync(file));
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const launchOpts = { headless: true, args: ['--no-sandbox'] };
if (process.env.CHROME_PATH) launchOpts[driver.name === 'playwright' ? 'executablePath' : 'executablePath'] = process.env.CHROME_PATH;
const pwChromium = driver.mod.chromium || driver.mod.default?.chromium;
const browser = await (driver.name === 'playwright' ? pwChromium : (driver.mod.default || driver.mod)).launch(launchOpts);
const page = await browser.newPage();
const pageErrors = [];
page.on('pageerror', e => pageErrors.push(String(e)));
await page.goto(`${base}/__test.html`);

const result = await page.evaluate(async () => {
  const { default: ui } = await import('/admin/ui.js');
  await new Promise(r => setTimeout(r, 300)); // let the component stylesheet load
  const out = { clicks: [], controls: [] };
  const mount = (opts, width) => {
    const host = document.createElement('div'); host.style.width = width || '600px'; document.body.append(host);
    const t = ui.table(opts); host.append(t.element); return { t, host };
  };
  const cols = [
    { key: 'name', label: 'Name' },
    { key: 'act', label: 'Action', render: row => {
      const wrap = document.createElement('span');
      const b = document.createElement('button'); b.type = 'button'; b.textContent = 'Do'; b.addEventListener('click', () => out.controls.push('button:' + row.id));
      const a = document.createElement('a'); a.href = '#x'; a.textContent = 'Link'; a.addEventListener('click', e => { e.preventDefault(); out.controls.push('link:' + row.id); });
      const inner = document.createElement('b'); inner.textContent = 'icon'; b.append(inner);
      wrap.append(b, a); return wrap;
    } }
  ];
  const fetchRows = async () => [{ id: 1, name: 'Ada' }];

  // 1) Row click handling
  const { t, host } = mount({ columns: cols, fetch: fetchRows, selectable: true, onRowClick: r => out.clicks.push(r.id) });
  await t.reload();
  const tr = t.element.querySelector('tbody tr');
  const btn = tr.querySelector('button'), link = tr.querySelector('a'), cb = tr.querySelector('input[type=checkbox]');
  btn.querySelector('b').click();                 // click on an element nested inside a button
  btn.click(); link.click(); cb.click();
  out.afterControls = { clicks: [...out.clicks], controls: [...out.controls], selected: t.getSelected() };
  tr.querySelector('td:nth-child(2)').click();    // plain cell click still activates the row
  out.afterCell = [...out.clicks];
  tr.focus(); tr.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  out.afterRowEnter = [...out.clicks];
  btn.focus(); btn.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); btn.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
  out.afterButtonKeys = [...out.clicks];
  // Row inside a scroll region that has tabindex=0 must still be clickable (ancestor must not count as a control).
  t.element.tabIndex = 0; tr.querySelector('td:nth-child(2)').click(); out.ancestorTabindexClicks = [...out.clicks];
  t.destroy(); host.remove();

  // 2) Scrollable region accessibility
  const wide = [{ key: 'name', label: 'Name' }, { key: 'x', label: 'Wide', render: () => { const s = document.createElement('div'); s.style.width = '1200px'; s.textContent = 'w'; return s; } }];
  const a = mount({ columns: wide, fetch: fetchRows, label: 'Users' }, '300px'); await a.t.reload();
  out.scroll = { tabindex: a.t.element.getAttribute('tabindex'), role: a.t.element.getAttribute('role'), name: a.t.element.getAttribute('aria-label') };
  a.t.element.focus(); out.scrollFocusable = document.activeElement === a.t.element;
  const d = mount({ columns: wide, fetch: fetchRows }, '300px'); await d.t.reload();
  out.defaultName = d.t.element.getAttribute('aria-label');
  const n = mount({ columns: [{ key: 'name', label: 'Name' }], fetch: fetchRows }, '600px'); await n.t.reload();
  out.noScroll = { tabindex: n.t.element.getAttribute('tabindex'), role: n.t.element.getAttribute('role'), name: n.t.element.getAttribute('aria-label') };
  // Shrinking the container after load adds the region semantics (ResizeObserver).
  n.host.style.width = '40px'; n.t.element.querySelector('table').style.minWidth = '400px';
  await new Promise(r => setTimeout(r, 200));
  out.afterShrink = n.t.element.getAttribute('tabindex');
  return out;
});

await browser.close(); server.close();

assert.deepEqual(result.afterControls.clicks, [], 'controls inside a row must not trigger onRowClick');
assert.deepEqual(result.afterControls.controls, ['button:1', 'button:1', 'link:1'], 'controls keep their own behaviour');
assert.deepEqual(result.afterControls.selected, ['1'], 'row checkbox still selects');
assert.deepEqual(result.afterCell, [1], 'plain cell click still activates the row');
assert.deepEqual(result.afterRowEnter, [1, 1], 'Enter on the focused row still activates it');
assert.deepEqual(result.afterButtonKeys, [1, 1], 'Enter/Space on a button inside the row must not activate the row');
assert.deepEqual(result.ancestorTabindexClicks, [1, 1, 1], 'a focusable scroll wrapper must not block row clicks');
assert.deepEqual(result.scroll, { tabindex: '0', role: 'region', name: 'Users' }, 'overflowing table is a focusable, named region');
assert.equal(result.scrollFocusable, true, 'scroll region can receive keyboard focus');
assert.equal(result.defaultName, 'Data table', 'default accessible name');
assert.deepEqual(result.noScroll, { tabindex: null, role: null, name: null }, 'non-overflowing table adds no extra tab stop');
assert.equal(result.afterShrink, '0', 'region semantics are added when the table starts to overflow');
assert.deepEqual(pageErrors, [], 'no page errors');
console.log(`Admin UI hardening checks passed (browser driver: ${driver.name}).`);
