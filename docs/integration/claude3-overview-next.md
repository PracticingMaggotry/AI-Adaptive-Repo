# Integration notes: Overview + Needs-attention inbox

Scope: `#/overview` is now the real landing page, and `admindashboard.html` redirects to it.
The sidebar shell and hash router from `claude3-admin-shell-router.zip` are unchanged.

## 1. Changed files

| File | Change |
|---|---|
| `src/main/resources/static/admin/views/overview.js` | **Replaced.** Was a thin adapter over legacy `renderOverview()`. Now a self-contained view (`mount(el, ctx)` / `unmount()`). Same path, so `routes.js` needed no change. |
| `src/main/resources/static/admin/overview-model.js` | **New.** Pure functions (no DOM, no network): KPIs, account breakdown, quiz analytics, inbox aggregation and de-duplication. |
| `src/main/resources/static/admin/overview.css` | **New.** `.ov-*` styles, injected by the view on mount. `admin.html` is not edited. |
| `src/main/resources/static/admindashboard.html` | **Replaced** with a redirect to `admin.html#/overview` (see section 5). |
| `scripts/test-admin-overview.mjs` | **New.** 122 checks (model, routes, backend endpoints, static safety, redirect). |
| `scripts/smoke-admin-overview.mjs` | **New.** 33 browser checks (Playwright, mocked API). |
| `scripts/smoke-admin-shell.mjs` | **Edited** (fixture + selector only, see section 7). |
| `INTEGRATION-NOTES.md` | This file. |

Not touched: `routes.js`, `router.js`, `admin.html`, `admin.js`, `ui.js`, shared CSS, `login.js`, `AuthInterceptor.java`,
all other views, all `legacy/*.js` (`renderOverview` is kept, see section 6).
Baseline: I applied `phase1-hardening.zip` (`ui.js`, `admin-components.css`, its test) and the tokens CSS from `phase1-shared-ui.zip` to my
working tree before starting. They are **not** in this zip. The Overview does not call `ctx.ui`, so it does not depend on them.

## 2. Dashboard data sources and what was migrated

All endpoints already exist (verified against `AdminController` / `QuestionReportController`) and are called with GET only, through `ctx.api.get`
(the shell's wrapper over `apiFetch`). Each source loads, fails and retries independently.

| Old dashboard / old Overview feature | Now | Source |
|---|---|---|
| Platform KPIs (users, topics, questions, attempts, materials) + hints | Migrated | `GET /api/admin/overview`, `/api/admin/users` |
| User breakdown (active / suspended / banned / flagged) | Migrated, same definitions | `/api/admin/users`, `/api/admin/banned-emails` |
| Security summary (banned emails, deleted accounts) + recently banned | Migrated | `/api/admin/banned-emails`, `/api/admin/activity-log` |
| Recent admin actions (5) | Migrated | `/api/admin/activity-log` |
| Material categorization + This Week/Month/All Time + charts | Migrated | `/api/admin/material-categories?range=` |
| Quiz analytics (avg, difficulty mix, score distribution, recent attempts) | Migrated | `/api/admin/quiz-activity` |
| Topics & content table with attempts/performance | Migrated | `/api/admin/topics` + quiz history |
| Platform activity ratios (questions/topic, attempts/user, materials/user) | Migrated (numbers only) | `/api/admin/overview` |
| Quick actions | Migrated, now real `<a href="#/...">` links | see section 4 |
| **Needs attention** inbox | **New** | section 3 |
| Greeting banner | Reduced to a one-line greeting (`ctx.session.user.name`) | `/api/me` via shell |

Deliberately **not** migrated, with reasons:
- **"Recent Registrations" table**: `/api/admin/users` has no registration timestamp and no ordering guarantee, so any "recent" label would be false.
- **Decorative progress bars** in "Platform Activity" (fixed 60/40/30% widths unrelated to data). Only the real ratios are shown.
- **Dashboard top-bar search box** (it only filtered the dashboard's own mini-tables). Global search is out of scope for this task.
- **Dashboard sidebar collapse toggle**: the page is retired; the shell has its own navigation.

Honest labels: "Deleted (in recent log)" counts `delete` entries among the server's newest 200 log entries, so it is a lower bound.

## 3. Needs-attention inbox: categories and data sources

| Category | Source | Counted as | Destination | Workflow already exists |
|---|---|---|---|---|
| Reported questions | `GET /api/admin/reports?status=PENDING` | pending reports (grouped per question; one item per question) | `#/content/reported-questions` | Yes (review/fix/dismiss/delete) |
| Deletion requests | `GET /api/admin/deletion-requests?status=PENDING` and `?status=APPROVED` | open requests (pending + approved-ready-to-execute); "awaiting your vote" from `callerVoted` | `#/people/deletion-requests` | Yes (vote/execute/cancel) |
| Flagged accounts | `GET /api/admin/users` (`flagged`, `flagReason`) | flagged accounts | `#/people/users` | Yes (flag filter, unflag) |

Rules implemented (and tested): a source that fails shows an error with **Try again** and is never shown as 0 or "All clear"; "All clear" appears only when
every source loaded and returned nothing; if one half of the deletion data fails the other half is shown with a note; items are de-duplicated by stable key
(report id, request id, user id/email); every refresh replaces data (a failed refresh drops the old items instead of leaving them on screen); responses from
superseded requests are ignored; leaving the page aborts in-flight requests; a visibility change re-checks after 60 s; the sidebar badges are updated
from the same de-duplicated counts.

**Not implemented (no safe integration), on purpose:**
- **Self-service account deletion requests** (`GET /api/admin/self-deletion-requests`): the endpoint exists but no current admin screen uses it, so there is no
  workflow to link to. It needs a UI in the People area first. The legacy Deletion-requests screen shows only the dual-control flow.
- **Suspended accounts**: an admin-applied state, not a pending decision. Shown as a count only.
- **Pending users / material moderation / AI failures / anything else**: no existing data source or workflow.
- **Deep links** such as "open this report" or "users filtered to flagged": the target views do not read route params/queries, so each item links to its
  area (the correct canonical route), not to the individual record. This needs those views to accept `ctx.params`/`ctx.query`.

## 4. Canonical routes used

Quick actions and inbox items link only to: `#/content/reported-questions`, `#/people/users`, `#/people/banned-emails`, `#/people/deletion-requests`,
`#/content/materials`, `#/audit/platform-report`, `#/audit/activity-log`. A test asserts each exists in `routes.js` as a sidebar route, and that every
destination offered by the old dashboard (`reportedquestions, users, topics, bannedemails, deletionrequests, reports`) is still offered.

## 5. Redirect behavior and authentication

`admindashboard.html` is now a tiny page: `window.location.replace("admin.html#/overview")` plus a `<noscript>` meta refresh.
- **Auth is unchanged and not duplicated here.** `AuthInterceptor` already gates both `/admin.html` and `/admindashboard.html` server-side (logged-out to
  `/login.html`, non-admin to `/dashboard.html`), and `admin.html` re-checks `/api/me` itself (access-denied state, 401 to login). The redirect page makes
  no API call, so only a logged-in admin ever receives it. It does not weaken any check.
- **No loop:** `admin.html`, `admin.js`, the router and the view never redirect to `admindashboard.html` (a test asserts this). `replace()` keeps the
  redirect page out of history, so Back does not bounce.
- **Kept working through the redirect:** `login.js` (admin login target) and `AuthInterceptor` (sends an admin who opens a student page to
  `/admindashboard.html`) still point at the old URL. Result: one extra hop, no breakage.
- **Required follow-up (files I was told not to edit):** change `login.js` (`"/admindashboard.html"`) and the two `AuthInterceptor.java` redirects
  (`sendRedirect(... "/admindashboard.html" ...)`) to `/admin.html#/overview`, then `admindashboard.html` can be deleted. Also leave `ADMIN_ONLY_PAGES`
  as is until it is deleted. Other pages (`profile.html`, `dashboard.html`, ...) also contain `admindashboard.html` links; they work through the redirect.
- **`admin.html` follow-up:** the sidebar footer still has a "Dashboard (legacy page)" link to `admindashboard.html`. It now just redirects to Overview, so
  remove it when `admin.html` is next edited.

## 6. Behavior changes to be aware of

- **Legacy re-render guard.** `admin.js`'s `renderCurrentTab()` would call legacy `renderOverview()` and overwrite this page. While the view is mounted it
  points `window.renderOverview` at a refresh of itself and restores the original on unmount (tested). `legacy/overview.js` is untouched and still used if
  the router fails to load. Cleaner fix for later: drop the legacy renderer once nothing calls it.
- **Category range is local to the Overview.** The legacy range was shared with the printable Platform report via `currentCategoryRange`; now the Overview
  keeps its own and the report keeps its own.
- **Extra requests.** `admin.js` still preloads `adminData` at startup; the Overview does not use it (it fetches fresh data), so those calls are duplicated.
  It also fetches `/api/admin/reports` and `/api/admin/deletion-requests` lists (not just the pending-count the legacy badges use).
- **Two writers for three sidebar badges** (legacy refreshers and the Overview, via `ctx.badges`). They use the same underlying data; the Overview uses
  de-duplicated counts.
- Deletion badge semantics unchanged (PENDING only). The inbox additionally counts APPROVED-ready-to-execute.

## 7. Verification

**Passed** (Node 22, this environment):
- `node --check` on all classic scripts and ES modules.
- `scripts/test-admin-contracts.mjs`, `scripts/test-admin-shell.mjs` (238 checks), `scripts/test-admin-ui-hardening.mjs`,
  `scripts/test-admin-overview.mjs` (122 checks).
- `scripts/smoke-admin-shell.mjs` 51/51 and `scripts/smoke-admin-overview.mjs` 33/33 in headless Chromium.
- Mutation checks: removing the report de-duplication, and rendering a failed source as empty, each make `test-admin-overview.mjs` fail.

**Test edits (no assertions removed):**
- `smoke-admin-shell.mjs`: the Overview quick action is now a link, so the selector changed from `button /Manage Users/` to `link /Manage users/`
  (same behavior asserted); its mock `/api/admin/reports` list now matches its `pending-count` of 4 (the old mock returned an inconsistent empty list).
- `test-admin-shell.mjs` reports 238 checks instead of 239. No assertion was changed: that test scans repo sources for old-hash producers, and the retired
  dashboard no longer contains `admin.html#overview`, so one input fewer is checked. All ten aliases are still asserted from the alias table.

**Browser tests used mocked APIs.** Only `/api/*`, `/logout` and the CDN scripts were mocked (Chart.js replaced by a counting stub); static files were real.

**Not run / not verified:**
- No Maven/Java tests, no real Spring backend, no real database.
- **No authenticated production session.** Server-side gating of `admindashboard.html` was verified by reading `AuthInterceptor`, not by exercising it.
- No real Chart.js rendering (stubbed), no other browsers or devices, no screen-reader testing, no visual regression, no load test of the full-history
  endpoints (`/quiz-activity`, `/reports`) with realistic data volume.
- `admin-dev.html`'s `mock-api.js` does not know the new list endpoints, so the dev harness shows error states for them (file not in scope).

## 8. Remaining limitations and follow-ups

1. Retarget `login.js` / `AuthInterceptor` to `admin.html#/overview`, then delete `admindashboard.html` (section 5).
2. Remove the "Dashboard (legacy page)" sidebar link from `admin.html`.
3. Self-service deletion requests: needs a screen before it can be an inbox category.
4. Deep links to individual reports/requests/users need the target views to read `ctx.params` / `ctx.query`.
5. `/api/admin/quiz-activity` returns every attempt ever recorded; for large datasets a server-side summary endpoint would be cheaper than aggregating on the client.
6. Remove legacy `renderOverview` and the `window.renderOverview` guard once nothing re-renders the Overview through `renderCurrentTab()`.
