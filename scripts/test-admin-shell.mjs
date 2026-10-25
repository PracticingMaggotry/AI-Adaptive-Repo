/**
 * Focused tests for the admin sidebar shell and hash router (Phase 2).
 * Run: node scripts/test-admin-shell.mjs   (Node 22+; add --experimental-default-type=module on older Node)
 *
 * Covers route grouping, old-hash aliases, hash resolution, router lifecycle against a fake browser
 * (unmount-before-mount, Back/Forward, refresh on a deep link, superseded navigation, failures), and static
 * checks on admin.html / admin.js. It does NOT prove browser behavior; see scripts/smoke-admin-shell.mjs.
 */
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import routes, { sections, aliases, defaultPath } from '../src/main/resources/static/admin/routes.js';
import { createRouter, resolveHash, matchRoute, normalizePath, splitHash, buildBreadcrumb } from '../src/main/resources/static/admin/router.js';

const staticRoot = new URL('../src/main/resources/static/', import.meta.url);
const read = (rel) => readFile(new URL(rel, staticRoot), 'utf8');
let checks = 0;
const ok = (value, message) => { checks += 1; assert.ok(value, message); };
const eq = (actual, expected, message) => { checks += 1; assert.deepEqual(actual, expected, message); };

// ── 1. Route grouping ─────────────────────────────────────────────────────────────────────────
const expectedGroups = {
  overview: ['Overview'],
  people: ['Users', 'Deletion requests', 'Banned emails'],
  content: ['Materials', 'Reported questions', 'Categories'],
  study: ['Participants', 'Assigned handouts', 'Data exports'],
  system: ['Usage limits', 'Configuration', 'AI testing', 'Reliability'],
  audit: ['Activity log', 'Platform report']
};
eq(sections.map((s) => s.id), Object.keys(expectedGroups), 'six sections in the required order');
eq(sections.map((s) => s.label), ['Overview', 'People', 'Content', 'Study', 'System', 'Audit']);
for (const [section, labels] of Object.entries(expectedGroups)) {
  const navRoutes = routes.filter((r) => r.section === section && r.nav !== false);
  eq(navRoutes.map((r) => r.label || r.title), labels, `${section} group lists the required pages in order`);
}
ok(routes.every((r) => sections.some((s) => s.id === r.section)), 'every route belongs to a known section');
ok(routes.every((r) => r.path.startsWith('/' + (r.section === 'overview' ? 'overview' : r.section))), 'path prefix matches section');
ok(routes.every((r) => r.path === r.path.toLowerCase() && !r.path.includes('#') && !r.path.startsWith('/admin/')), 'paths are lower-case internal hash paths without "#" or /admin/');
eq(new Set(routes.map((r) => r.path)).size, routes.length, 'no duplicate paths');
eq(routes.filter((r) => r.nav === false).map((r) => r.path), ['/people/users/:id'], 'only user detail is outside the sidebar');

// Route status flags distinguish implemented views from partial/export-only views.
const byPath = Object.fromEntries(routes.map((r) => [r.path, r]));
eq(byPath['/system/configuration'].status, 'ready', 'Configuration has a real view module');
eq(byPath['/system/reliability'].status, 'partial');
eq(byPath['/people/users/:id'].status, 'ready');
ok(byPath['/people/users/:id'].shellLoad && byPath['/people/users/:id'].shellLoad !== byPath['/people/users/:id'].load, 'user-detail URL mounts the Users list adapter with the detail route context');
ok(!byPath['/people/users/:id'].after?.notice, 'no stale unavailable-endpoint notice remains after the detail drawer implementation');
ok(/export-only|only reliability feature/i.test(byPath['/system/reliability'].after.notice.text), 'reliability states it is export-only');
eq(byPath['/content/materials'].after, { subTab: 'materials' });
eq(byPath['/content/categories'].after, { subTab: 'categories' });
eq(byPath['/study/participants'].status, 'ready');
ok(!byPath['/study/participants'].legacyTab && !byPath['/study/participants'].after?.focus, 'Participants is a standalone Study view');
eq(typeof (await byPath['/study/participants'].load()).default.mount, 'function', 'Participants loads its own view module');
eq(byPath['/study/data-exports'].status, 'ready');
ok(!byPath['/study/data-exports'].legacyTab && !byPath['/study/data-exports'].after?.focus, 'Data exports is a standalone Study view');
eq(typeof (await byPath['/study/data-exports'].load()).default.mount, 'function', 'Data exports loads its own view module');
eq(byPath['/audit/platform-report'].after.focus, '#printTarget');
eq(byPath['/system/usage-limits'].after.modal, 'openDailyLimitsModal');
eq(byPath['/system/usage-limits'].legacyTab, 'users');
const configModule = (await byPath['/system/configuration'].load()).default;
eq([typeof configModule.mount, typeof configModule.unmount], ['function', 'function'], 'configuration view honors mount/unmount');

// ── 2. Old-hash aliases ───────────────────────────────────────────────────────────────────────
const brief = {
  overview: '/overview', users: '/people/users', topics: '/content/materials', bannedemails: '/people/banned-emails',
  deletionrequests: '/people/deletion-requests', reports: '/audit/platform-report', activitylog: '/audit/activity-log',
  aitesting: '/system/ai-testing', reportedquestions: '/content/reported-questions', assignedhandouts: '/study/assigned-handouts'
};
eq({ ...aliases }, brief, 'alias table matches the ten old hashes');
const ctxOpts = { routes, aliases, defaultPath };
for (const [old, path] of Object.entries(brief)) {
  const r = resolveHash('#' + old, ctxOpts);
  eq([r.kind, r.path, r.viaAlias, r.rewritten, r.canonicalHash], ['ok', path, true, true, '#' + path], `#${old} -> #${path}`);
}
eq(resolveHash('#USERS/', ctxOpts).path, '/people/users', 'aliases ignore case and trailing slash');
eq(resolveHash('#users?x=1', ctxOpts).canonicalHash, '#/people/users?x=1', 'alias keeps query string');
for (const key of ['constructor', '__proto__', 'toString', 'hasOwnProperty']) eq(resolveHash('#' + key, ctxOpts).kind, 'notfound', `${key} is not an alias`);

// Every in-repo producer of an old hash must resolve: switchTab('x') calls and admin.html#x quick-action links.
async function files(dir, ext) {
  const out = [];
  for (const entry of await readdir(new URL(dir, staticRoot), { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith(ext)) out.push(dir + entry.name);
  }
  return out;
}
const producers = [...await files('admin/legacy/', '.js'), 'admin.js', 'admindashboard.html'];
const oldHashes = new Set();
for (const file of producers) {
  const text = await read(file);
  for (const m of text.matchAll(/switchTab\(\s*['"]([a-z]+)['"]\s*\)/g)) oldHashes.add(m[1]);
  for (const m of text.matchAll(/admin\.html#([A-Za-z0-9_\/-]+)/g)) oldHashes.add(m[1]);
}
ok(oldHashes.size >= 5, `expected several existing quick actions, found ${[...oldHashes]}`);
for (const hash of oldHashes) eq(resolveHash('#' + hash, ctxOpts).kind, 'ok', `existing quick action "${hash}" still resolves`);

// ── 3. Hash resolution ────────────────────────────────────────────────────────────────────────
const res = (h) => resolveHash(h, ctxOpts);
eq([res('').kind, res('').path, res('').rewritten], ['ok', '/overview', true], 'empty hash -> default route, rewritten in place');
eq(res('#').path, '/overview');
eq([res('#/overview').rewritten, res('#/people/users').rewritten], [false, false], 'canonical hashes are not rewritten');
eq(res('#/People/Users/').canonicalHash, '#/people/users', 'case and trailing slash normalize');
eq(res('#//people///users').path, '/people/users', 'repeated slashes collapse');
eq(res('#/people/users/42').params, { id: '42' }, 'user detail captures :id');
eq(res('#/people/users/42').route.path, '/people/users/:id');
eq(res('#/people/users/4%32').canonicalHash, '#/people/users/42', 'encoded ids normalize');
for (const bad of ['#/nope', '#/people', '#/people/users/42/extra', '#/people/users/a%2Fb', '#/people/users/%E0%A4%A', '#/people/users/..', '#/admin/users', '#nope']) {
  eq(res(bad).kind, 'notfound', `${bad} is not a route`);
  eq(res(bad).rewritten, false, `${bad} is left alone`);
}
eq(res('#/people/users/<x>').kind, 'notfound', 'ids are restricted to safe characters');
eq(res('#/overview?tab=a&b=2').query.get('b'), '2', 'query string parsed');
eq(splitHash('#/a?b=1'), { rawPath: '/a', queryString: 'b=1' });
eq(normalizePath('/a/b/'), '/a/b');
eq(normalizePath(''), '');
eq(matchRoute([{ path: '/x/:id' }, { path: '/x/new' }], '/x/new').route.path, '/x/new', 'static routes beat params');
eq(buildBreadcrumb({ route: byPath['/people/users'], params: {} }, { routes, sections }), [{ label: 'People', path: '/people/users' }, { label: 'Users' }]);
eq(buildBreadcrumb({ route: byPath['/people/users/:id'], params: { id: '42' } }, { routes, sections }),
  [{ label: 'People', path: '/people/users' }, { label: 'Users', path: '/people/users' }, { label: 'User detail 42' }]);
eq(buildBreadcrumb({ route: byPath['/overview'], params: {} }, { routes, sections }), [{ label: 'Overview' }]);

// ── 4. Router lifecycle against a fake browser ────────────────────────────────────────────────
const tick = () => new Promise((r) => setTimeout(r, 0));
const delay = (ms) => new Promise((r) => setTimeout(r, ms));
function fakeEnv(initial = '') {
  const stack = [initial]; let index = 0; const listeners = new Set();
  const calls = { push: [], replace: [] };
  const fire = () => queueMicrotask(() => listeners.forEach((l) => l()));
  return {
    calls,
    get hash() { return stack[index]; },
    setHash(v) { if (v === stack[index]) return; stack.splice(index + 1); stack.push(v); index += 1; calls.push.push(v); fire(); },
    replaceHash(v) { stack[index] = v; calls.replace.push(v); },
    onHashChange(l) { listeners.add(l); return () => listeners.delete(l); },
    back() { index -= 1; fire(); },
    forward() { index += 1; fire(); },
    listenerCount: () => listeners.size
  };
}
function fakeMount() { return { children: [], cleared: 0, replaceChildren(...n) { this.children = n; this.cleared += 1; } }; }
function harness({ initial = '', views = {}, hooks = {} } = {}) {
  const events = []; const errors = []; const contexts = []; const env = fakeEnv(initial); const el = fakeMount();
  const testRoutes = routes.map((r) => {
    const cfg = views[r.path] || {};
    const view = {
      async mount(target, ctx) { events.push('mount:' + r.path); contexts.push(ctx); if (cfg.mountDelay) await delay(cfg.mountDelay); if (cfg.failMount) throw new Error('mount failed'); target.replaceChildren(r.path); },
      async unmount() { events.push('unmount:' + r.path); if (cfg.failUnmount) throw new Error('unmount failed'); }
    };
    return { ...r, load: async () => ({ default: view }), shellLoad: undefined };
  });
  const router = createRouter({
    routes: testRoutes, aliases, defaultPath, mount: el, env,
    hooks: { getContext: () => ({ api: {}, ui: {}, session: {}, badges: {} }), onError: (e, m, phase) => errors.push(`${phase}:${e.message}`), onRouteChange: (m, i) => events.push('route:' + (m ? m.route.path : 'none') + (i.silent ? ':silent' : '')), ...hooks }
  });
  return { router, events, errors, env, el, contexts };
}
const mounts = (events) => events.filter((e) => e.startsWith('mount:') || e.startsWith('unmount:'));

{ // old hash on first load is rewritten in place and mounted once
  const h = harness({ initial: '#users' });
  await h.router.start();
  eq(h.env.calls.replace, ['#/people/users'], 'alias rewritten with replaceState');
  eq(h.env.calls.push, [], 'no history entry added for the alias');
  eq(mounts(h.events), ['mount:/people/users']);
  eq(h.el.children, ['/people/users']);
  eq(h.env.listenerCount(), 1);
  const ctx = h.contexts[0];
  eq(Object.keys(ctx).sort(), ['api', 'badges', 'params', 'query', 'router', 'session', 'ui'], 'ctx has the frozen keys');
  ok(ctx.router === h.router);
}
{ // refresh on a deep link
  const h = harness({ initial: '#/people/users/42' });
  await h.router.start();
  eq(h.contexts[0].params, { id: '42' });
  eq(h.env.calls.replace, [], 'canonical deep link is not rewritten');
}
{ // empty hash -> overview
  const h = harness({ initial: '' });
  await h.router.start();
  eq(h.env.calls.replace, ['#/overview']);
  eq(mounts(h.events), ['mount:/overview']);
}
{ // navigate, unmount-before-mount, Back/Forward, no re-render for same hash
  const h = harness({ initial: '#/overview' });
  await h.router.start();
  await h.router.navigate('/people/users'); await delay(5);
  await h.router.navigate('bannedemails'); await delay(5);   // an old alias is accepted by navigate()
  eq(mounts(h.events), ['mount:/overview', 'unmount:/overview', 'mount:/people/users', 'unmount:/people/users', 'mount:/people/banned-emails'], 'previous view unmounts before the next mounts');
  eq(h.env.calls.push, ['#/people/users', '#/people/banned-emails'], 'old alias passed to navigate() becomes a canonical hash');
  h.events.length = 0;
  await h.router.navigate('/people/banned-emails'); await delay(5);
  eq(mounts(h.events), [], 'same route: nothing happens');
  h.env.back(); await delay(10);
  eq(mounts(h.events), ['unmount:/people/banned-emails', 'mount:/people/users'], 'Back re-renders the previous route');
  h.env.forward(); await delay(10);
  eq(mounts(h.events).slice(2), ['unmount:/people/users', 'mount:/people/banned-emails'], 'Forward works');
  await h.router.reload(); await delay(5);
  eq(mounts(h.events).slice(4), ['unmount:/people/banned-emails', 'mount:/people/banned-emails'], 'reload remounts');
}
{ // pasting an old alias into the address bar while running
  const h = harness({ initial: '#/overview' });
  await h.router.start();
  h.env.setHash('#reports'); await delay(10);
  eq(h.env.calls.replace, ['#/audit/platform-report']);
  eq(mounts(h.events).slice(-1), ['mount:/audit/platform-report']);
}
{ // superseded navigation: only the latest queued target is rendered, mounts never overlap
  const h = harness({ initial: '#/overview', views: { '/overview': { mountDelay: 30 } } });
  const started = h.router.start();
  await delay(5);
  h.env.setHash('#/people/users'); await tick();
  h.env.setHash('#/content/materials');
  await started; await delay(60);
  eq(mounts(h.events), ['mount:/overview', 'unmount:/overview', 'mount:/content/materials'], 'intermediate route skipped; slow mount settles before it is unmounted');
}
{ // mount failure is reported, router keeps working, failed view is still unmounted
  const h = harness({ initial: '#/people/users', views: { '/people/users': { failMount: true } } });
  await h.router.start();
  eq(h.errors, ['mount:mount failed']);
  await h.router.navigate('/overview'); await delay(5);
  eq(mounts(h.events), ['mount:/people/users', 'unmount:/people/users', 'mount:/overview']);
}
{ // unmount failure is reported but does not block the next view
  const h = harness({ initial: '#/overview', views: { '/overview': { failUnmount: true } } });
  await h.router.start();
  await h.router.navigate('/people/users'); await delay(5);
  eq(h.errors, ['unmount:unmount failed']);
  eq(mounts(h.events).slice(-1), ['mount:/people/users']);
}
{ // not found: previous view unmounted, hook called, hash not rewritten, router recovers
  let notFound = null;
  const h = harness({ initial: '#/overview', hooks: { onNotFound: (el, r) => { notFound = r; } } });
  await h.router.start();
  h.env.setHash('#/does/not/exist'); await delay(10);
  eq(mounts(h.events), ['mount:/overview', 'unmount:/overview']);
  eq(notFound.kind, 'notfound');
  eq(h.env.calls.replace, []);
  await h.router.navigate('/overview'); await delay(5);
  eq(mounts(h.events).slice(-1), ['mount:/overview']);
}
{ // before/after hooks and silent sync (used by the Materials/Categories buttons)
  const order = [];
  const h = harness({ initial: '#/content/materials', hooks: { beforeLeave: (m) => order.push('leave:' + m.route.path), afterMount: (m, c, info) => order.push('after:' + m.route.path + ':' + info.initial) } });
  await h.router.start();
  ok(h.router.sync('/content/categories'), 'sync accepted');
  eq(h.env.calls.replace.slice(-1), ['#/content/categories']);
  eq(h.router.current().route.path, '/content/categories');
  eq(mounts(h.events), ['mount:/content/materials'], 'sync does not remount');
  ok(h.events.includes('route:/content/categories:silent'));
  await h.router.navigate('/overview'); await delay(5);
  eq(order, ['after:/content/materials:true', 'leave:/content/categories', 'after:/overview:false']);
  eq(h.router.sync('/nope'), false);
}
{ // shellLoad wins over load; destroy unmounts and stops listening
  const events = []; const env = fakeEnv('#/people/users/7'); const el = fakeMount();
  const mk = (n) => ({ default: { mount(t) { events.push('mount:' + n); t.replaceChildren(n); }, unmount() { events.push('unmount:' + n); } } });
  const patched = routes.map((r) => r.path === '/people/users/:id' ? { ...r, load: async () => mk('real-detail'), shellLoad: async () => mk('shell-users') } : { ...r, load: async () => mk(r.path), shellLoad: undefined });
  const router2 = createRouter({ routes: patched, aliases, mount: el, env, hooks: {} });
  await router2.start();
  eq(events, ['mount:shell-users'], 'the unavailable user-detail module is not mounted by the shell');
  await router2.destroy();
  eq(events, ['mount:shell-users', 'unmount:shell-users']);
  eq(env.listenerCount(), 0);
}
assert.throws(() => createRouter({ routes: [], mount: fakeMount(), env: fakeEnv() }), TypeError);
assert.throws(() => createRouter({ routes, env: fakeEnv() }), TypeError);

// ── 5. admin.html ─────────────────────────────────────────────────────────────────────────────
const html = await read('admin.html');
ok(!/admin-tab\b|admin-tabs|adminTabsScroll|tabsArrow|data-tab=|onclick="switchTab/.test(html), 'horizontal tab strip is gone');
ok(!/overflow-x:\s*auto[^}]*\.admin-tabs/.test(html));
ok(html.includes('href="admin/admin-shell.css"'), 'shell stylesheet linked');
ok(!html.includes('admin/mock-api.js'), 'mock API never loaded in production');
ok(!/<script[^>]+admin\/(router|routes)\.js/.test(html), 'router modules are imported by admin.js, not as classic scripts');
const navBlock = html.match(/<nav class="nav shell-nav" id="adminNav"[\s\S]*?<\/nav>/)[0];
const groups = [...navBlock.matchAll(/data-group="([a-z]+)"/g)].map((m) => m[1]);
eq(groups, sections.map((s) => s.id), 'six groups in the sidebar');
const headings = [...navBlock.matchAll(/class="shell-nav-heading" id="navgrp-([a-z]+)">([^<]+)</g)].map((m) => [m[1], m[2]]);
eq(headings, sections.filter((s) => s.id !== 'overview').map((s) => [s.id, s.label]), 'group headings');
const links = [...navBlock.matchAll(/<a class="nav-item shell-nav-(?:top|sub)" href="([^"]+)" data-route="([^"]+)">([\s\S]*?)<\/a>/g)];
eq(links.map((m) => m[2]), routes.filter((r) => r.nav !== false).map((r) => r.path), 'sidebar links match nav routes in order');
for (const m of links) eq(m[1], '#' + m[2], 'href is the hash route');
for (const [, , path, inner] of links) {
  const route = byPath[path];
  const text = inner.match(/<span>([^<]+)<\/span>/)[1];
  eq(text, route.label || route.title, `label for ${path}`);
  const tag = inner.match(/class="nav-tag">([^<]+)</);
  eq(Boolean(tag), route.status === 'partial' || route.status === 'not-implemented', `nav tag only on partial/not-built pages (${path})`);
}
eq(links.map((m) => m[3]).filter((i) => i.includes('nav-badge')).length, 3, 'three badge slots');
for (const [id, path] of [['flaggedBadge', '/people/users'], ['reportedBadge', '/content/reported-questions'], ['deletionBadge', '/people/deletion-requests']]) {
  ok(links.find((m) => m[2] === path)[3].includes(`id="${id}"`), `${id} lives in ${path} (legacy badge refreshers update it by id)`);
}
eq([...html.matchAll(/id="flaggedBadge"|id="reportedBadge"|id="deletionBadge"/g)].length, 3, 'badge ids are unique');
for (const id of ['adminContent', 'adminRouteNotice', 'adminPageTitle', 'adminBreadcrumb', 'adminNavToggle', 'adminSidebar', 'adminNavBackdrop', 'topbarName', 'modal']) ok(html.includes(`id="${id}"`), `#${id} exists`);
ok(!/id="printExclude"/.test(html), 'printExclude is owned by the reports screen only (no duplicate id)');
for (const m of html.matchAll(/href="#([^"]*)"/g)) ok(m[1] === '' || m[1].startsWith('/'), `static "#${m[1]}" link would hit the hash router`);
ok(html.includes('class="nav-item logout"') && !html.includes('href="admindashboard.html"'), 'logout kept; the legacy dashboard link is gone (that page only redirects to Overview)');
ok(!/location\.(replace|href)\s*=\s*['"]admindashboard/.test(html), 'admindashboard.html is not redirected in this phase');
for (const file of [...await files('admin/legacy/', '.js'), 'admin/shared.js']) {
  ok(!/href=\\?["']#(?![\/])[^"']*["']/.test(await read(file)), `${file}: no bare-hash anchors that the router would treat as navigation`);
}

// ── 6. admin.js bridge ────────────────────────────────────────────────────────────────────────
const js = await read('admin.js');
ok(!/admin-tabs-wrap|adminTabsScroll|VALID_TABS|applyHashTab/.test(js), 'no tab-strip leftovers (the old code threw once .admin-tabs-wrap was removed)');
ok(!/addEventListener\(\s*["']hashchange["']/.test(js), 'hashchange is owned by the router');
ok(/function switchTab\(tab\)\s*\{\s*if \(adminRouter\)/.test(js), 'switchTab delegates to the router');
ok(/function renderCurrentTab\(\)/.test(js), 'renderCurrentTab kept for legacy re-renders');
ok(/import\(\s*["']\.\/admin\/router\.js["']\s*\)/.test(js) && /import\(\s*["']\.\/admin\/routes\.js["']\s*\)/.test(js));
ok(/await startAdminShell\(\);/.test(js), 'initAdmin starts the router after data loads');
ok(js.indexOf('/api/me') < js.indexOf('await startAdminShell();') && /if \(!me\?\.isAdmin\)/.test(js), 'admin check still runs before the shell starts');
ok(/err\.status === 401\) \{ window\.location\.href = "\/login\.html"/.test(js), '401 redirect kept');
ok(/window\.addEventListener\("pagehide", aitCancelActiveUpload\)/.test(js) && /window\.addEventListener\("beforeunload"/.test(js), 'AI Testing pagehide/beforeunload cleanup kept');
ok(/if \(route\.legacyTab === "aitesting" && aitUploadInProgress\) aitCancelActiveUpload\(\)/.test(js), 'leaving AI testing cancels an in-flight upload');
ok(/if \(link\.hasAttribute\("data-route"\)\) return;/.test(js), 'route links skip the page-leave cancel handler');
ok(!/\.innerHTML\s*=[^;]*resolved/.test(js), 'router data is rendered with textContent');
ok(!/window\.location\.href\s*=\s*["']\/?admindashboard/.test(js), 'no dashboard redirect added');
const stripComments = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
ok(!/["'`]\/admin\//.test(stripComments(await read('admin/routes.js') + await read('admin/router.js'))), 'no server-path (/admin/...) routes in code');

console.log(`Admin shell tests passed (${checks} checks): grouping, aliases, hash resolution, router lifecycle, admin.html and admin.js static checks.`);
