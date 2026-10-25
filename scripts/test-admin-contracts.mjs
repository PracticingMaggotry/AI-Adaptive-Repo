import assert from 'node:assert/strict';
import { readFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import routes, { aliases } from '../src/main/resources/static/admin/routes.js';
import ui from '../src/main/resources/static/admin/ui.js';
import { createBadgeStore } from '../src/main/resources/static/admin/badges.js';
import {
  ACCOUNT_STATUS_SEVERITY,
  deriveAccountStatus,
  compareAccountStatus
} from '../src/main/resources/static/admin/account-status.js';

const contract = JSON.parse(await readFile(
  new URL('../src/main/resources/static/admin/contracts/api-contracts.json', import.meta.url),
  'utf8'
));

const expectedTabs = [
  'overview', 'users', 'topics', 'reportedquestions', 'bannedemails',
  'deletionrequests', 'reports', 'activitylog', 'assignedhandouts', 'aitesting'
];

const staticRoot = new URL('../src/main/resources/static/', import.meta.url);
const adminHtml = await readFile(new URL('admin.html', staticRoot), 'utf8');
const sharedUiCss = await readFile(new URL('admin/admin-components.css', staticRoot), 'utf8');
assert.match(sharedUiCss, /\.admin-ui-dialog\s*\{[^}]*margin:\s*auto/s, 'shared native confirmation dialogs must override legacy global margin resets and remain centered');
const harnessHtml = await readFile(new URL('admin-dev.html', staticRoot), 'utf8');
const localScriptSources = [...adminHtml.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/gi)]
  .map(match => match[1]).filter(src => !/^https?:\/\//i.test(src));
for (const src of localScriptSources) {
  await access(fileURLToPath(new URL(src, staticRoot)));
}
assert.ok(!adminHtml.includes('admin/mock-api.js'), 'production admin page must never load the mock API');
assert.ok(harnessHtml.includes('admin/mock-api.js'), 'development harness must load mock API responses');
assert.ok(harnessHtml.includes('admin-dev.html') === false, 'harness must not recursively include itself');
for (const tab of expectedTabs) {
  assert.ok(adminHtml.includes(`admin/legacy/${tab}.js`), `production page must load the ${tab} legacy bundle`);
}

// Phase 2: the six-section hash-route table replaced the one-route-per-tab manifest
// (details of grouping and aliases are asserted in scripts/test-admin-shell.mjs).
const canonicalPaths = [
  '/overview',
  '/people/users', '/people/users/:id', '/people/deletion-requests', '/people/banned-emails',
  '/content/materials', '/content/reported-questions', '/content/categories',
  '/study/participants', '/study/assigned-handouts', '/study/data-exports',
  '/system/usage-limits', '/system/configuration', '/system/ai-testing', '/system/reliability',
  '/audit/activity-log', '/audit/platform-report'
];
assert.deepEqual(routes.map(route => route.path).sort(), [...canonicalPaths].sort(), 'route table must match the canonical hash paths in the redesign brief');
const coveredTabs = new Set(routes.map(route => route.legacyTab).filter(Boolean));
// Tabs whose screen has been replaced by dedicated, non-legacy views. They intentionally have no route with
// legacyTab, because currentTab = legacyTab would let renderCurrentTab() re-render the old screen over the new
// view. Their compatibility is verified instead: the old hash alias must resolve to the dedicated view, and the
// legacy bundle must still be loaded (checked above) so the old renderer stays available.
const dedicatedTabs = {
  reports: { alias: '/audit/platform-report', loader: 'platform-report.js' },
  assignedhandouts: { alias: '/study/assigned-handouts', loader: 'assignedhandouts.js' }   // Phase 5: setCategoryRange() re-rendered the legacy screen over the dedicated view
};
for (const [tab, { alias, loader }] of Object.entries(dedicatedTabs)) {
  assert.ok(!coveredTabs.has(tab), `${tab} must not be a route legacyTab (the legacy renderer would overwrite the dedicated view)`);
  assert.equal(aliases[tab], alias, `old #${tab} hash must resolve to ${alias}`);
  const target = routes.find(route => route.path === alias);
  assert.ok(target && String(target.load).includes(loader), `${alias} must load ${loader}`);
}
assert.deepEqual([...new Set([...coveredTabs, ...Object.keys(dedicatedTabs)])].sort(), [...expectedTabs].sort(), 'every existing admin tab must stay reachable: through a legacyTab route or an alias to its dedicated view');
assert.ok(routes.some(route => route.path === '/people/users/:id'), 'user detail route must use the frozen path');
for (const route of routes) {
  assert.ok(route.path.startsWith('/') && !route.path.startsWith('/admin/') && !route.path.includes('#'), `route ${route.path} must be an internal hash path, not a server path`);
  assert.equal(typeof route.load, 'function');
  const view = (await route.load()).default;
  assert.equal(typeof view.mount, 'function', `${route.path} must export mount()`);
  assert.equal(typeof view.unmount, 'function', `${route.path} must export unmount()`);
}

assert.deepEqual(Object.keys(ACCOUNT_STATUS_SEVERITY), [
  'ACTIVE', 'FLAGGED', 'SUSPENDED', 'BANNED', 'DELETION_PENDING'
]);
const user = { id: 5, email: 'person@example.test' };
assert.equal(deriveAccountStatus(user), 'ACTIVE');
assert.equal(deriveAccountStatus({ ...user, flagged: true }), 'FLAGGED');
assert.equal(deriveAccountStatus({ ...user, archived: true }), 'SUSPENDED');
assert.equal(deriveAccountStatus(user, { bannedEmails: [{ email: 'PERSON@example.test' }] }), 'BANNED');
assert.equal(deriveAccountStatus(user, { deletionRequests: [{ id: 5, email: user.email }] }), 'DELETION_PENDING');
assert.equal(deriveAccountStatus({ ...user, flagged: true, archived: true }, {
  bannedEmails: [{ email: user.email }], selfDeletionRequests: [{ id: 5 }]
}), 'DELETION_PENDING', 'most severe active status must win (self-deletion-requests ids are user ids)');
assert.ok(compareAccountStatus('ACTIVE', 'FLAGGED') < 0);
assert.ok(compareAccountStatus('BANNED', 'DELETION_PENDING') < 0);

for (const name of ['modal', 'drawer', 'toast', 'confirm', 'table', 'statusBadge', 'empty']) {
  assert.equal(typeof ui[name], 'function', `ui.${name} must exist`);
}
const badges = createBadgeStore();
badges.set('people.deletions', 3);
assert.equal(badges.get('people.deletions'), 3);
badges.set('people.deletions', -1);
assert.equal(badges.get('people.deletions'), 0);
badges.destroy();

const endpointPaths = contract.endpoints.map(endpoint => endpoint.path);
assert.ok(endpointPaths.includes('/api/admin/summary'));
assert.ok(endpointPaths.includes('/api/admin/users/{id}'));
assert.ok(contract.statusModel.valuesInSeverityOrder.includes('DELETION_PENDING'));

console.log('Admin contract checks passed: 16 sidebar routes plus user detail covering all 10 legacy tabs, module signatures, UI API, status precedence, badge store, and API contract JSON.');
