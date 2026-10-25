# Admin Migration Audit

**Audit date:** 2026-10-21  
**Audited source:** uploaded `AI-Adaptive-Repo-step0.zip` compared with uploaded `AI-Adaptive-Repo-main.zip`.  
**Purpose:** establish a trustworthy baseline and bounded next tasks before three Claude Free accounts continue the migration.

## Executive result

Step 0 is a **transitional extraction**, not a completed modular admin migration. The old admin panel remains the production implementation. The new route manifest and view interfaces exist, but ten of the eleven view modules are adapters that call global legacy renderers. Only `user-detail.js` has an independent renderer. The backend has not implemented the new endpoint contracts.

Do not deploy this as the final migration. It is a reasonable development baseline if the transition is completed incrementally and the legacy screen stays available until parity is demonstrated.

## Verified frontend findings

- The existing panel has ten tabs: `overview`, `users`, `topics`, `bannedemails`, `deletionrequests`, `reports`, `activitylog`, `aitesting`, `reportedquestions`, and `assignedhandouts`.
- Original JavaScript function-declaration name inventory: **115** names. The combined `admin.js`, `admin/shared.js`, and `admin/legacy/*.js` inventory contains the same **115** names, with no missing or duplicate function names in the static comparison.
- The simple line-start scan of `let`/`const`/`var` declarations finds **358** declaration entries in the original script and the same **358** entries in the split files. This scan is an inventory check, not a scope-aware JavaScript AST comparison.
- `admin.html` loads the ten classic `admin/legacy/*.js` files, `admin/shared.js`, and `admin.js`. Shared state remains in the classic-script global lexical environment to preserve existing inline handlers and function references.
- `admin.js` contains the old `switchTab`, `renderCurrentTab`, `hashchange` handler, startup authentication check, and legacy shell logic. Its many blank lines are retained from the mechanical split; renderer bodies now live in the `admin/legacy` files.
- The `pagehide` and `beforeunload` upload-cancellation listeners are still present in `admin.js`, and `aitCancelActiveUpload()` remains in `admin/shared.js`. `admin/legacy/aitesting.js` still uses `aitUploadInProgress` and `aitActiveUploadId`. **Do not remove this compatibility path until a replacement AI Testing view has equivalent cleanup and has been tested.**
- The ten files under `admin/views/` (excluding `user-detail.js`) declare `mount` and `unmount`, but currently call `window.renderOverview`, `window.renderUsers`, etc. They require `#adminContent`, ignore `ctx`, and have no actual per-view cleanup. These are adapters, not standalone screens.
- `admin/views/user-detail.js` is a real independent view using safe `textContent` rendering and `ui.js`, but the production shell does not currently load it and its API endpoint is missing from the backend.
- `admin-dev.html` is not an isolated-view harness yet: it loads every legacy file plus `admin.js` so the transitional adapters work. The harness comment correctly discloses this limitation.
- `admin/routes.js` is imported by `admin-dev.html` only. Production `admin.html` does not import `routes.js`, `ui.js`, `account-status.js`, or `badges.js`, and it does not invoke `mount()`/`unmount()`.
- Current production navigation is hash-based (`admin.html#users`) via inline `onclick="switchTab(...)"`. It has not been migrated to the path routes in `routes.js`.
- Static route paths such as `/admin/users` and `/people/users/:id` are not served by a production SPA fallback today. `AuthInterceptor` explicitly lists `/admin.html` and `/admindashboard.html` as admin-only page paths; do not switch production to path-based URLs without a protected fallback/rewrite and refresh/deep-link authorization tests. Until then, keep the legacy page and hash navigation as the production route.

## Backend/API findings

The current backend uses Spring Boot controllers and session checks. `AuthInterceptor` gates the `.html` pages, while API controllers additionally check `isAdmin(session)`. Every new admin API must keep that server-side check; hiding controls in JavaScript is not authorization.

| Contract | Current implementation | Required work |
|---|---|---|
| `GET /api/admin/summary` | Does not exist. | Add an admin-guarded summary endpoint. Existing repositories already provide a pending question-report count; other counts should use current entities/repos. |
| `GET /api/admin/users?query=&status=&page=&size=` | `AdminController.listUsers()` exists but accepts no filters/pagination and returns `{success, users}`. User maps include `id`, `fullName`, `email`, `isAdmin`, `lastKnownIp`, `flagged`, `flagReason`, `archived`, and `archiveReason`; they do not expose the complete new contract. | Add query/status/page/size support and contract fields, while retaining legacy response fields during migration so `admin/legacy/users.js` does not break. |
| `GET /api/admin/users/{id}` | Does not exist. `/users/{id}/daily-usage` and limit subroutes exist. | Implement the detail response using existing `User`, `Material`, daily-usage and `AdminActivityLog` data. Avoid inventing historical status facts that are not persisted. |
| `GET /api/admin/activity-log?targetEmail=&type=&page=` | Exists with no filters/page metadata and returns `{success, log}` limited to 200 entries. `AdminActivityLog` has `type`, `message`, `detail`, `performedBy`, and `timestamp`, but no structured `targetEmail` field. | Preserve `log` for the legacy UI while adding `items`, `total`, and `page`. Be explicit that older log events may not support reliable target-email filtering unless target data can be grounded or a nullable structured field is added. |

### Summary count semantics for the first backend task

- `pendingReports`: count of `QuestionReport` rows whose status is `PENDING` (existing repository method `countPending()`).
- `deletionVotesNeeded`: number of **admin-initiated** deletion requests still `PENDING` and lacking at least one required valid current-admin approval; this is a count of requests needing votes, not the total number of missing individual votes. Do not mix these with self-deletion requests.
- `selfDeletionRequests`: count of users for whom `User.isDeletionRequested()` is true (one-admin review flow).
- `flaggedUsers`: count of users whose `User.isFlagged()` is true.
- `pendingHandouts`: count of assigned-handout rows whose status is `PENDING`.

`deletionVotesNeeded` is a contract interpretation for implementation consistency. If its UI label changes later, preserve the JSON key and documented meaning unless all stream leads approve an additive/explicitly versioned contract change.

## Required semantic safeguards

- The UI term **suspend** maps to the existing `archived`/`archive-user` backend behavior for now. Do not rename the database field, silently drop compatibility properties, or break old request paths during the modular split.
- **Ban email** is distinct from suspending a user. It uses the existing `BannedEmail` repository and endpoints.
- User self-deletion (`/api/admin/self-deletion-requests`) is distinct from admin-initiated dual-control deletion (`/api/admin/deletion-requests`). Keep the two flows separate.
- `DELETION_PENDING` describes a row with an active request; **Deleted** is an audit event after the row is gone and is not a live account status.
- Account status precedence is frozen as `ACTIVE < FLAGGED < SUSPENDED < BANNED < DELETION_PENDING`.
- The status calculation must use original email when the user's visible `email` has been replaced for a self-deletion request.
- AI Testing's in-flight upload ID, cancel request, `pagehide`/`beforeunload` cleanup, upload-progress UI, question generation, and testing-preview behavior are existing functionality. Preserve these explicitly until replacement parity has been tested.
- Preserve all existing tab features, response aliases, forms, confirmation flows, and access checks. Add new API response properties without breaking the legacy properties used during migration.

## Checks run in this audit

- `node scripts/test-admin-contracts.mjs`: **passed**. This is a structural contract test; it does not verify independent view behavior.
- `node --check` across `admin.js`, all `admin/*.js`, all `admin/views/*.js`, all `admin/legacy/*.js`, and the `.mjs` test: **passed**.
- Static inventory of function and variable declaration names across original/split scripts: counts match (115 function-declaration names, 358 variable declaration entries). This does not prove every function body is identical or every path behaves identically at runtime.
- Maven tests: **not run**. `bash mvnw -o test` could not start because Maven Wrapper could not fetch Maven 3.9.12; this is an environment/network blocker, not a test failure result.
- A fresh headless Chromium check did not return DOM output within the environment timeout. Therefore this audit does **not** independently certify browser runtime behavior. The prior Step 0 smoke-test claim should be treated as a previous report, not a substitute for repeatable browser tests.

## Recommended migration order

1. Keep current production `admin.html` and hash navigation untouched.
2. Run the three bounded first tasks in `docs/claude-prompts/` (no shared files overlap).
3. Review all returned changes and merge them into one canonical working copy before issuing second tasks.
4. Replace view adapters incrementally, one view per task, testing with mock data and preserving each legacy renderer until parity passes.
5. Complete the user-list and activity-log API contracts while maintaining old JSON fields.
6. Only after standalone views have parity, create the production shell/router cutover and implement/test secure deep-link fallback if using path URLs.
7. Remove legacy scripts only after all dependent globals are gone and a complete regression pass succeeds.
