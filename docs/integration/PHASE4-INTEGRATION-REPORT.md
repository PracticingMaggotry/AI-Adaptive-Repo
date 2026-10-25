# Phase 4 Integration Report

**Integration date:** 2026-10-24  
**Baseline:** `AI-Adaptive-Repo-phase3-integrated-2026-10-24.zip`  
**Status:** Phase 4 patches merged; browser/backend verification remains outstanding.

## Changes integrated

### People: combined deletion-request badge

- Added `admin/people/deletion-badge.js` to combine actionable admin-initiated dual-control requests (pending and approved) with user-initiated self-deletion requests.
- Kept request IDs and account IDs as separate identity spaces, each deduplicated within its own workflow.
- The badge shows a combined count only when all three sources (pending, approved, self-requested) are valid. If a source fails, it keeps the last complete count as stale or shows `?` when no complete count has been established. Failed sources do not become false zeroes.
- Updated the legacy badge refresh entry point, Deletion requests view, self-deletion queue refresh hooks, and Overview's badge synchronization.
- Updated the Overview smoke fixture expectation from pending-only count `2` to combined count `5`.

### System: Configuration page

- Added `admin/views/configuration-model.js` and `admin/views/configuration.js`.
- The page reads existing configuration rows and current snapshot data, edits only registered/supported rows, validates values, confirms consequential changes, rechecks the stored row before PUT, and verifies values after reloading.
- Unknown or unsupported settings remain read-only. Unsupported defaults that have no existing row are not presented as editable. The UI reports the current limitation that updates are not written to the Activity Log.
- The existing API is reused; no backend endpoint or schema was added.
- Integration fix: route `/system/configuration` now has status `ready`, and the sidebar's stale `Not built` tag was removed. The command-palette search status and shell/config tests were updated to match the implemented page.

### Global navigation search

- Added a keyboard-accessible command palette (`Ctrl+K` on Windows/Linux, `Cmd+K` on macOS) with a top-bar button, route-registry-derived results, keyboard/pointer selection, Escape handling, focus restoration, and a Reload current page action.
- Results cover static pages and a user-detail route when the query supplies an ID; they do not query user/material/question/log/banned-email/deletion records or make search API requests.
- Integrated the palette into `admin.js` and `admin.html`. Existing route registry and router were not overwritten by this patch.

## Integration decisions and reconciliations

1. The Configuration route was marked `ready` and its static `Not built` sidebar tag removed. Leaving it `not-implemented` would misrepresent the implemented page and cause the palette to label it as unavailable.
2. Updated `scripts/test-admin-shell.mjs` to require the Configuration route's ready status and view lifecycle contract.
3. Updated `scripts/smoke-admin-shell.mjs` to provide configuration API fixtures and assert that the actual Configuration view renders and has no `Not built` tag.
4. Updated `scripts/test-system-configuration.mjs` to assert the final ready status, rather than permitting either status.
5. Updated `scripts/test-admin-command-palette.mjs` and `scripts/smoke-admin-command-palette.mjs` so Configuration has no `Not built` result tag.
6. Updated `scripts/test-report-reliability.mjs`, whose old assertion required Configuration to remain a placeholder.
7. Preserved Claude 1's modification to `scripts/smoke-admin-overview.mjs`, which expects the combined badge count and explicitly records `known` state.
8. Kept the existing Study-view smoke failures as known baseline issues; they were not relaxed or silently marked passing.
9. Preserved the three Claude `INTEGRATION-NOTES.md` files under `docs/integration/phase4/`.

## Local verification performed on the merged tree

### Passed

- Syntax check: 76 `.js`/`.mjs` files across `src/main/resources/static` and `scripts`, with zero syntax failures.
- Route loader file check: 17 route records, zero missing dynamic-import module files.
- `test-admin-command-palette.mjs`: 174 checks.
- `test-admin-contracts.mjs`: passed.
- `test-admin-overview.mjs`: 214 checks.
- `test-admin-shell.mjs`: 242 checks.
- `test-ai-testing-preview.mjs`: passed.
- `test-ai-testing-lifecycle.mjs`: 16 checks.
- `test-report-reliability.mjs`: 140 assertions.
- Configuration model/route checks: 219 checks passed before the view tests were skipped.
- Deletion badge pure/coordinator checks: 145 passed before DOM scenarios were skipped.
- People account-status checks: 28 passed before DOM scenarios were skipped.
- Self-deletion queue pure checks: 14 passed before DOM scenarios were skipped.

### Not independently verified in this environment

- The Configuration view interaction tests require `jsdom`, which is unavailable here. The script explicitly reports 219 model/route checks passed, then skips view tests and exits non-zero.
- Deletion badge DOM tests require `jsdom`, which is unavailable here. Only 145 pure/coordinator checks ran locally.
- People and self-deletion DOM scenarios require `jsdom`; only their pure checks ran locally.
- The Study view test is skipped because `jsdom` is unavailable; no Study DOM behavior was verified locally.
- All Playwright browser smoke scripts were skipped because the `playwright` package is unavailable in this environment. This includes the new badge and command-palette smoke suites.
- No Maven/Java tests, live Spring backend, authenticated session, live database writes, real configuration persistence, production redirects, real CSV export, real print dialog, screen-reader audit, or cross-browser verification was performed.

The per-Claude notes record their separate environment results, including claimed Chromium checks. Those results are preserved but were not independently reproduced against this merged archive.

## Remaining risks and next work

1. Run the complete browser suite in an environment with Playwright/Chromium and `jsdom`; specifically run the new badge, Configuration, and command-palette checks on the integrated tree.
2. Verify Configuration GET/PUT behavior against a test backend and confirm that only supported keys are writable, especially seeded-row limitations and post-save reload behavior.
3. Verify login/root/dashboard redirects in the real Spring/Tomcat stack for admin and non-admin sessions.
4. Resolve or explicitly quarantine the three known `smoke-study-views` failures, which prior runs reported on the baseline too. Do not loosen their assertions without proving the expected behavior.
5. Review the initial `?` badge accessibility and the real visual presentation of the combined count in a browser/screen reader.
6. Global search is navigation/action search, not full record search. Entity search would require a separately designed bounded backend API.

This archive is a consolidated development snapshot, not a production-verified release.
