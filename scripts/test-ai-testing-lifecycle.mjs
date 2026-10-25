// Behavioral regression test for the admin AI Testing sandbox.
//
// Unlike the string checks in test-ai-testing-preview.mjs, this EXECUTES the real
// shipped scripts inside a Node `vm` context with a small fake DOM, and asserts on
// observable behavior:
//   * a successful run no longer throws (the old loadAitPreviewFrame() referenced an
//     undefined `el`), shows the iframe preview, and ends with "All tests complete.";
//   * genuine failures (quiz generation, upload, a throw while rendering the preview)
//     are still surfaced to the admin;
//   * the upload-cancel lifecycle: pagehide / beforeunload send exactly one
//     /api/materials/cancel-upload beacon with the in-flight uploadId and topic, and
//     send nothing when no upload is in flight;
//   * upload-progress.js honours cancelOnLeave:false (no double cancel) and, by default,
//     aborts + beacons.
//
// Real files executed: admin/shared.js, admin/legacy/aitesting.js, upload-progress.js,
// and ONLY the AI-upload lifecycle block of admin.js (its `let aitUploadInProgress`,
// `let aitActiveUploadId` and the pagehide/beforeunload listeners) — the rest of
// admin.js is a 3.8k-line page script that needs a full browser DOM.
//
// NOT covered (needs a real browser / running server): real XHR/SSE/sendBeacon
// delivery, bfcache behavior, in-app hash navigation, and the Spring endpoint itself.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const staticRoot = new URL('../src/main/resources/static/', import.meta.url);
const read = (p) => readFile(new URL(p, staticRoot), 'utf8');
const [sharedSrc, aitSrc, adminSrc, uploadProgressSrc] = await Promise.all([
  read('admin/shared.js'), read('admin/legacy/aitesting.js'), read('admin.js'), read('upload-progress.js')
]);

const lifecycleStart = adminSrc.indexOf('let aitUploadInProgress');
const lifecycleEnd = adminSrc.indexOf('// ── Shell / hash-router compatibility bridge', lifecycleStart);
assert.ok(lifecycleStart >= 0 && lifecycleEnd > lifecycleStart,
  'admin.js: AI upload lifecycle block (state and pagehide/beforeunload listeners before the shell bridge) not found; update this test');
const adminLifecycle = adminSrc.slice(lifecycleStart, lifecycleEnd);

const API = 'http://api.test';
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const tick = () => new Promise((r) => setTimeout(r, 0));

// ── fake DOM ────────────────────────────────────────────────────────────────
class FakeEl {
  constructor(id) {
    this.id = id; this.textContent = ''; this.className = ''; this.style = {}; this.disabled = false;
    this.value = ''; this.files = []; this.dataset = {}; this.listeners = {}; this._html = '';
    this.classList = { add() {}, remove() {} };
  }
  get innerHTML() { return this._html; }
  set innerHTML(v) { if (this.onSetHtml) this.onSetHtml(String(v)); this._html = String(v); }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  click() {} appendChild() {}
}

function makeAitEnv({ api, send } = {}) {
  const els = new Map();
  const beacons = [];
  const windowListeners = {};
  const lockCalls = [];
  const sendCalls = [];
  const env = { beacons, lockCalls, sendCalls, beaconThrows: false, mathCalls: [] };
  let uuid = 0;

  const bar = { setFile() {}, setLocked(v) { lockCalls.push(v); }, clear() {}, reset() {} };
  const sandbox = {
    console, Date, Math, JSON, Promise, Blob, FormData, URL, Error, Object, Array, String, Number,
    setTimeout, clearTimeout, encodeURIComponent,
    API_BASE: API,
    crypto: { randomUUID: () => `upload-${++uuid}` },
    navigator: { sendBeacon(url, blob) { if (env.beaconThrows) throw new Error('beacon unsupported'); beacons.push({ url, blob }); return true; } },
    document: {
      getElementById(id) { if (!els.has(id)) els.set(id, new FakeEl(id)); return els.get(id); },
      createElement: () => new FakeEl('created'), addEventListener() {}
    },
    UploadProgress: { mount: () => bar, send: (opts) => { sendCalls.push(opts); return send(opts); } },
    apiFetch: (path, options) => api(path, options),
    renderMathOnly: (node) => env.mathCalls.push(node)
  };
  sandbox.window = sandbox;
  sandbox.addEventListener = (type, fn) => { (windowListeners[type] ||= []).push(fn); };
  const ctx = vm.createContext(sandbox);
  vm.runInContext(sharedSrc, ctx, { filename: 'admin/shared.js' });
  vm.runInContext(aitSrc, ctx, { filename: 'admin/legacy/aitesting.js' });
  vm.runInContext(adminLifecycle, ctx, { filename: 'admin.js(lifecycle block)' });

  env.el = (id) => sandbox.document.getElementById(id);
  env.state = () => ({
    inProgress: vm.runInContext('aitUploadInProgress', ctx),
    uploadId: vm.runInContext('aitActiveUploadId', ctx)
  });
  env.fire = (type) => (windowListeners[type] || []).forEach((fn) => fn({}));
  env.render = () => {
    vm.runInContext('renderAiTesting()', ctx, { filename: 'renderAiTesting' });
    env.el('aitTopic').value = 'Cell Biology & Co';
    env.el('aitDifficulty').value = 'Easy';
    env.el('aitFileInput').files = [new Blob(['hello'])];
    env.el('aitFileInput').listeners.change.forEach((fn) => fn({}));
  };
  env.run = () => env.el('aitRunAll').onclick();
  env.payload = async (i = 0) => JSON.parse(await beacons[i].blob.text());
  return env;
}

const okApi = (over = {}) => async (path, options = {}) => {
  if (path.startsWith('/api/quiz/test-types')) return over.quiz ? over.quiz() : { success: true, questions: [{}, {}, {}] };
  if (path.startsWith('/api/ai/lesson')) return { noAttemptYet: true };
  if (path === '/api/materials') return { materials: [{ topic: 'Cell Biology & Co', topicSummary: 'sum', primaryCategory: 'Biology' }] };
  throw new Error('unexpected api call ' + path + ' ' + (options.method || ''));
};

// ── tests ───────────────────────────────────────────────────────────────────
const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('successful run: no ReferenceError, iframe preview rendered, run completes and resets state', async () => {
  const env = makeAitEnv({ api: okApi(), send: async () => ({ success: true }) });
  env.render();
  await env.run();
  const status = env.el('aitGlobalStatus');
  assert.equal(status.textContent, 'All tests complete.', `run reported: ${status.textContent}`);
  assert.match(status.className, /success/);
  const preview = env.el('aitPreviewBody').innerHTML;
  assert.match(preview, /<iframe /);
  assert.match(preview, /adminPreview=1/);
  assert.match(preview, /topic=Cell%20Biology%20%26%20Co&amp;difficulty=Test|topic=Cell%20Biology%20%26%20Co&difficulty=Test/);
  assert.equal(env.el('aitQBadge').textContent, '3 types OK');
  assert.deepEqual(env.state(), { inProgress: false, uploadId: null });
  assert.deepEqual(env.lockCalls, [true, false], 'chip locked for the run, then unlocked');
  assert.equal(env.el('aitRunAll').disabled, false);
  assert.equal(env.beacons.length, 0, 'a finished run must not send cancel beacons');
});

test('upload call contract: id matches active id, cancelOnLeave/manageLock off, form has topic+file', async () => {
  const seen = {};
  const env = makeAitEnv({
    api: okApi(),
    send: async (opts) => { seen.id = env.state().uploadId; seen.inProgress = env.state().inProgress; return { success: true }; }
  });
  env.render();
  await env.run();
  const opts = env.sendCalls[0];
  assert.equal(opts.uploadId, seen.id);
  assert.equal(seen.inProgress, true);
  assert.equal(opts.cancelOnLeave, false);
  assert.equal(opts.manageLock, false);
  assert.equal(opts.topic, 'Cell Biology & Co');
  assert.equal(opts.formData.get('topic'), 'Cell Biology & Co');
  assert.ok(opts.formData.get('file'));
});

for (const [label, quiz] of [
  ['quiz request rejects', () => { throw new Error('claude down'); }],
  ['quiz returns success:false', () => ({ success: false, message: 'nope' })],
  ['quiz returns zero questions', () => ({ success: true, questions: [] })]
]) {
  test(`genuine preview failure still reported (${label})`, async () => {
    const env = makeAitEnv({ api: okApi({ quiz }), send: async () => ({ success: true }) });
    env.render();
    await env.run();
    const preview = env.el('aitPreviewBody').innerHTML;
    assert.match(preview, /Could not generate the all-types quiz/);
    assert.match(preview, /ait-status error/);
    assert.doesNotMatch(preview, /<iframe/);
    assert.equal(env.el('aitQBadge').textContent, 'Failed');
    assert.deepEqual(env.state(), { inProgress: false, uploadId: null });
  });
}

test('an error thrown while rendering the preview still surfaces as "Test run failed"', async () => {
  const env = makeAitEnv({ api: okApi(), send: async () => ({ success: true }) });
  env.render();
  env.el('aitPreviewBody').onSetHtml = (html) => { if (html.includes('<iframe')) throw new Error('boom'); };
  await env.run();
  assert.equal(env.el('aitGlobalStatus').textContent, 'Test run failed: boom');
  assert.match(env.el('aitGlobalStatus').className, /error/);
  assert.deepEqual(env.state(), { inProgress: false, uploadId: null });
  assert.deepEqual(env.lockCalls, [true, false]);
  assert.equal(env.el('aitRunAll').disabled, false);
});

test('upload failure is reported and state/lock/button are restored', async () => {
  const env = makeAitEnv({ api: okApi(), send: async () => ({ success: false, message: 'File is too large.' }) });
  env.render();
  await env.run();
  assert.equal(env.el('aitGlobalStatus').textContent, 'Test run failed: File is too large.');
  assert.deepEqual(env.state(), { inProgress: false, uploadId: null });
  assert.deepEqual(env.lockCalls, [true, false]);
  assert.equal(env.el('aitRunAll').disabled, false);
  assert.equal(env.beacons.length, 0);
});

test('each run gets a fresh uploadId', async () => {
  const env = makeAitEnv({ api: okApi(), send: async () => ({ success: true }) });
  env.render();
  await env.run(); await env.run();
  assert.equal(env.sendCalls.length, 2);
  assert.notEqual(env.sendCalls[0].uploadId, env.sendCalls[1].uploadId);
});

test('pagehide mid-upload sends exactly one cancel beacon with uploadId+topic; beforeunload then adds none', async () => {
  const gate = deferred();
  const env = makeAitEnv({ api: okApi(), send: () => gate.promise });
  env.render();
  const run = env.run();
  await tick();
  const id = env.state().uploadId;
  assert.equal(env.state().inProgress, true);
  env.fire('pagehide');
  assert.equal(env.beacons.length, 1);
  assert.equal(env.beacons[0].url, `${API}/api/materials/cancel-upload`);
  assert.deepEqual(await env.payload(), { uploadId: id, topic: 'Cell Biology & Co' });
  assert.deepEqual(env.state(), { inProgress: false, uploadId: null });
  env.fire('beforeunload');
  assert.equal(env.beacons.length, 1, 'no duplicate cancel');
  gate.resolve({ success: true }); await run;
});

test('beforeunload alone mid-upload sends the cancel beacon', async () => {
  const gate = deferred();
  const env = makeAitEnv({ api: okApi(), send: () => gate.promise });
  env.render();
  const run = env.run();
  await tick();
  const id = env.state().uploadId;
  env.fire('beforeunload');
  assert.equal(env.beacons.length, 1);
  assert.equal((await env.payload()).uploadId, id);
  gate.resolve({ success: true }); await run;
});

test('no beacon when idle, before any run, or after a run has finished', async () => {
  const env = makeAitEnv({ api: okApi(), send: async () => ({ success: true }) });
  env.render();
  env.fire('pagehide'); env.fire('beforeunload');
  assert.equal(env.beacons.length, 0, 'idle');
  await env.run();
  env.fire('pagehide'); env.fire('beforeunload');
  assert.equal(env.beacons.length, 0, 'after a finished run');
});

test('characterization: leaving during post-upload test generation still beacons (server treats it as a no-op once /upload succeeded)', async () => {
  // The flag stays true until the whole run ends, so pagehide during the all-types quiz
  // request sends a beacon. MaterialController removes pendingUploads[uploadId] when /upload
  // succeeds, so the server ignores it: the uploaded test material is intentionally kept.
  const quizGate = deferred();
  const env = makeAitEnv({ api: okApi({ quiz: () => quizGate.promise }), send: async () => ({ success: true }) });
  env.render();
  const run = env.run();
  await tick(); await tick();
  assert.equal(env.state().inProgress, true);
  env.fire('pagehide');
  assert.equal(env.beacons.length, 1);
  quizGate.resolve({ success: true, questions: [{}] });
  await run;
});

test('a failing navigator.sendBeacon is swallowed and state is still cleared', async () => {
  const gate = deferred();
  const env = makeAitEnv({ api: okApi(), send: () => gate.promise });
  env.render();
  const run = env.run();
  await tick();
  env.beaconThrows = true;
  assert.doesNotThrow(() => env.fire('pagehide'));
  assert.deepEqual(env.state(), { inProgress: false, uploadId: null });
  gate.resolve({ success: true }); await run;
});

// ── upload-progress.js (real file) ──────────────────────────────────────────
function makeUploadProgressEnv() {
  const beacons = []; const xhrs = []; const listeners = {};
  class FakeXHR {
    constructor() { this.upload = {}; this.aborted = false; xhrs.push(this); }
    open() {} setRequestHeader() {} send() {}
    abort() { this.aborted = true; this.onabort && this.onabort(); }
  }
  class FakeES { addEventListener() {} close() { this.closed = true; } }
  const sandbox = {
    console, Date, Math, JSON, Promise, Blob, Error, Object, String, setTimeout, clearTimeout,
    API_BASE: API, XMLHttpRequest: FakeXHR, EventSource: FakeES,
    navigator: { sendBeacon(url, blob) { beacons.push({ url, blob }); return true; } },
    document: {}, crypto: { randomUUID: () => 'generated-id' }
  };
  sandbox.window = sandbox;
  sandbox.addEventListener = (t, fn) => { (listeners[t] ||= []).push(fn); };
  const ctx = vm.createContext(sandbox);
  vm.runInContext(uploadProgressSrc, ctx, { filename: 'upload-progress.js' });
  const bar = { setLocked() {}, start() {}, setPercent() {}, setProcessing() {}, finish() {}, reset() {} };
  const fd = new FormData(); fd.append('topic', 'T');
  return {
    beacons, xhrs, bar, fd, UploadProgress: sandbox.UploadProgress,
    fire: (t) => (listeners[t] || []).forEach((fn) => fn({}))
  };
}

test('upload-progress.js: cancelOnLeave:false → page exit neither aborts the XHR nor beacons (admin.js owns cleanup)', async () => {
  const e = makeUploadProgressEnv();
  const p = e.UploadProgress.send({ bar: e.bar, formData: e.fd, topic: 'T', uploadId: 'u1', cancelOnLeave: false, manageLock: false });
  e.fire('pagehide'); e.fire('beforeunload');
  assert.equal(e.xhrs[0].aborted, false);
  assert.equal(e.beacons.length, 0);
  e.xhrs[0].status = 200; e.xhrs[0].responseText = '{"success":true}'; e.xhrs[0].onload();
  assert.deepEqual(await p, { success: true });
});

test('upload-progress.js: default cancelOnLeave → page exit aborts and sends one cancel beacon', async () => {
  const e = makeUploadProgressEnv();
  const p = e.UploadProgress.send({ bar: e.bar, formData: e.fd, topic: 'T', uploadId: 'u2' });
  e.fire('pagehide'); e.fire('beforeunload');
  assert.equal(e.xhrs[0].aborted, true);
  assert.equal(e.beacons.length, 1);
  assert.equal(e.beacons[0].url, `${API}/api/materials/cancel-upload`);
  assert.deepEqual(JSON.parse(await e.beacons[0].blob.text()), { uploadId: 'u2', topic: 'T' });
  await assert.rejects(p, (err) => err.cancelled === true);
});

test('upload-progress.js: after the upload settles, page exit sends nothing', async () => {
  const e = makeUploadProgressEnv();
  const p = e.UploadProgress.send({ bar: e.bar, formData: e.fd, topic: 'T', uploadId: 'u3' });
  e.xhrs[0].status = 200; e.xhrs[0].responseText = '{"success":true}'; e.xhrs[0].onload();
  await p;
  e.fire('pagehide'); e.fire('beforeunload');
  assert.equal(e.beacons.length, 0);
});

// ── runner ──────────────────────────────────────────────────────────────────
let failed = 0;
for (const { name, fn } of tests) {
  try { await fn(); console.log(`  ok   ${name}`); }
  catch (err) { failed++; console.log(`  FAIL ${name}\n       ${String(err && err.message || err).split('\n')[0]}`); }
}
console.log(`\n${tests.length - failed}/${tests.length} AI Testing lifecycle checks passed.`);
if (failed) process.exit(1);
