# Phase 5 stabilization - Study, Configuration, Reporting (claude2)

Source of truth: `AI-Adaptive-Repo-phase4-integrated-2026-10-24.zip`. Regression testing and demonstrated-defect fixes only.

## Environment and honesty statement
- Node + `jsdom` + `playwright@1.56.0`, local Chromium. **All browser tests used a mocked API.** No Spring backend, no authenticated session, no live persistence, and nothing was run against the live Study. Study data semantics were not touched.
- Print: `smoke-report-reliability` uses Chromium print emulation and PDF generation (existing check, 20/20, re-run). I did not add new print tests.

## Commands and results (fixed tree)
Unit / jsdom: test-study-views 143 assertions, test-report-reliability 140, test-system-configuration 350, test-admin-contracts, test-admin-shell 242, test-admin-command-palette 174: all pass. (All 13 `scripts/test-*.mjs` pass.)
Browser (`--require-browser`): smoke-study-views 23/23 (baseline 18/21; 2 expectations corrected, 2 checks added), smoke-report-reliability 20/20, smoke-admin-shell 51/51, smoke-admin-command-palette 44/44. Stability: smoke-study-views and smoke-report-reliability 8/8 consecutive runs each.

## `smoke-study-views` baseline failures (3) - source evidence
All three were OUTDATED EXPECTATIONS, not product defects:
1. Participants and 2. Data exports expected `document.activeElement` to be `#participantsCard` / `#printExclude`. The integrated registry deliberately has no `after.focus` for these routes (`test-admin-shell` asserts `!after.focus`), and the shell never moves focus on the initial load. Assertions now check the current contract: no focus steal on load, and `#adminPageTitle` focus after in-app navigation to each standalone Study route.
3. "Legacy Platform report still renders its own participants card" - the Platform Report is now a dedicated view, and `test-report-reliability` forbids the Study participants card there. Now asserts `#participantsCard` count 0, `#printExclude` 1, and focus on `#printTarget` (route `after.focus`). No assertion was removed.

## Defect found and fixed: legacy code overwrote Assigned handouts
- `/study/assigned-handouts` still had `legacyTab: "assignedhandouts"`, so the shell set `currentTab` to it and `setCategoryRange()` -> `renderCurrentTab()` re-ran the legacy `renderAssignedHandouts()` over the dedicated view (observed in Chromium: extra participants/handouts GETs and replaced DOM).
- Fix in `admin/routes.js`: removed that `legacyTab` (same rule the contract test already applied to `reports`). Every canonical route and every legacy alias is unchanged; `#assignedhandouts` still resolves to `/study/assigned-handouts`. `test-admin-contracts` now lists `assignedhandouts` under dedicated tabs; `test-report-reliability` expects `legacyTab` undefined for it.
- New Chromium check runs `setCategoryRange` on Participants, Data exports, Assigned handouts, Platform Report and Reliability and asserts the mounted view is not replaced and no legacy renderer fetches data. It fails on the original registry (Assigned handouts) and passes now.

## Test-harness fix
`smoke-study-views.mjs` and `smoke-report-reliability.mjs` used the same static-server pattern (`writeHead(200)` before an awaited read) that crashed People smokes with `ERR_HTTP_HEADERS_SENT` when an in-flight `/api` request outlived its mock route. Hardened identically. Neither crashed at baseline in my runs; this is preventive, not a demonstrated failure.

## Required checks
1. All Study, Platform Report, Reliability, Configuration, route/contract tests run: see above.
2. Study smoke failures: classified above.
3. Route-to-module mappings: asserted by `test-report-reliability`/`test-admin-contracts`; legacy overwrite defect fixed; Participants, Data exports, Platform Report, Reliability, Configuration have no legacyTab.
4. Platform Report content/filters/Copy/print structure: covered by existing tests and print-emulation smoke (passing). Not re-derived independently.
5. Configuration: verified by reading `configuration.js`/`configuration-model.js` and the 350-assertion test: editable keys/types validated, stored row re-read before PUT (stale refusal), "saved" shown only after reload, 404 never triggers create. Not tested against a real backend or live persistence.
6. Unsupported settings read-only; the only write is `PUT /api/admin/configuration/{key}` (the single `method:` in the module); the test asserts no create/delete.
7. Reliability: export-only, makes no API requests, link is `/api/admin/export/reliability.csv`.
8. Loading/empty/error/retry/unmount for Study pages and Configuration: relied on existing tests (test-study-views covers Retry, abort and unmount; test-system-configuration covers lifecycle). I added no new probes here.

## Unresolved
- `smoke-admin-overview` ("inbox item navigates through the router") fails intermittently, 4 of 6 runs on the ORIGINAL tree as well; Overview is out of scope. Not caused by this change.
- Check 8 and Platform Report print/filter behaviour were not independently re-probed beyond existing tests.

## Files changed
`src/main/resources/static/admin/routes.js`, `scripts/test-admin-contracts.mjs`, `scripts/test-report-reliability.mjs`, `scripts/smoke-study-views.mjs`, `scripts/smoke-report-reliability.mjs`.
