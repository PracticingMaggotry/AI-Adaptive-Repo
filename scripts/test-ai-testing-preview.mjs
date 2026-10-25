// Regression check: loadAitPreviewFrame() must not reference an undefined `el`
// (it only builds an iframe in its local `body`), and the AI Testing upload
// lifecycle hooks must still be present. Static, dependency-free.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const src = await readFile(
  new URL('../src/main/resources/static/admin/legacy/aitesting.js', import.meta.url), 'utf8');

function extractFunction(source, name) {
  const start = source.indexOf(`function ${name}(`);
  assert.ok(start >= 0, `function ${name} must exist`);
  const open = source.indexOf('{', source.indexOf(')', start));
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unbalanced braces in ${name}`);
}

const fn = extractFunction(src, 'loadAitPreviewFrame');
assert.ok(/iframe/.test(fn), 'preview function must still render the iframe');
assert.ok(/const body\s*=\s*document\.getElementById\("aitPreviewBody"\)/.test(fn), 'preview must still target aitPreviewBody');
for (const m of fn.matchAll(/renderMathOnly\(\s*([A-Za-z_$][\w$]*)\s*\)/g)) {
  assert.ok(new RegExp(`\\b(const|let|var)\\s+${m[1]}\\b`).test(fn) || /\(\s*topic,\s*difficulty\s*\)/.test(fn) && ['topic', 'difficulty'].includes(m[1]),
    `loadAitPreviewFrame references undeclared "${m[1]}" in renderMathOnly()`);
}
assert.ok(!/renderMathOnly\(\s*el\s*\)/.test(fn), 'loadAitPreviewFrame must not call renderMathOnly(el)');

// Upload lifecycle must be intact. The state/hooks live in admin.js and admin/shared.js
// (read-only here); aitesting.js owns the in-flight flags.
const root = new URL('../src/main/resources/static/', import.meta.url);
const adminJs = await readFile(new URL('admin.js', root), 'utf8');
const sharedJs = await readFile(new URL('admin/shared.js', root), 'utf8');
for (const token of ['aitUploadInProgress', 'aitActiveUploadId']) {
  assert.ok(src.includes(token), `aitesting.js lost upload lifecycle token: ${token}`);
}
assert.ok(/addEventListener\("pagehide",\s*aitCancelActiveUpload\)/.test(adminJs), 'admin.js must keep the pagehide cancel hook');
assert.ok(/addEventListener\("beforeunload"/.test(adminJs), 'admin.js must keep the beforeunload cancel hook');
assert.ok(sharedJs.includes('/api/materials/cancel-upload'), 'shared.js must keep the cancel endpoint call');
console.log('AI Testing preview regression check passed.');
