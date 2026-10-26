/**
 * Regression guard: the admin UI must never show stray "null" / "undefined" / "false" text.
 *
 * Root cause it protects against: native Element.append() / prepend() / replaceChildren() / before() / after()
 * turn a null, undefined or false argument into a visible text node. `cond ? node : null` passed straight to
 * them renders the word "null" on screen (e.g. "nullnull" under Overview > Topics & content).
 *
 * Two layers:
 *   1. Static lint  - no admin module may call a native insertion method with an argument that can be
 *                     null/false (conditional or `&&`). Overview may only touch them inside its own helpers.
 *   2. Behavior     - the real Overview view is mounted in a small DOM that copies the native semantics, for the
 *                     states that used to leak (topics + quiz ok, many topics, quiz failed, empty, ...).
 *                     No rendered text node may be "null", "undefined", "false" or "[object ...]".
 *
 * Run: node scripts/test-admin-no-null-text.mjs      (no browser, no dependencies)
 */
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const adminDir = path.join(root, 'src/main/resources/static/admin');
let n = 0;
const ok = (v, m) => { n += 1; assert.ok(v, m); };

// ── 1. Static lint ──────────────────────────────────────────────────────────────────────────────
async function walk(dir) {
  const out = [];
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (e.name !== 'legacy') out.push(...await walk(p)); }
    else if (e.name.endsWith('.js') && e.name !== 'mock-api.js') out.push(p);
  }
  return out;
}
const stripComments = (t) => t.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' ')).replace(/(^|[^:])\/\/.*$/gm, '$1');
const NATIVE = /\.(append|prepend|replaceChildren|before|after)\(/g;
const RISKY = /:\s*(null|undefined|false)\b|&&|\|\|\s*(null|undefined|false)\b/;
// Only the argument's own top level matters: `cond ? h(...) : null` is a leak, but a `&&` buried inside a nested call
// such as greeting(a && b) is not a direct child. Blank out everything inside nested (), [], {} and quotes first.
function topLevelOnly(args) {
  let out = '', depth = 0, quote = null;
  for (let i = 0; i < args.length; i++) {
    const c = args[i];
    if (quote) { if (c === '\\') i++; else if (c === quote) quote = null; continue; }
    if (c === '"' || c === "'" || c === '`') { quote = c; continue; }
    if ('([{'.includes(c)) { if (!depth) out += c; depth++; continue; }
    if (')]}'.includes(c)) { depth--; if (!depth) out += c; continue; }
    if (!depth) out += c;
  }
  return out;
}

function nativeCalls(code) {
  const calls = [];
  for (const m of code.matchAll(NATIVE)) {
    let depth = 1, j = m.index + m[0].length;
    while (j < code.length && depth) { const c = code[j++]; if (c === '(') depth++; else if (c === ')') depth--; }
    calls.push({ method: m[1], args: code.slice(m.index + m[0].length, j - 1), line: code.slice(0, m.index).split('\n').length, head: code.slice(Math.max(0, m.index - 40), m.index) });
  }
  return calls;
}

for (const file of await walk(adminDir)) {
  const rel = path.relative(root, file);
  const code = stripComments(await readFile(file, 'utf8'));
  for (const c of nativeCalls(code)) {
    ok(!RISKY.test(topLevelOnly(c.args)), `${rel}:${c.line}  .${c.method}() receives an argument that can be null/false and would print it as text. ` +
      'Build the children with h() (it skips null/false) or filter them first.');
  }
}

// Overview is stricter: every insertion goes through put()/swap(); the only native calls left are the helpers,
// h() itself (which guards each child) and the one <head> stylesheet insert.
{
  const code = stripComments(await readFile(path.join(adminDir, 'views/overview.js'), 'utf8'));
  const allowed = [/^\s*document\.head$/, /^\s*(parent)$/, /^\s*(node)$/];
  for (const c of nativeCalls(code)) {
    const receiver = (c.head.match(/([\w.]+)$/) || [, ''])[1];
    ok(['document.head', 'parent', 'node'].includes(receiver), `overview.js:${c.line} raw .${c.method}() on "${receiver}" - use put() / swap() so null never renders`);
  }
  ok(/function put\(/.test(code) && /function swap\(/.test(code) && /function visibleKids\(/.test(code), 'overview.js defines put/swap/visibleKids');
  void allowed;
}

// ── 2. Behavior: a DOM with the native insertion semantics ───────────────────────────────────────────────
class FNode { constructor() { this.parent = null; } }
class FText extends FNode { constructor(t) { super(); this.data = String(t); } get textContent() { return this.data; } }
class FElement extends FNode {
  constructor(tag) { super(); this.tag = tag; this.children = []; this.attrs = {}; this.listeners = {}; this.className = ''; this.id = ''; }
  _adopt(kids) {
    // Native semantics: Node stays a node, EVERYTHING else (null, undefined, false, numbers...) becomes String(x) text.
    return kids.map((k) => (k instanceof FNode ? k : new FText(String(k))));
  }
  append(...kids) { for (const k of this._adopt(kids)) { k.parent = this; this.children.push(k); } }
  prepend(...kids) { const ks = this._adopt(kids); ks.forEach((k) => { k.parent = this; }); this.children.unshift(...ks); }
  replaceChildren(...kids) { this.children = []; this.append(...kids); }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this); this.parent = null; }
  setAttribute(k, v) { this.attrs[k] = String(v); }
  getAttribute(k) { return this.attrs[k] ?? null; }
  addEventListener(t, fn) { (this.listeners[t] ||= []).push(fn); }
  removeEventListener() {}
  getContext() { return {}; }
  set textContent(v) { this.children = []; if (v !== '' && v != null) this.append(String(v)); }
  get textContent() { return this.children.map((c) => c.textContent).join(''); }
  set hidden(v) { this._hidden = v; } get hidden() { return !!this._hidden; }
  focus() {}
  querySelector() { return null; } querySelectorAll() { return []; }
}
const texts = (node) => (node instanceof FText ? [node.data] : node.children.flatMap(texts));
const BAD = /^(null|undefined|false|true|NaN)$|^\[object |nullnull|undefinedundefined|(^|\s)(null|undefined)(\s|$)/;

function installDom() {
  const head = new FElement('head');
  const doc = {
    head, visibilityState: 'visible', _l: {},
    createElement: (t) => new FElement(t), createTextNode: (t) => new FText(t),
    getElementById: () => null, querySelector: () => null, querySelectorAll: () => [],
    addEventListener() {}, removeEventListener() {}
  };
  globalThis.document = doc;
  globalThis.window = { location: { href: '' }, addEventListener() {}, removeEventListener() {} };
  globalThis.Node = FNode;
  return doc;
}

// 2a. the shim really reproduces the bug (so the tests below can fail)
{
  installDom();
  const probe = new FElement('div');
  probe.append(null, undefined, false);
  ok(texts(probe).join('|') === 'null|undefined|false', 'DOM shim copies native behavior: append(null) prints "null"');
}

const overview = (await import(path.join(adminDir, 'views/overview.js'))).default;
const settle = () => new Promise((r) => setTimeout(r, 30));

const attempts = (topic, scores) => scores.map((s, i) => ({ topic, score: s, difficulty: 'Medium', date: `2026-03-0${i + 1}` }));
function fixtures(over = {}) {
  return {
    '/api/admin/overview': { stats: { totalUsers: 12, totalTopics: 1, totalQuestions: 41, totalAttempts: 4, totalMaterials: 9 } },
    '/api/admin/users': { users: [{ id: 1, email: 'a@x.test', isAdmin: false }] },
    '/api/admin/banned-emails': { bannedEmails: [] },
    '/api/admin/activity-log': { log: [] },
    '/api/admin/reports?status=PENDING': { reports: [] },
    '/api/admin/deletion-requests?status=PENDING': { requests: [] },
    '/api/admin/deletion-requests?status=APPROVED': { requests: [] },
    '/api/admin/self-deletion-requests': { success: true, requests: [] },
    '/api/admin/quiz-activity': { quizHistory: attempts('Euthenics 4', [70, 72, 74, 72]) },
    '/api/admin/topics': ['Euthenics 4'],
    '/api/admin/material-categories?range=all': { categoryBreakdown: [] },
    ...over
  };
}

async function render(label, data) {
  const doc = installDom();
  const host = new FElement('main');
  const ctx = {
    session: { user: { name: 'Admin Tester' } },
    api: {
      async get(p) {
        const v = data[p];
        if (v instanceof Error) throw v;
        if (v === undefined) throw Object.assign(new Error(`no fixture for ${p}`), { status: 404 });
        return v;
      }
    }
  };
  await overview.mount(host, ctx);
  await settle();
  const all = texts(host);
  const leaks = all.filter((t) => BAD.test(t.trim()) || /\bnull\b/.test(t) || /\bundefined\b/.test(t));
  overview.unmount();
  ok(all.join(' ').includes('Topics & content'), `${label}: Overview rendered`);
  ok(leaks.length === 0, `${label}: stray text rendered on screen: ${JSON.stringify(leaks)}`);
  return all;
}

// The exact scenario from the bug report: one topic, four attempts, everything healthy -> used to show "nullnull".
const happy = await render('topics + quiz ok (bug report)', fixtures());
ok(happy.includes('Euthenics 4'), 'the topic row is still rendered');
ok(happy.some((t) => /OK .* 72%/.test(t)), 'performance pill is still rendered');

// More topics than the table shows (the "...and N more" child is present, the quiz note is absent).
const many = Array.from({ length: 11 }, (_, i) => `Topic ${i + 1}`);
const manyHost = await render('more topics than rows', fixtures({ '/api/admin/topics': many }));
ok(manyHost.some((t) => /and 3 more/.test(t)), 'the "and N more" note still appears when it should');

// Quiz source fails (the quiz-error note is present, the "more" child is absent).
const quizDown = await render('quiz source failed', fixtures({ '/api/admin/quiz-activity': Object.assign(new Error('boom'), { status: 500 }) }));
ok(quizDown.some((t) => /Quiz data could not be loaded/.test(t)), 'the quiz-failure note still appears when it should');

// Both optional children present at once.
await render('many topics and quiz failed', fixtures({ '/api/admin/topics': many, '/api/admin/quiz-activity': new Error('boom') }));

// Remaining edge states.
await render('no topics', fixtures({ '/api/admin/topics': [] }));
await render('no quiz attempts', fixtures({ '/api/admin/quiz-activity': { quizHistory: [] } }));
await render('banned emails failed', fixtures({ '/api/admin/banned-emails': new Error('boom') }));
await render('everything failed', Object.fromEntries(Object.keys(fixtures()).map((k) => [k, new Error('down')])));
await render('malformed bodies', Object.fromEntries(Object.keys(fixtures()).map((k) => [k, { unexpected: true }])));

console.log(`Admin "no stray null text" tests passed (${n} checks): static lint of all admin modules + Overview rendered in ${9} states.`);
