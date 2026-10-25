# Claude 3 — Admin sidebar shell and hash router (Phase 2)

Read `docs/ADMIN-REDESIGN-BRIEF.md`, `docs/ADMIN-ARCHITECTURE-AUDIT.md`, and `docs/admin-contract-freeze.md` first. This is the central Phase 2 task. Preserve existing business logic; do not rewrite all views.

## Own only these files
- `src/main/resources/static/admin.html`
- `src/main/resources/static/admin.js`
- `src/main/resources/static/admin/routes.js`
- `src/main/resources/static/admin/router.js` (create)
- `src/main/resources/static/admin/admin-shell.css` (create)
- focused route/shell tests under `scripts/`

Do not edit `admin/ui.js`, token/component CSS, any `admin/legacy/*.js`, view implementations, backend controllers, `login.js`, `admindashboard.html`, or `AuthInterceptor.java`. Do not remove legacy renderers.

## Goal
Replace the 10 horizontally scrolling tabs with a persistent sidebar containing these six groups:

- Overview
- People: Users, Deletion requests, Banned emails
- Content: Materials, Reported questions, Categories
- Study: Participants, Assigned handouts, Data exports
- System: Usage limits, Configuration, AI testing, Reliability
- Audit: Activity log, Platform report

Use hash routes such as `#/overview`, `#/people/users`, and `#/people/users/42`; store internal route paths without the `#` in `routes.js`. Do not use server path routes like `/admin/users` because there is no SPA fallback. Support old hashes (`#users`, `#topics`, etc.) as aliases and keep current quick actions working.

## Behavior-preserving approach
Reuse existing `admin/views/*.js` adapters or a simple explicit compatibility bridge to current `renderX()` functions in the first pass. Do not migrate all views to independent renderers yet. Route entries may focus or select existing subviews after rendering: `topics` has Materials/Categories subtabs; `reports` contains Participants (`#participantsCard`), export controls (`#printExclude`), and printable report (`#printTarget`). System > Usage limits should reuse `openDailyLimitsModal()` after the Users renderer is ready. Reliability is currently only a CSV export; do not claim a full Reliability view exists. Configuration has an API but no UI; display a clearly labeled not-yet-implemented state for now. Do not expose a broken User Detail route as complete: its backend endpoint does not exist.

Keep the route contract `mount(el, ctx)` / `unmount()` stable. Ensure the previous view is unmounted before replacement, Back/Forward and refresh on a hash route work, route aliases normalize cleanly, and the current admin authorization/data loading remains intact. Do not make sidebar hiding a security mechanism.

Do not redirect `admindashboard.html` yet: its unique dashboard content has not been merged into Overview. Do not edit login or AuthInterceptor redirect logic in this task. Document the exact follow-up needed; a later integration task can do redirects after Overview parity.

## Verify
Run the existing route/contract and syntax checks. Add or update a focused test for route grouping and old-hash aliases if practical. Do not claim full browser behavior unless tested. Return only changed/new files in a ZIP with original relative paths and a short report stating what works, what remains, tests run, and limitations.
