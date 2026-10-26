# Current Admin Redesign Integration Status

**Last integrated:** Phase 5 stabilization patches, 2026-10-25.  
**Current source archive:** `AI-Adaptive-Repo-phase5-integrated-2026-10-25.zip`.

## Completed implementation phases

- Phase 1: shared UI components, hardening, and AI Testing preview/lifecycle fixes.
- Phase 2: six-section admin sidebar/hash router, People user-detail/status work, and dedicated Study views.
- Phase 3: Overview/dashboard consolidation, self-requested deletion attention items, dedicated Platform Report and Reliability views, and admin entry-point redirects.
- Phase 4: combined deletion badge, System Configuration page, and Ctrl+K/Cmd+K navigation palette.
- Phase 5: People/deletion stability fixes, Study/report/route regression fixes, and Overview/shell smoke-test stabilization.

## Current known functionality

- Sidebar: Overview, People, Content, Study, System, Audit.
- People: Users/details, deletion requests (admin dual-control plus self-request queue), combined deletion badge, banned emails.
- Content: Materials, Reported questions, Categories.
- Study: dedicated Participants, Assigned handouts, and Data exports views.
- System: Usage limits, Configuration, AI Testing, export-only Reliability.
- Audit: Activity log and dedicated Platform Report.
- Global navigation command palette: Ctrl+K/Cmd+K, route-driven results and safe text rendering.
- Canonical hash routes and legacy aliases retained.

## Verification performed during Phase 5 integration

- 77 JavaScript/MJS files passed `node --check`.
- Contract tests, shell tests (242), Overview unit tests (214), command-palette tests (174), AI Testing preview/lifecycle tests (16), Platform Report/Reliability assertions (140), and pure deletion/status/configuration model checks passed as recorded in `PHASE5-INTEGRATION-REPORT.md`.
- Browser and DOM tests were **not fully re-run in the integration environment** because Playwright/Puppeteer/jsdom are not installed/resolvable here. Refer to the integration report for the exact skipped suites and to individual Claude notes for their reported test results.
- No live Spring/backend/Maven or authenticated production verification has been performed.

## Immediate next step

Freeze feature work and perform acceptance testing:

1. Configure Playwright + Chromium and jsdom; execute every browser/DOM suite on this exact integrated ZIP.
2. Resolve any real failures and preserve tests that fail because of defects; do not remove assertions simply to obtain green results.
3. Run a test deployment with admin and non-admin accounts to verify authorization, redirects, configuration writes, deletion/restore workflows, live Study endpoints, CSV exports, and report content.
4. Perform manual keyboard/focus, narrow viewport, screen-reader, and report-print checks.

Do not declare production readiness until these checks are complete.

## UI rule: never render null/undefined/false as text

Native `Element.append()`, `prepend()`, `replaceChildren()`, `before()` and `after()` turn `null`, `undefined` and `false`
into visible text. `box.append(cond ? node : null)` therefore prints the word "null" (seen as "nullnull" under
Overview > Topics & content). In admin code:

- Build DOM with `h()` (skips null/false children), or in `views/overview.js` insert with `put()` / `swap()`.
- Never pass a conditional that can be `null`/`false` straight to a native insertion method.
- `node scripts/test-admin-no-null-text.mjs` enforces this (static lint of every admin module + the real Overview rendered
  in 9 states). Run it with the other `scripts/test-*.mjs` files before shipping admin UI changes.

## Admin boot: no duplicate requests

`initAdmin()` in `admin.js` still fills the classic `adminData` global (the Users view and the legacy actions read it), so
it loads overview, users, topics, material-categories, banned-emails and activity-log. These six now load **in parallel**
and go through `admin/startup-prefetch.js`, which hands each response to the first view that asks for the same path
(normally the Overview) instead of repeating the request: 17 requests became 11 on a cold admin load.

Rules (all covered by `scripts/test-admin-startup-prefetch.mjs`): one-shot, 15 s expiry, a failed boot request falls back
to a live request, any write clears the hand-off, readers get their own copy, AbortSignals still abort.
If a view ever needs boot-time data, call it through `ctx.api.get`; never read `adminData` for new code.

`admin/legacy/*.js` is **not dead code**: `admin.html` loads it and `views/users.js` depends on it. Do not delete it until
those views are ported.

## Server settings worth knowing

- `server.compression.*` is on (admin JS/CSS/HTML is about 3x smaller on the wire).
- `spring.jpa.show-sql` is off by default; set `JPA_SHOW_SQL=true` in Railway variables to debug queries.
