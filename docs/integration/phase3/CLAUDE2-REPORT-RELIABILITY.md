# Dedicated Platform report and Reliability views

Base: `AI-Adaptive-Repo-current-integrated-2026-10-23.zip`. Paths in this ZIP are relative to the repository root
(the `AI-Adaptive-Repo-main/` folder inside the base ZIP).

## Files

| File | Change |
|---|---|
| `src/main/resources/static/admin/routes.js` | modified (owned by this task) |
| `src/main/resources/static/admin/views/platform-report.js` | new: dedicated Audit > Platform report view |
| `src/main/resources/static/admin/views/platform-report-model.js` | new: pure data model (endpoints, derivations, labels); Node-testable |
| `src/main/resources/static/admin/views/reliability.js` | new: export-only Reliability view |
| `src/main/resources/static/admin/views/view-dom.js` | new: tiny DOM builder + listener lifecycle for the two views |
| `scripts/test-admin-contracts.mjs` | modified: legacy-tab coverage assertion (see below) |
| `scripts/test-report-reliability.mjs` | new: Node tests (140 assertions) |
| `scripts/smoke-report-reliability.mjs` | new: Chromium/Playwright checks (20) with mocked API |

Not touched: `admin.html`, `admin.js`, `admin/router.js`, `ui.js`, Overview, People views, the three Study views,
`admin/legacy/*`, `admin/views/reports.js`, `shared.js`, the shell test files `test-admin-shell.mjs` / `smoke-admin-shell.mjs`
(verified with `diff -rq` against the base ZIP: only the files above differ).

## Route changes (`routes.js`)

* New loaders: `platformReport` -> `views/platform-report.js`, `reliability` -> `views/reliability.js`.
* `/audit/platform-report`: now loads `platformReport`. `legacyTab` removed; `status` `shared` -> `ready`; `after: { focus: "#printTarget" }` kept.
* `/system/reliability`: now loads `reliability`. `legacyTab` removed; `status` stays `partial`; `after.focus` (the CSV link) kept;
  `after.notice` kept as an info notice, reworded because the old text said "the rest of this page is the existing study reports screen",
  which is no longer true. It still contains "export-only" and "only reliability feature".
* Why `legacyTab` had to go: admin.js sets `currentTab = route.legacyTab`, and `renderCurrentTab()` (called by `setCategoryRange()` and by in-place
  legacy actions) runs `legacyRenderers()[currentTab]`. With `legacyTab: "reports"` that is `renderReports()`, which would replace the new view with the
  old screen. With no `legacyTab`, `currentTab` is `""` and `renderCurrentTab()` is a no-op. The smoke test calls both `setCategoryRange()` and `renderCurrentTab()` on
  each new route and asserts nothing is replaced.
* The `reports` loader (legacy adapter `views/reports.js`) is kept in `viewLoaders` for compatibility, but no route mounts it.
* Everything else is byte-identical: Overview, Users, user detail (`shellLoad` kept), deletion requests, banned emails, Materials/Categories (sub-tab `after`),
  Reported questions, all three Study routes (module mappings, no `legacyTab`), Usage limits (modal), Configuration, AI testing, Activity log, and all 10 `aliases`
  (`#reports` still resolves to `/audit/platform-report`, now to the dedicated view). `test-report-reliability.mjs` asserts the full table.

### Legacy-tab coverage assertion (`test-admin-contracts.mjs`)
It required every old tab to appear as some route's `legacyTab`; `reports` no longer does, by design. The assertion is replaced, not removed: `reports` must
NOT be a `legacyTab`, its alias must resolve to `/audit/platform-report`, that route must load `platform-report.js`, the legacy bundle must still be loaded by
`admin.html` (existing check), and the union of `legacyTab`s and dedicated tabs must still equal all 10 expected tabs.

## Goal 1: Platform report: what is preserved

Same ids, classes, inline styles, wording, section numbering (4 and 5 appear only when they have rows, so numbering skips exactly as before), tables,
"Generated / Administrator" header, "Material categorization range" line, and print structure: `#printExclude` toolbar, `#printTarget > .admin-card` with
`.table-scroll > table.admin-table`, `.admin-card-title`, `hr`. The print stylesheet in `admin.html` is keyed on exactly these, so its pagination rules apply unchanged.
Everything that is not report content (loading/error banner, links to Study) lives inside `#printExclude`, which the stylesheet hides.

* Actions: Print / Save as PDF (`window.print()`), Copy Report Text (`#printTarget.innerText` to clipboard; success/failure via `ctx.ui.toast`, previously the legacy info modal),
  range filter This Week / This Month / All Time with the legacy labels.
* The legacy report has tables only; there were no charts to preserve.
* Data meanings are the legacy ones; each source is the same endpoint admin.js used to fill `adminData`:
  `/api/admin/overview` (.stats), `/api/admin/users` (.users), `/api/admin/topics`, `/api/admin/material-categories?range=`, `/api/admin/banned-emails` (.bannedEmails),
  `/api/admin/activity-log` (.log, `type === "delete"`). All GET, no new endpoints, no backend change, nothing computed that the legacy report did not compute
  (share = round(count / totalMaterials * 100); status precedence banned > suspended > flagged > active).
* Not present on this view: enrolment, create accounts, identity-key CSV/delete, participant list, assigned handouts, and every `/api/admin/export/*` link.
  A one-line pointer links to `#/study/participants` and `#/study/data-exports`.
* Parity was checked in the browser: with the same session data the text of legacy `renderReports()` equals the new `#printTarget` text (timestamps normalised).

### Deliberate behaviour differences (please review)
1. **Range state is local to the view** (default All Time, remembered while the page stays open). It is no longer the admin.js global `currentCategoryRange`, which a module cannot reach. Overview already
   keeps its own range, so the old "shared by Overview and Report" coupling no longer exists. Calling the legacy `setCategoryRange()` no longer changes this report.
2. **Data is fetched on mount**, not read from the `adminData` cache, so the report is current when opened. It costs six GETs per open (one per range change).
3. **Failures are visible.** The legacy cache turned failed requests into zeros / "None". Now a failed source is named in a warning with Retry, and the affected sections print
   "Could not load ... This section is not available in this report." instead of a fabricated value. Operators should not print while that warning is showing.
4. Report text from the API (emails, reasons, topics, flagged users) is inserted as text. The legacy code interpolated flagged emails into HTML unescaped.
5. Print and Copy are disabled until data has loaded. `#printTarget` exists immediately (with a loading line) so the shell's `after.focus` works, and `mount()` does not await the network (the router serialises navigation behind `mount()`).

## Goal 2: Reliability

Export-only page: explanation + one link to `/api/admin/export/reliability.csv`, a pointer to Study > Data exports. No API requests, no tables, charts, figures or controls, and nothing
that suggests a dashboard; it states there is none. The same CSV link also still appears on Study > Data exports (that view is unchanged and not mine to edit).

## Verification actually run (Node 22, Playwright + Chromium, this sandbox)

| Command | Result |
|---|---|
| `node scripts/test-report-reliability.mjs` | pass, 140 assertions |
| `node scripts/smoke-report-reliability.mjs` | pass, 20/20 |
| `node scripts/test-admin-contracts.mjs` | pass |
| `node scripts/test-admin-shell.mjs` (unmodified) | pass, 242 checks |
| `node scripts/smoke-admin-shell.mjs` (unmodified) | pass, 51/51 |
| `node scripts/test-admin-overview.mjs`, `test-admin-ui-hardening.mjs`, `test-ai-testing-lifecycle.mjs`, `test-ai-testing-preview.mjs` | pass |
| `node scripts/smoke-people-users.mjs` | pass, 15/15 |
| `node scripts/smoke-admin-overview.mjs` | 33/33 on three reruns; one earlier run had 1 failure ("inbox item navigates through the router", landed on Overview), not reproduced; the base ZIP passes 3/3. Probably timing; unexplained |
| `node scripts/smoke-study-views.mjs` | **18/21**: see below |
| `test-people-users.mjs` DOM part, `test-study-views.mjs` | **skipped** (jsdom not installed); nothing verified there |

The new smoke test covers: alias `#reports` lands on the new view; all seven sections and values; legacy-text parity; no Study/export content; range filter (only the categories endpoint is refetched);
legacy `setCategoryRange()`/`renderCurrentTab()` cannot replace the view (same DOM node); Print calls `window.print()`; Copy writes the clipboard; print-media computed styles (toolbar/sidebar/topbar hidden, card
`break-inside:auto`, `thead` repeating, rows/titles kept together); a 150-user report renders to more than one A4 page via `page.pdf()` with the **same page count as the legacy renderer on the same data**;
failure and Retry; slow API does not block navigation; Reliability content, no requests, no extra links; navigation between neighbouring routes.

### Existing assertions that need later integration changes (I did not edit these files)
* `scripts/smoke-study-views.mjs`, check "legacy Platform report is untouched and still renders its own participants card and export bar": now fails (`#participantsCard` count 0, expected 1). That is the intended outcome of this task.
  Replace with: `#printTarget` present, `#participantsCard` and `#printExclude`'s export links absent.
* `scripts/smoke-study-views.mjs`, "participants: own view..." and "exports: own view..." fail on the **base ZIP too** (the shell focus target `#participantsCard` / `#printExclude` is not the active element). Not caused by this change; not investigated.
* `scripts/smoke-admin-shell.mjs` line ~167 message says "renders the legacy reports screen once"; the assertion passes but the message is stale.
* `scripts/test-admin-shell.mjs` needed no change (it still sees the Reliability notice text, `partial`, and `#printTarget` focus). It does not assert that the platform report is separate from Study content; the new tests do.

## Print limitations (unverified)
Verified only with Chromium print emulation and `page.pdf()` page counts on mock data. **Not tested:** the real browser print dialog, Firefox/Safari, headers/footers and margins chosen by the user, actual printers,
very large real datasets, or whether a table row taller than a page splits. The pagination rules themselves are the unchanged ones in `admin.html`; this view only has to keep the markup they target.
Do not rename `#printTarget`, `#printExclude` or the `.admin-card` / `.table-scroll` / `thead` / `tr` / `.admin-card-title` / `hr` nesting.

## Remaining dependencies
* `admin.html` print CSS (above); `admin.html` must keep loading `admin/legacy/reports.js` (the old renderer remains available, now unrouted).
* `ctx.api.get` from the shell (wired to `apiFetch`, with `signal` passed through); `ctx.ui.toast` (falls back to the global `showInfoModal`); `#topbarName` for the Administrator name (falls back to `ctx.session.user.name`, then "Admin").
* Backend response shapes are taken from how the legacy code read them; **real API behaviour was not tested** (no Spring backend, no authenticated session). Mock shapes in the smoke test mirror the legacy usage, not captured responses.
* `admin-dev.html` (the mock-API harness) was not run.
* Optional later cleanup: remove `renderReports()` and the `views/reports.js` adapter once nothing needs them (admin.js still references `renderReports` in `legacyRenderers()`, and `test-admin-contracts.mjs` expects `admin/legacy/reports.js` to be loaded, so removal is not safe in this task).
