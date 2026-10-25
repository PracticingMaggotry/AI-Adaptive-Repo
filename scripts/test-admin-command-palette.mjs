/**
 * Pure (Node-only) tests for the admin command palette: route matching, aliases/keywords, dynamic
 * parameterised destinations, highlight ranges, and the keyboard helpers.
 * Run: node scripts/test-admin-command-palette.mjs
 *
 * These use the REAL route registry (admin/routes.js) and router (admin/router.js) as input, so a registry
 * change that breaks search shows up here. Keyboard/focus/DOM behavior is covered by
 * scripts/smoke-admin-command-palette.mjs (needs a browser).
 */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import routes, { sections, aliases } from '../src/main/resources/static/admin/routes.js';
import { createRouter, resolveHash } from '../src/main/resources/static/admin/router.js';
import { buildEntries, searchEntries, computeResults, resolveDynamicEntries, highlightRanges, normalize, compact, words } from '../src/main/resources/static/admin/search/search-model.js';
import { isShortcutEvent, isEditableTarget, isComposingEvent, isMacPlatform } from '../src/main/resources/static/admin/search/palette-keys.js';

let checks = 0;
const ok = (v, m) => { checks += 1; assert.ok(v, m); };
const eq = (a, b, m) => { checks += 1; assert.deepEqual(a, b, m); };

const entries = buildEntries({ routes, sections, aliases });
const resolve = (hash) => resolveHash(hash, { routes, aliases });
const top = (q, n = 1) => searchEntries(entries, q).slice(0, n).map((r) => r.entry.path);
const all = (q) => searchEntries(entries, q).map((r) => r.entry.path);

// ── 1. Derived from the registry, not a second list ──────────────────────────────────────────
const staticRoutes = routes.filter((r) => !r.path.includes(':'));
eq(entries.map((e) => e.path), staticRoutes.map((r) => r.path), 'one entry per static registry route, in registry order');
ok(!entries.some((e) => e.path.includes(':')), 'parameterised routes are not listed as static entries');
for (const wanted of ['/overview', '/people/users', '/people/deletion-requests', '/people/banned-emails', '/content/materials', '/content/reported-questions', '/content/categories',
  '/study/participants', '/study/assigned-handouts', '/study/data-exports', '/system/usage-limits', '/system/configuration', '/system/ai-testing', '/system/reliability', '/audit/activity-log', '/audit/platform-report']) {
  ok(entries.some((e) => e.path === wanted), `destination present: ${wanted}`);
}
eq(buildEntries({ routes: [], sections, aliases }), [], 'empty registry -> no entries');
eq(buildEntries({}), [], 'missing options do not throw');
const extra = buildEntries({ routes: [...routes, { path: '/system/new-page', section: 'system', title: 'New page', status: 'ready', keywords: ['gizmo'], load() {} }], sections, aliases });
ok(extra.some((e) => e.path === '/system/new-page'), 'a route added to the registry appears without touching the palette');
eq(searchEntries(extra, 'gizmo').map((r) => r.entry.path), ['/system/new-page'], 'optional route.keywords are searchable');

// ── 2. Matching ──────────────────────────────────────────────────────────────────────────────
eq(top('users'), ['/people/users'], '"users" -> Users first');
eq(top('overview'), ['/overview']);
eq(top('banned'), ['/people/banned-emails']);
eq(top('banned emails'), ['/people/banned-emails']);
eq(top('bannedemails'), ['/people/banned-emails'], 'compact form (old hash alias) matches');
eq(top('deletion'), ['/people/deletion-requests']);
eq(top('deletionrequests'), ['/people/deletion-requests'], 'alias deletionrequests');
ok(['/content/reported-questions', '/audit/platform-report'].includes(top('report')[0]), '"report" ranks a report page first');
ok(all('report').includes('/audit/platform-report') && all('report').includes('/content/reported-questions'), '"report" finds both report pages');
eq(top('platform report'), ['/audit/platform-report']);
eq(top('activity'), ['/audit/activity-log']);
eq(top('ai'), ['/system/ai-testing']);
eq(top('handouts'), ['/study/assigned-handouts']);
eq(top('exports'), ['/study/data-exports']);
eq(top('participants'), ['/study/participants']);
eq(top('categories'), ['/content/categories']);
eq(top('limits'), ['/system/usage-limits']);
eq(top('config'), ['/system/configuration']);
eq(top('reliab'), ['/system/reliability']);
eq(top('USERS'), ['/people/users'], 'case-insensitive');
eq(top('  users  '), ['/people/users'], 'surrounding whitespace ignored');
eq(top('people/users'), ['/people/users'], 'typed path segments match');
eq(top('#/people/banned-emails'), ['/people/banned-emails'], 'a pasted hash route matches');
eq(top('/study/data-exports'), ['/study/data-exports']);
eq(all('people').sort(), entries.filter((e) => e.path.startsWith('/people/')).map((e) => e.path).sort(), 'section name lists that section');
eq(all('study').sort(), entries.filter((e) => e.path.startsWith('/study/')).map((e) => e.path).sort(), 'section "study" lists exactly the three Study pages');
eq(all('zzzz-nothing'), [], 'no match -> empty');
eq(all('users zzz'), [], 'every token must match (AND)');
eq(searchEntries(entries, '').map((r) => r.entry.path), entries.map((e) => e.path), 'empty query lists everything in registry order');
eq(searchEntries(entries, '   ').length, entries.length, 'blank query is the empty query');
eq(searchEntries(entries, '!!!').length, entries.length, 'punctuation-only query has no tokens -> empty-query behaviour');
eq(searchEntries(entries, '', { limit: 3 }).length, 3, 'limit applies');
{ const scores = searchEntries(entries, 'report').map((r) => r.score); ok(scores.length >= 2 && scores.every((v, i) => i === 0 || scores[i - 1] >= v), 'results are ordered by descending score'); }
ok(normalize('Café') === 'cafe' && compact('Banned e-mails') === 'bannede' + 'mails' && words('a-b/c').join() === 'a,b,c', 'normalisation helpers');

// ── 3. Aliases come from the registry ────────────────────────────────────────────────────────
for (const [key, target] of Object.entries(aliases)) {
  const hit = all(key);
  ok(hit.includes(target), `old hash alias "${key}" finds ${target}`);
  eq(hit[0], target, `old hash alias "${key}" ranks ${target} first`);
}
const reportsEntry = entries.find((e) => e.path === '/audit/platform-report');
ok(reportsEntry.aliasKeys.includes('reports'), 'alias "reports" is attached to Platform report');

// ── 4. Status tags mirror the registry ───────────────────────────────────────────────────────
const byPath = Object.fromEntries(entries.map((e) => [e.path, e]));
eq(byPath['/system/configuration'].tags, [], 'implemented Configuration has no status tag');
ok(byPath['/system/reliability'].tags.includes('Partial'), 'partial route is tagged');
ok(byPath['/system/usage-limits'].tags.includes('Opens dialog'), 'route that opens a modal says so');
eq(byPath['/people/users'].tags, [], 'ready route has no tag');
eq(byPath['/people/users'].section, 'People');
eq(byPath['/people/users'].hash, '#/people/users');

// ── 5. Parameterised destination (user detail) ───────────────────────────────────────────────
const dyn = (q) => resolveDynamicEntries(q, { routes, sections, resolve }).map((e) => e.path);
eq(dyn('user 12'), ['/people/users/12'], '"user 12"');
eq(dyn('users 12'), ['/people/users/12'], '"users 12"');
eq(dyn('user detail 12'), ['/people/users/12'], '"user detail 12"');
eq(dyn('user #12'), ['/people/users/12'], '"user #12"');
eq(dyn('user id 12'), ['/people/users/12'], '"user id 12"');
eq(dyn('/people/users/12'), ['/people/users/12'], 'typed canonical path');
eq(dyn('#/people/users/12'), ['/people/users/12'], 'typed hash route');
eq(dyn('people/users/12'), ['/people/users/12'], 'typed path without leading slash');
eq(dyn('/PEOPLE/Users/abc-9'), ['/people/users/abc-9'], 'path is canonicalised by the router');
eq(dyn('user a.b_c~d-1'), ['/people/users/a.b_c~d-1'], 'router-legal characters accepted');
eq(dyn('users'), [], 'no value -> no dynamic entry');
eq(dyn('12'), [], 'a bare number is not guessed to be a user id');
eq(dyn('banned 12'), [], 'unrelated words + value -> nothing');
eq(dyn('user <img src=x onerror=alert(1)>'), [], 'hostile parameter rejected by the router');
eq(dyn('user a/b'), [], 'slash in value rejected');
eq(dyn('user ' + 'x'.repeat(65)), [], 'over-long value rejected');
eq(dyn('/people/users/12/extra'), [], 'extra segment is not a match');
eq(dyn('/people/users'), [], 'static path is not a dynamic entry');
eq(dyn('user %zz'), [], 'malformed percent escape rejected');
eq(resolveDynamicEntries('user 12', { routes, sections }), [], 'without resolve() nothing is offered');
eq(resolveDynamicEntries('user 12', { routes, sections, resolve: () => { throw new Error('boom'); } }), [], 'a throwing resolve() is contained');
const d12 = resolveDynamicEntries('user 12', { routes, sections, resolve })[0];
eq([d12.kind, d12.title, d12.section, d12.hash], ['dynamic', 'User detail 12', 'People', '#/people/users/12']);
ok(/not looked up until the page loads/.test(d12.description), 'dynamic entry says the record is not verified');
eq(computeResults(entries, 'user 12', { routes, sections, resolve }).map((r) => r.entry.path), ['/people/users/12'], 'user 12 -> only the detail entry (static Users needs "12" too)');
eq(computeResults(entries, 'users', { routes, sections, resolve })[0].entry.path, '/people/users', 'plain "users" still lists Users first');

// ── 6. Actions ───────────────────────────────────────────────────────────────────────────────
let ran = 0;
const withActions = buildEntries({ routes, sections, aliases, actions: [
  { id: 'reload', title: 'Reload current page', keywords: ['refresh'], run: () => { ran += 1; } },
  { title: 'broken: no run' }, { run() {} }, null, { title: '   ', run() {} }
] });
eq(withActions.filter((e) => e.kind === 'action').map((e) => e.title), ['Reload current page'], 'invalid actions are dropped');
eq(searchEntries(withActions, 'refresh').map((r) => r.entry.kind), ['action'], 'action keywords are searchable');
eq(searchEntries(withActions, 'action')[0].entry.kind, 'action', '"action" lists actions');
searchEntries(withActions, 'reload')[0].entry.run();
eq(ran, 1, 'action run() is the host function');

// ── 7. Hostile text stays text (the model never builds HTML) ─────────────────────────────────
const hostile = '<img src=x onerror=window.__pwned=1>';
const hostileEntries = buildEntries({ routes: [{ path: '/system/x', section: 'system', title: hostile, status: 'ready', load() {} }], sections, aliases: {} });
eq(hostileEntries[0].title, hostile, 'title kept verbatim for textContent rendering');
eq(searchEntries(hostileEntries, 'img').map((r) => r.entry.title), [hostile], 'hostile titles are matched as plain text');
eq(searchEntries(entries, hostile), [], 'a hostile query matches nothing and does not throw');
eq(searchEntries(entries, '"><script>alert(1)</script>'), [], 'script-ish query is inert');
for (const q of ['(', ')', '[', '\\', '.*', '$^', '%', '\u0000', '😀', 'ü', 'İ']) searchEntries(entries, q);
ok(true, 'regex metacharacters and odd Unicode do not throw');

// ── 8. highlight ranges ──────────────────────────────────────────────────────────────────────
eq(highlightRanges('Banned emails', ['ban']), [[0, 3]]);
eq(highlightRanges('Banned emails', ['ban', 'mail']), [[0, 3], [8, 12]]);
eq(highlightRanges('Banned', ['ban', 'bann']), [[0, 4]], 'overlapping ranges merge');
eq(highlightRanges('Users', []), []);
eq(highlightRanges('İstanbul', ['stan']), [], 'length-changing lower-casing -> no highlight rather than wrong indices');

// ── 9. Keyboard helpers ──────────────────────────────────────────────────────────────────────
const ev = (o) => ({ key: 'k', ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...o });
ok(isShortcutEvent(ev({ ctrlKey: true }), { mac: false }), 'Ctrl+K on Windows/Linux');
ok(isShortcutEvent(ev({ ctrlKey: true, key: 'K' }), { mac: false }), 'Ctrl+K with caps lock');
ok(!isShortcutEvent(ev({ metaKey: true }), { mac: false }), 'Cmd+K is not the shortcut on Windows/Linux');
ok(isShortcutEvent(ev({ metaKey: true }), { mac: true }), 'Cmd+K on macOS');
ok(!isShortcutEvent(ev({ ctrlKey: true }), { mac: true }), 'Ctrl+K on macOS is left to text fields (kill-line)');
ok(!isShortcutEvent(ev({ ctrlKey: true, metaKey: true }), { mac: false }) && !isShortcutEvent(ev({ ctrlKey: true, metaKey: true }), { mac: true }), 'Ctrl+Cmd+K ignored');
ok(!isShortcutEvent(ev({ ctrlKey: true, shiftKey: true }), { mac: false }), 'Ctrl+Shift+K ignored');
ok(!isShortcutEvent(ev({ ctrlKey: true, altKey: true }), { mac: false }), 'Ctrl+Alt+K (AltGr) ignored');
ok(!isShortcutEvent(ev({ ctrlKey: true, key: 'j' }), { mac: false }), 'other letters ignored');
ok(!isShortcutEvent(ev({ key: 'k' }), { mac: false }), 'plain k ignored');
ok(isShortcutEvent(ev({ ctrlKey: true, key: 'л', code: 'KeyK' }), { mac: false }), 'non-Latin layout: physical K key');
ok(!isShortcutEvent(ev({ ctrlKey: true, key: 't', code: 'KeyK' }), { mac: false }), 'Dvorak Ctrl+T (code KeyK) is not hijacked');
ok(!isShortcutEvent(null), 'null event');
ok(isComposingEvent({ isComposing: true }) && isComposingEvent({ keyCode: 229 }) && !isComposingEvent({ key: 'a', keyCode: 65 }), 'IME composition detection');
ok(isMacPlatform({ platform: 'MacIntel' }) && isMacPlatform({ userAgentData: { platform: 'macOS' } }) && isMacPlatform({ platform: 'iPhone' }), 'mac detection');
ok(!isMacPlatform({ platform: 'Win32' }) && !isMacPlatform({ platform: 'Linux x86_64' }) && !isMacPlatform(null), 'non-mac detection');
const node = (o) => ({ nodeType: 1, tagName: 'DIV', closest: () => null, ...o });
ok(isEditableTarget(node({ tagName: 'INPUT', type: 'text' })), 'text input is editable');
ok(isEditableTarget(node({ tagName: 'INPUT', type: 'search' })) && isEditableTarget(node({ tagName: 'INPUT' })), 'search / typeless input');
ok(isEditableTarget(node({ tagName: 'TEXTAREA' })), 'textarea');
ok(isEditableTarget(node({ isContentEditable: true })), 'contenteditable');
ok(isEditableTarget(node({ closest: () => ({}) })), 'inside an embedded editor');
ok(!isEditableTarget(node({ tagName: 'INPUT', type: 'checkbox' })) && !isEditableTarget(node({ tagName: 'INPUT', type: 'button' })), 'non-text inputs are not editable');
ok(!isEditableTarget(node({ tagName: 'INPUT', type: 'text', readOnly: true })) && !isEditableTarget(node({ tagName: 'INPUT', type: 'text', disabled: true })), 'read-only / disabled inputs are not editable');
ok(!isEditableTarget(node({ tagName: 'BUTTON' })) && !isEditableTarget(node({ tagName: 'SELECT' })) && !isEditableTarget(null) && !isEditableTarget({ nodeType: 3 }), 'buttons, selects, null, text nodes');

// ── 10. Static checks on the wiring ──────────────────────────────────────────────────────────
const staticRoot = new URL('../src/main/resources/static/', import.meta.url);
const read = (p) => readFile(new URL(p, staticRoot), 'utf8');
const [adminJs, adminHtml, modelSrc, paletteSrc, cssSrc] = await Promise.all([read('admin.js'), read('admin.html'), read('admin/search/search-model.js'), read('admin/search/command-palette.js'), read('admin/search/command-palette.css')]);
ok(!/\.innerHTML\s*=|insertAdjacentHTML|outerHTML|document\.write|\beval\(|new Function/.test(paletteSrc + modelSrc), 'palette modules never assign or parse HTML');
ok(/import\("\.\/admin\/search\/command-palette\.js"\)/.test(adminJs) && /initCommandPalette\(/.test(adminJs), 'admin.js loads and initialises the palette');
ok(/initAdminCommandPalette\(routesModule\);/.test(adminJs.replace(/\s+/g, ' ')) , 'palette is initialised after the router starts');
ok(/navigate: \(target\) => adminRouter && adminRouter\.navigate\(target\)/.test(adminJs), 'selection goes through the router');
ok(!/addEventListener\("keydown"[^)]*k/i.test(adminJs.slice(adminJs.indexOf('initAdminCommandPalette'), adminJs.indexOf('async function startAdminShell'))), 'admin.js adds no keyboard listener of its own for the palette');
ok(/href="admin\/search\/command-palette\.css"/.test(adminHtml), 'admin.html loads the palette stylesheet');
ok(/id="adminSearchTrigger"[^>]*\bhidden\b/.test(adminHtml), 'trigger button is hidden until the palette is alive');
ok(!/["'`]\/(people|content|study|system|audit)\/[a-z-]+["'`]/.test(modelSrc.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')) , 'search-model.js contains no hard-coded route path');
ok(!/["'`]\/(people|content|study|system|audit)\/[a-z-]+["'`]/.test(paletteSrc.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')), 'command-palette.js contains no hard-coded route path');
ok(!/fetch\(|XMLHttpRequest|apiFetch|sendBeacon/.test(modelSrc + paletteSrc), 'palette makes no network request');
ok(/@media print[\s\S]*cmdk-root/.test(cssSrc), 'palette is hidden in print');

// ── 11. The router actually accepts what the palette navigates to ────────────────────────────
for (const e of entries.filter((x) => x.kind === 'route')) {
  const r = resolve(e.path);
  ok(r.kind === 'ok' && r.path === e.path && !r.rewritten, `router resolves ${e.path} canonically`);
}
const hashes = [];
const router = createRouter({ routes, aliases, mount: { replaceChildren() {} }, env: { hash: '', setHash(v) { hashes.push(v); }, replaceHash() {}, onHashChange: () => () => {} } });
router.navigate('/people/users/12');
router.navigate('/people/banned-emails');
eq(hashes, ['#/people/users/12', '#/people/banned-emails'], 'navigate() writes canonical hashes via setHash (history entries, Back/Forward)');

console.log(`Admin command palette model/key tests passed (${checks} checks). Browser behavior: see scripts/smoke-admin-command-palette.mjs.`);
