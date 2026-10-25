# Phase 5 stabilization - People and deletion workflows (claude1)

Source of truth: `AI-Adaptive-Repo-phase4-integrated-2026-10-24.zip`. Stabilization only; no new features, no API or deletion-semantics changes.

## Environment and honesty statement
- Node + `jsdom` + `playwright@1.56.0`, local Chromium (`PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers`). Run from the repo root.
- **Every browser test used a mocked API.** No Spring backend, no authenticated session, no live data, no screen reader. Backend response shapes were checked by reading `AdminController.java`, not by calling it.
- "Unit" = Node/jsdom (not a real browser). "Browser" = Playwright/Chromium against mocked routes.

## Commands and results (fixed tree)
Unit / jsdom (`node scripts/<name>.mjs`): all pass.
- test-deletion-badge: 28/28 scenarios, 262 assertions (baseline 18 scenarios; +10 added)
- test-self-deletion-queue: 14/14, 88 assertions
- test-people-users: 62 assertions, 13 DOM scenarios
- test-admin-contracts, test-admin-shell (242), test-admin-overview (214), test-admin-ui-hardening, test-admin-command-palette (174): pass
Browser (`node scripts/<name>.mjs --require-browser`):
- smoke-deletion-badge 14/14, smoke-people-users 15/15, smoke-self-deletion-queue 11/11 (baseline: crashed intermittently; +2 checks added)
- Stability on the fixed tree: 12 consecutive runs each of the three People smokes, 36/36 passed. Baseline: self-deletion-queue and people-users each crashed 1 in 8 runs.

## Intermittent People smoke failure: TEST-HARNESS issue (timing-triggered), not a product defect
- Signature: `ERR_HTTP_HEADERS_SENT` thrown from the smoke's static file server; process dies.
- Mechanism: the server did `res.writeHead(200).end(await readFile(file))`; a failed read then ran `writeHead(404)` on an already-started response, throwing inside the async handler. The failing reads were `/api/admin/...` badge refreshes (PENDING, APPROVED, self-deletion-requests) that were still in flight when `context.close()` removed the Playwright mock route, so they fell through to the file server.
- Evidence: instrumented copies logged each failing read; 7 of 7 leaked requests occurred with the context closing. The app behaviour (a badge refresh after the last action) is normal.
- Fix: read before writing headers; answer stray `/api/*` with 503. Same fix applied to the two People smokes. (Same pattern exists in other smokes outside this scope; claude2 notes cover the Study/Report ones.)
- Limit: a race is only evidenced statistically (36/36 vs 2/16), plus the mechanism above.

## Defects found and fixed (each failed a new test on the Phase 4 tree)
1. `legacy/deletionrequests.js` filter race: a slow response for an earlier status filter overwrote the current list (All filter showed only APPROVED rows). Now latest-call-wins.
2. Duplicate actions: double click on Approve/Reject/Cancel sent two POSTs; the second produced a false "already voted"/"no longer pending" error. Per-request in-flight guard (Execute included).
3. Stale actions (server 400/404 because another admin acted): the dead button and the badge stayed. Now the list and badge are re-read. 5xx/403/network errors still do NOT trigger a refresh (existing test kept).
4. Malformed 200 (not `{requests:[...]}`, `success:false`, or non-JSON such as an HTML login page) was shown as "No ... requests." Now an `role="alert"` error, consistent with the badge's rule.
5. `people/deletion-badge.js`: after a known ZERO, a failed round hid the badge (looked like a confirmed zero). Now shows `?` with `data-badge-state="stale"` and the existing tooltip; a complete round restores the real zero. The shell store is still never fed a number by a failed round.
6. `people/self-deletion-queue.js`: after a stale outcome the reload removed the focused row and focus fell to `<body>`. Now moves to the queue heading.
7. Dual-control Reject/Execute confirmations use the legacy `#modal` host: no dialog role, focus stayed behind the overlay, Escape ignored, Tab escaped into the page (observed in Chromium). `enhanceDeletionModal` (local to `legacy/deletionrequests.js`) adds role/aria-modal/label, initial focus (note field; Cancel for Execute), Tab trap, Escape (ignored while an execute is in flight), focus return to the opener.

## Required checks
1. Badge ID spaces: verified (pending+approved dual-control IDs and self-deletion account IDs are counted from separate sources; existing + new tests).
2. Failed/malformed source never yields a false zero or partial count: verified for pending, approved and self sources (fail, malformed, text, success:false); defect 5 closed the last gap.
3. Refresh after success/restore/vote/cancel: verified in existing tests plus new stale-path tests.
4. Duplicates, stale, restore conflicts, empty queues, API failures: new/existing jsdom scenarios; double-click and keyboard in Chromium.
5. Intermittent smoke: see above.
6. Keyboard/focus: native `ui.confirm` dialog verified in Chromium (focus in, Tab trapped, Escape closes, focus restored). Legacy modal fixed (defect 7). The user-detail drawer relies on shared `ui.js` behaviour covered by `test-admin-ui-hardening`; I did not add new drawer probes. Not tested with a screen reader.

## Not done / unresolved
- User listing filtering and pagination and the drawer were not probed beyond the existing 62 assertions and 15 browser checks.
- Legacy `showModal`/`showInfoModal` in `admin/shared.js` (used by other tabs) still lacks dialog semantics. Out of scope; the People flows are covered locally. A shared fix there would be the proper long-term solution.
- `smoke-admin-overview` ("inbox item navigates through the router") fails intermittently (4 of 6 runs on the ORIGINAL tree too; it read heading 'Overview' instead of 'Reported questions'). Overview is out of scope; not investigated further, likely timing in the test. Not caused by this change.

## Files changed
`admin/legacy/deletionrequests.js`, `admin/people/deletion-badge.js`, `admin/people/self-deletion-queue.js` (all under `src/main/resources/static/`), `scripts/test-deletion-badge.mjs`, `scripts/smoke-self-deletion-queue.mjs`, `scripts/smoke-people-users.mjs`.
