/**
 * Rules for the boot-time request hand-off (admin/startup-prefetch.js).
 * Run: node scripts/test-admin-startup-prefetch.mjs   (no browser, no dependencies)
 */
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { createStartupPrefetch, STARTUP_TTL_MS } = await import(path.join(root, 'src/main/resources/static/admin/startup-prefetch.js'));

let checks = 0;
const eq = (a, b, m) => { checks += 1; assert.deepEqual(a, b, m); };
const ok = (v, m) => { checks += 1; assert.ok(v, m); };
async function rejects(promise, name, m) {
  checks += 1;
  try { await promise; } catch (e) { assert.equal(e.name, name, m); return; }
  assert.fail(`${m}: expected a ${name} rejection`);
}

/** A fake apiFetch that counts calls and can be told to fail or wait. */
function harness({ ttlMs } = {}) {
  const calls = [];
  const pending = [];
  let clock = 1000;
  const fail = new Set();
  const manual = new Set();
  const fetcher = (p, options = {}) => {
    calls.push({ path: p, method: options.method || 'GET' });
    if (manual.has(p)) return new Promise((resolve, reject) => pending.push({ p, resolve, reject }));
    if (fail.has(p)) return Promise.reject(Object.assign(new Error('boom'), { status: 500 }));
    return Promise.resolve({ path: p, n: calls.length, users: [{ id: 1 }, { id: 2 }] });
  };
  const sp = createStartupPrefetch(fetcher, { ttlMs, now: () => clock });
  return { sp, calls, pending, fail, manual, tick: (ms) => { clock += ms; } };
}

// 1. The whole point: boot fetches once, the first view gets that result with no second network call.
{
  const h = harness();
  const boot = await h.sp.prefetch('/api/admin/users');
  const view = await h.sp.get('/api/admin/users');
  eq(h.calls.length, 1, 'only one network request for boot + first view');
  eq(view.path, '/api/admin/users', 'the view receives the boot response');
  eq(boot.n, view.n, 'same response content');
}

// 2. One-shot: the second read (refresh button, revisit) goes to the server.
{
  const h = harness();
  await h.sp.prefetch('/api/admin/topics');
  await h.sp.get('/api/admin/topics');
  await h.sp.get('/api/admin/topics');
  eq(h.calls.length, 2, 'second read is a live request');
  eq(h.sp.pending(), 0, 'nothing left pending');
}

// 3. Unrelated paths are untouched and do not consume each other.
{
  const h = harness();
  await h.sp.prefetch('/api/admin/users');
  await h.sp.get('/api/admin/overview');
  eq(h.calls.map((c) => c.path), ['/api/admin/users', '/api/admin/overview'], 'different path is a live request');
  eq(h.sp.pending(), 1, 'users entry is still waiting for its reader');
}

// 4. TTL: a boot result older than the TTL is never shown.
{
  const h = harness();
  await h.sp.prefetch('/api/admin/users');
  h.tick(STARTUP_TTL_MS + 1);
  await h.sp.get('/api/admin/users');
  eq(h.calls.length, 2, 'expired entry -> live request');
  const h2 = harness({ ttlMs: 50 });
  await h2.sp.prefetch('/x');
  h2.tick(49);
  await h2.sp.get('/x');
  eq(h2.calls.length, 1, 'just inside the TTL -> reused');
}

// 5. Failure safety: a failed boot request is not inherited; the view tries for itself.
{
  const h = harness();
  h.fail.add('/api/admin/users');
  await rejects(h.sp.prefetch('/api/admin/users'), 'Error', 'boot code still sees the failure');
  eq(h.sp.pending(), 0, 'failed entry is dropped');
  h.fail.delete('/api/admin/users');
  const view = await h.sp.get('/api/admin/users');
  eq(view.path, '/api/admin/users', 'view gets a fresh, successful response');
  eq(h.calls.length, 2, 'two network attempts');
}

// 5b. Boot request fails AFTER the view already attached (in-flight hand-off): view falls back to a live request.
{
  const h = harness();
  h.manual.add('/api/admin/log');
  const boot = h.sp.prefetch('/api/admin/log');
  boot.catch(() => {});
  await Promise.resolve();
  const viewP = h.sp.get('/api/admin/log');
  h.manual.delete('/api/admin/log');
  h.pending[0].reject(Object.assign(new Error('late failure'), { status: 503 }));
  const view = await viewP;
  eq(view.path, '/api/admin/log', 'view recovered with its own request');
  eq(h.calls.length, 2, 'boot attempt + live retry');
}

// 6. In-flight sharing: a view that asks while boot is still loading rides the same request.
{
  const h = harness();
  h.manual.add('/api/admin/overview');
  const boot = h.sp.prefetch('/api/admin/overview');
  const viewP = h.sp.get('/api/admin/overview');
  h.pending[0].resolve({ stats: { totalUsers: 3 } });
  const [b, v] = await Promise.all([boot, viewP]);
  eq(h.calls.length, 1, 'one request for both');
  eq(b, v, 'both got the data');
}

// 7. Isolation: mutating one side's copy cannot change the other's.
{
  const h = harness();
  const boot = await h.sp.prefetch('/api/admin/users');
  const view = await h.sp.get('/api/admin/users');
  view.users.pop();
  view.users[0].id = 999;
  eq(boot.users, [{ id: 1 }, { id: 2 }], 'boot data unchanged by the view');
  const h2 = harness();
  const boot2 = h2.sp.prefetch('/api/admin/users');
  const view2 = await h2.sp.get('/api/admin/users');
  (await boot2).users.length = 0;
  eq(view2.users.length, 2, 'view data unchanged by boot code');
}

// 8. Mutation safety: any write through the layer invalidates everything prefetched.
{
  const h = harness();
  await h.sp.prefetch('/api/admin/users');
  await h.sp.prefetch('/api/admin/topics');
  await h.sp.get('/api/admin/users/5/ban', { method: 'POST' });
  eq(h.sp.pending(), 0, 'POST cleared the prefetched entries');
  await h.sp.get('/api/admin/topics');
  eq(h.calls.map((c) => `${c.method} ${c.path}`), ['GET /api/admin/users', 'GET /api/admin/topics', 'POST /api/admin/users/5/ban', 'GET /api/admin/topics'], 'writes pass through, later read is live');
  await h.sp.prefetch('/a');
  await h.sp.request('/api/admin/x', { method: 'DELETE' });
  eq(h.sp.pending(), 0, 'request() clears as well');
  const h2 = harness();
  await h2.sp.prefetch('/a');
  await h2.sp.get('/a', { method: 'put' });
  eq(h2.calls[1].method, 'put', 'method check is case-insensitive and passes the verb through');
}

// 9. Abort behaves like a real fetch: prompt AbortError, and an aborted reader does not consume a live request.
{
  const h = harness();
  const c = new AbortController();
  h.manual.add('/slow');
  h.sp.prefetch('/slow').catch(() => {});
  const viewP = h.sp.get('/slow', { signal: c.signal });
  c.abort();
  await rejects(viewP, 'AbortError', 'abort while waiting');
  const pre = new AbortController();
  pre.abort();
  const h2 = harness();
  await h2.sp.prefetch('/y');
  await rejects(h2.sp.get('/y', { signal: pre.signal }), 'AbortError', 'already-aborted signal');
  // a live (non-prefetched) request is passed to the fetcher untouched, signal included
  let seen = null;
  const sp = createStartupPrefetch((p, o) => { seen = o; return Promise.resolve(1); });
  const ctl = new AbortController();
  await sp.get('/live', { signal: ctl.signal });
  ok(seen.signal === ctl.signal, 'live requests keep their own AbortSignal');
}

// 10. Non-object payloads (strings, arrays of strings) pass through; arrays are copied.
{
  const sp = createStartupPrefetch(() => Promise.resolve(['Euthenics 4']));
  const boot = await sp.prefetch('/api/admin/topics');
  const view = await sp.get('/api/admin/topics');
  eq(view, ['Euthenics 4'], 'array payload');
  ok(view !== boot, 'array payload is a copy');
  const sp2 = createStartupPrefetch(() => Promise.resolve('plain text'));
  await sp2.prefetch('/t');
  eq(await sp2.get('/t'), 'plain text', 'string payload');
}

console.log(`Admin startup-prefetch tests passed (${checks} checks).`);
