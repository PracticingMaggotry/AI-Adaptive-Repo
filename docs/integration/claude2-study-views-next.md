# Study views separation: integration notes

Assignment: split the Study section into three independent views. **`admin/routes.js` was not touched or included.**
The routes are **NOT integrated** until the mappings in section 4 are applied to Claude 3's `routes.js`.

## 1. Changed and new files (all under `src/main/resources/static/admin/views/` unless noted)

| File | Status | Purpose |
|---|---|---|
| `views/study-participants.js` | new | `/study/participants` |
| `views/assignedhandouts.js` | **replaced** (same path) | `/study/assigned-handouts`; was a transitional adapter calling global `renderAssignedHandouts()` |
| `views/study-data-exports.js` | new | `/study/data-exports` |
| `views/study-common.js` | new | Study-only helpers (DOM builder, lifecycle, API wrapper, status/error blocks, CSV download) |
| `scripts/test-study-views.mjs` | new | jsdom unit tests (needs `jsdom`, not a repo dependency) |
| `scripts/smoke-study-views.mjs` | new | Playwright acceptance test through the real `admin.html`; exits 2 until routes are integrated |
| `INTEGRATION-NOTES.md` | new | this file |

Not modified: `routes.js`, `router.js`, `admin.html`, `admin.js`, `ui.js`, `legacy/*`, `views/reports.js`, Overview/People modules. No backend, schema or migration changes; no new endpoints or framework.
(ZIP paths are repo-relative: `src/main/resources/static/admin/views/...` and `scripts/...`.)

## 2. Existing API endpoints and functions retained

| View | Endpoints (unchanged request/response shapes) | Legacy source it was extracted from |
|---|---|---|
| Participants | `GET/POST /api/admin/participants`, `POST /api/admin/participants/create-accounts`, `GET /api/admin/participants/mapping.csv` (plain link), `DELETE /api/admin/participants?confirm=DELETE` | `legacy/reports.js`: `renderReports` card, `loadParticipants`, `enrollParticipants`, `createStudyAccounts`, `wipeParticipantKey` |
| Assigned handouts | `GET /api/admin/participants` (picker), `GET/POST /api/admin/assigned-handouts` (multipart: `topic`, `file`, repeated `emails`), `POST .../{id}/reset`, `DELETE .../{id}` | `legacy/assignedhandouts.js`: `renderAssignedHandouts`, `loadAhParticipants`, `ahAssign`, `loadAhList`, `ahReset`, `ahConfirmDelete` |
| Data exports | plain links to `/api/admin/export/{attempts.csv, attempt_questions.csv, question_exposures.csv, reliability.csv, sus.csv, review_questions.csv?perHandout=10, review_sources.txt?perHandout=10}`; one informational `GET /api/admin/participants` | export toolbar of `renderReports` |

All authorization stays server-side (every controller checks the admin session). The views use `ctx.api` / `ctx.ui` only; the one optional global is `refreshActivityLog()` (see 5).

## 3. Workflow preserved by each view

- **Participants:** enrol by e-mail list (same split regex and body, server skip messages shown), create 1-50 accounts (same body; same `study_accounts.csv` format, byte for byte), consent shown per participant (current consent version), identity-key CSV download, identity-key deletion.
- **Assigned handouts:** upload + assign to selected participants (same validation messages), list with Pending/Processed status, Reset (processed only), Delete with confirmation. Added: pending/processed counts derived from the existing list.
- **Data exports:** every legacy export link with its label and exact URL; the review sample keeps `perHandout=10`. No export content changed.

Deliberate small differences (UI only, same endpoints):
1. Delete identity key uses `ui.confirm` (destructive, type `DELETE`) instead of native `confirm()`; text explains codes are removed. Server still requires `confirm=DELETE`.
2. Create-accounts count is an inline 1-50 field instead of `prompt()`; the server still validates. If the response arrives after navigating away, the CSV is still downloaded (passwords are shown once). A "Download credentials again" button is kept only while the page is mounted; the CSV is dropped on unmount.
3. The participant list shows code + consent only (as the legacy card did), no e-mail addresses.
4. Errors/empty/loading states are inline (with Retry) rather than `showInfoModal`; a non-array response is an error, not "no participants".
5. After enrol / create / delete-key / assign / delete, `refreshActivityLog()` is called if present (legacy only did this for handouts) because the Activity Log renders from a cache.
6. Handout empty-picker text now points to Study > Participants.

## 4. Route-to-module mappings to apply to Claude 3's `admin/routes.js`

Add to `viewLoaders`:
```js
studyParticipants: () => import("./views/study-participants.js"),
studyDataExports: () => import("./views/study-data-exports.js"),
```
Replace the two records (drop `legacyTab`, so legacy `renderCurrentTab()` can never re-render Reports over them):
```js
{ path: "/study/participants", section: "study", title: "Participants", status: "ready", load: viewLoaders.studyParticipants, after: { focus: "#participantsCard" } },
{ path: "/study/data-exports", section: "study", title: "Data exports", status: "ready", load: viewLoaders.studyDataExports, after: { focus: "#printExclude" } },
```
`/study/assigned-handouts` needs **no change**: module path `./views/assignedhandouts.js` is the same. Keep its `legacyTab: "assignedhandouts"` because `test-admin-contracts.mjs` requires every legacy tab to stay covered; nothing in the new view triggers a legacy re-render.

| Route | Module | Replaces | Params/metadata |
|---|---|---|---|
| `/study/participants` | `views/study-participants.js` | `views/reports.js` + `after.focus` on `#participantsCard` | none; keeps focus id `#participantsCard` |
| `/study/assigned-handouts` | `views/assignedhandouts.js` (unchanged path) | legacy adapter to `renderAssignedHandouts` | none; alias `#assignedhandouts` unchanged |
| `/study/data-exports` | `views/study-data-exports.js` | `views/reports.js` + `after.focus` on `#printExclude` | none; keeps focus id `#printExclude` |

`/system/reliability` and `/audit/platform-report` keep `views/reports.js` unchanged.

Test updates needed with the integration (Claude 3's tests): `scripts/smoke-admin-shell.mjs` line ~167 asserts all three ids `#participantsCard, #printExclude, #printTarget` exist on the Study routes; it now sees 1. Restrict that count to `/audit/platform-report`. Optional polish: drop `after.focus` on the two Study routes so the page heading gets focus (then adjust `test-admin-shell.mjs` lines 51-52 and the smoke focus map).

## 5. What cannot be separated safely without a later change

- `legacy/reports.js` still contains the participants card and export toolbar. They were **copied, not removed**: `/system/reliability` focuses the Reliability CSV link in that toolbar, and the smoke test expects the three ids on `/audit/platform-report`. Until that route owner changes this, enrolment and exports are reachable from both places (same APIs, same data).
- The dev harness `admin-dev.html` lists legacy tab names only; it can load the new Assigned handouts view, not the two new modules.
- Study views rely on `refreshActivityLog` being a global from `admin.js`; without it nothing breaks but the Activity Log cache is not refreshed.

## 6. Verification

Run on a scratch tree: base repo + `phase1-shared-ui` + `phase1-hardening` + `claude3-admin-shell-router` + these files, with the section-4 edits applied to a scratch copy of `routes.js` (not delivered).

| Check | Result |
|---|---|
| `node --check` on all four modules and both scripts | passed |
| `test-study-views.mjs` (jsdom, mocked API, real hardened `ui.js`; `ui.confirm` stubbed) | 143 assertions passed. Mutation-checked: unbound listeners and a changed export URL make it fail |
| Claude 3 `test-admin-contracts.mjs`, `test-admin-shell.mjs` with the mappings | passed (239 checks) |
| Claude 3 `smoke-admin-shell.mjs` with the mappings | 49/51; the only 2 failures are the stale duplicate-id count described in section 4. With Claude 3's own `routes.js` it is 51/51 |
| `smoke-study-views.mjs` (headless Chromium, real `admin.html`, mocked stateful API) | 21/21 with mappings applied; exits 2 with Claude 3's current `routes.js` |

Not tested: the real Spring backend, real authentication/authorization, real CSV contents, real file uploads of large size, other browsers, the original `ui.js` (pre-Phase-1) rendering. Which `ui.js` is deployed is assumed to be the Phase 1 hardened one; the views use only `confirm`, `toast`, `empty`, `table`, whose signatures are identical in all versions.

**Status: views verified independently and, on a scratch tree, through the real shell with the proposed mappings. They are not integrated in the delivered code.**
