# Authority note — admin redesign routing

The following target is now authoritative based on the original redesign request: the shell uses **hash routing** (`admin.html#/people/users`) with internal route paths like `/people/users`; do not use server paths such as `/admin/users`. The information architecture is Overview, People, Content, Study, System, and Audit. Read `docs/ADMIN-REDESIGN-BRIEF.md` for the complete grouping and phase plan. This note supersedes the older route examples below where they use `/admin/...` URLs or imply that route-module extraction alone constitutes the redesign. Preserve existing APIs and renderer behavior during the first shell phase.

---

# Admin Contract Freeze (Step 0)

**Scope:** establish a stable boundary for parallel admin work without changing the existing admin UI behavior during this first migration pass.

## Current repository findings

- Current page: `src/main/resources/static/admin.html`.
- Current tabs: `overview`, `users`, `topics`, `reportedquestions`, `bannedemails`, `deletionrequests`, `reports`, `activitylog`, `assignedhandouts`, `aitesting`.
- The inline script was extracted into `src/main/resources/static/admin.js`. Its shared state, startup logic, tab switcher, and bootstrap remain there; tab-specific top-level functions were moved into `admin/legacy/<tab>.js`, with shared functions in `admin/shared.js`. The code remains classic scripts for now because generated HTML uses inline `onclick` handlers and global functions. The only production-safe harness gates allow seed data through `window.__adminDevSeedData` and skip `initAdmin()` only when `window.__ADMIN_DEV__` is true; the production page does not set that flag.
- Existing navigation still uses `switchTab()` and hash fragments. `admin/routes.js` is the planned route manifest; wiring a new shell router is a subsequent cutover, not silently included in this behavior-preservation pass.
- The current backend already has `/api/admin/users`, `/api/admin/overview`, `/api/admin/topics`, `/api/admin/activity-log`, `/api/admin/banned-emails`, `/api/admin/self-deletion-requests`, `/api/admin/deletion-requests`, and other tab-specific endpoints. See `contracts/api-contracts.json` for whether each requested new shape is present, absent, or requires expansion.

## View module contract

Every view exports a default object:

```js
export default {
  mount(el, ctx) {},
  unmount() {}
};
```

`ctx = { params, query, api, ui, router, session, badges }`.

- `mount(el, ctx)` renders into the supplied element. It may return a promise; callers must await it.
- `unmount()` must remove event listeners, observers, timers, subscriptions, and generated object URLs. It should be idempotent.
- `ctx.api` is the view-facing API client; views must not use global `fetch()` directly.
- `ctx.router.navigate(path)` is the view-facing navigation boundary.
- `ctx.badges.set(key, count)` publishes sidebar counts.

**Transitional status:** current `admin/views/*.js` files are ES-module adapters to each tab's legacy renderer. The renderer functions are now physically split into tab-owned classic-script files, so the file ownership and review boundaries are established without rewriting their behavior. The view modules satisfy the import and mount contract, but do not yet own all implementation code, and `unmount()` cannot clean up legacy page-level listeners. Streams can now migrate one tab at a time into its ES module, then remove that tab's classic compatibility functions only after regression checks.

## Route manifest

`admin/routes.js` exports route records in the shape `{ path, section, title, legacyTab, load }`. It includes one route per existing tab plus the planned `/people/users/:id` user-detail route. The new user-detail ES module is already independent and reads the agreed detail endpoint. The live `admin.html` still uses hash navigation until the shell migration is separately tested.

## UI component contracts

Implemented in `admin/ui.js` using native DOM and `<dialog>` primitives:

- `ui.modal({ title, body, actions, size }) -> Promise<result | null>`; result is `{ action }`.
- `ui.drawer({ title, content, onClose }) -> controller` with `element`, `close()`, and `destroy()`.
- `ui.toast(msg, { type, undo })` displays a status toast and optional Undo control.
- `ui.confirm({ tier, title, message, impact, typeToConfirm, reasonRequired }) -> Promise<{ confirmed, reason }>`.
- `ui.table({ columns, fetch, rowKey, onRowClick, selectable }) -> controller` with `element`, `reload()`, `destroy()`, `getSelected()`, and `getRows()`.
- `ui.statusBadge(accountStatus) -> HTMLElement`.
- `ui.empty({ title, action }) -> HTMLElement`.
- `createBadgeStore()` from `admin/badges.js` supports `set(key, count)` and subscriptions.

The UI stub is deliberately generic. Consequential actions still require backend authorization and validation; a confirm dialog is not a security boundary.

## Account status model

Canonical field: `accountStatus`. Severity order: `ACTIVE < FLAGGED < SUSPENDED < BANNED < DELETION_PENDING`. The highest applicable state wins.

- `ACTIVE`: no stronger state applies.
- `FLAGGED`: requires review, but does not by itself suspend account access.
- `SUSPENDED`: account access is temporarily restricted. Legacy backend field `archived` currently maps to this status; user-facing terminology should be “suspend”, not “archive”.
- `BANNED`: email is on the banned-email list. The current backend stores banned email separately from user state.
- `DELETION_PENDING`: a live account deletion request is pending. A historical deletion log entry does not create an account row and must not be treated as a current status for a row that no longer exists.

`admin/account-status.js` contains the shared status constants, severity order, derivation, and comparison helpers. The backend still needs to provide a consistent canonical field in its new list/detail responses.

## API contracts

Machine-readable response shapes are in `admin/contracts/api-contracts.json`.

Known compatibility differences:

- Existing `GET /api/admin/users` returns `{ success, users }` with `id`, `fullName`, `email`, `isAdmin`, `lastKnownIp`, `flagged`, `flagReason`, `archived`, and `archiveReason`. It does not currently support the requested filters/pagination and does not return `createdAt` or `accountStatus`.
- Existing `GET /api/admin/users/{id}` detail endpoint is absent. Daily usage/limit endpoints are present under that prefix but are not account-detail endpoints.
- Existing `GET /api/admin/activity-log` returns `{ success, log }` and is capped at 200. It does not currently support `targetEmail`, `type`, or `page` filtering; it also does not return `targetEmail` in the projected rows today.
- `GET /api/admin/summary` is new. It can aggregate existing reports, deletion, flag, and assigned-handout sources, but each count's exact business definition needs to be approved by the API owner.

Pagination convention for the planned list endpoints: `page` is 1-based (default 1); `size` defaults to 25 and caps at 100; `query` is a case-insensitive search on full name and email; empty `status` means all canonical statuses. Keep authorization checks in the backend controllers.

## CSS tokens and terminology

Shared CSS tokens are in `admin/admin-tokens.css` and are not yet globally applied to avoid changing the visual design in the extraction step.

Glossary:

- **Suspend / Suspended**: temporary access restriction (legacy backend APIs still use `/archive-user` and `/unarchive-user`; do not rename/remove those endpoints until a compatibility plan is approved).
- **Ban email / Banned**: block the email from use; it is separate from suspension.
- **Request deletion / Deletion pending**: a request awaiting its approval workflow.
- **Deleted**: an audit/log event only once the user row has been removed; do not show it as an account status on a missing row.
- **Flagged**: marked for review, not automatically restricted.

## Dev harness

Open `admin-dev.html` through the Spring Boot static server (not `file://`) and choose a view; the “User Detail (new contract)” option exercises the new endpoint shape. It loads fake data, a mock `apiFetch`, the current renderer definitions, and the view module's `mount()` entry point. It must not be used as an authenticated or production page. External chart/math/highlight assets are retained as CDN dependencies because the existing admin screen uses them.

## Stream ownership proposal

- **Stream A — Shell/contracts:** `admin/routes.js`, `admin/badges.js`, route/shell coordination; do not change view internals without the owning stream's approval. Own the Overview implementation in `admin/views/overview.js` and `admin/legacy/overview.js`, unless assigned separately.
- **Stream B — Shared UI:** `admin/ui.js`, `admin/admin-tokens.css`; owns the public component signatures.
- **Stream C — People:** `admin/views/users.js`, `admin/legacy/users.js`, `admin/views/user-detail.js`, and (with contract-owner approval) `admin/account-status.js`; owns list/detail UX.
- **Stream D — Content:** the matching module and compatibility files for Topics, Assigned Handouts, and AI Testing: `admin/views/{topics,assignedhandouts,aitesting}.js` and `admin/legacy/{topics,assignedhandouts,aitesting}.js`.
- **Stream E — Reports and review:** the matching module and compatibility files for Reports, Reported Questions, and Activity Log.
- **Stream F — Account controls:** the matching module and compatibility files for Banned Emails and Deletion Requests.
- **Stream G — QA/integration:** `admin-dev.html`, `admin/mock-api.js`, `scripts/test-admin-contracts.mjs`, regression checklist. QA should report defects rather than independently rewriting another stream's code.

Avoid concurrent edits to `admin.js` and `admin.html`. Until each legacy renderer has been migrated out, streams can replace only the adapter owned by their stream, and must coordinate before deleting shared global functions.

## Verification checklist

- Open `admin.html` in an authenticated environment and verify all 10 tabs still render using the same hash navigation.
- Verify admin access denial and 401 redirect still work.
- Verify uploads in AI Testing still register `pagehide`, `beforeunload`, sidebar-navigation cancellation, and `/api/materials/cancel-upload` cleanup.
- Verify modal, category editing, banned-email changes, account suspend/restore, deletion approval, reported-question review, handout assignment/reset, and user usage/limit editing.
- Open `admin-dev.html?view=users` (replace with any supported `view`) and confirm the mock data loads with no backend write requests.
- Run `node --check` for classic scripts and `node --experimental-default-type=module scripts/test-admin-contracts.mjs` for the route/module/status checks. Run `bash mvnw test` where Maven dependencies are available.
- Do not call the view migration complete until browser-level smoke tests are performed; a static syntax check is not proof of behavioral equivalence.

## Freeze rule

After approval, changes to names, signatures, status values, route shape, API response shape, CSS token names, or glossary terms are additive only and require explicit approval from all affected stream leads.

## Implementation and verification record

This is a Step 0 foundation, not a completed rewrite of the admin shell.

Verified in the working copy:

- `scripts/test-admin-contracts.mjs` passes under `node --experimental-default-type=module`; it checks the ten tab routes plus User Detail, required module signatures, UI surface, status ordering, badge store, contract JSON, referenced local scripts, and that production `admin.html` does not load the mock API.
- Classic JavaScript files pass `node --check`; ES modules pass `node --check --input-type=module`; the API contract file passes JSON parsing.
- All local script paths referenced by `admin.html` and `admin-dev.html` exist.
- A headless Chromium smoke test using the original admin markup and fake responses rendered all ten existing tab views without page errors or console errors. This was not an authenticated backend test.

Not verified / still required:

- The Maven test suite could not be run because the Maven wrapper could not download its Maven distribution in the environment (network resolution/download failure).
- The harness has not been verified against a live Spring Boot server in this environment.
- `GET /api/admin/summary`, the account-detail endpoint `GET /api/admin/users/{id}`, and the requested list/detail response-shape expansions are contract definitions/mock responses only; backend implementation remains future work.
- The current admin shell still uses its existing hash-based navigation. `routes.js` is a shared route manifest for the cutover, not yet the production router.
- The existing renderers have been separated into tab-owned compatibility scripts, with ES-module adapters exposing the frozen interface. They are not yet fully self-contained ES modules, and their legacy cleanup is not yet implemented. Each stream should migrate its owned renderer into the corresponding ES module and test it before removing compatibility code.
- No changes have been pushed to GitHub. This delivery is a modified project copy for review.
