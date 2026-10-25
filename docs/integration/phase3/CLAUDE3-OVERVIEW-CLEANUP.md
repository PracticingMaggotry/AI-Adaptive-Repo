# claude3-overview-cleanup-next: integration notes

Base: `AI-Adaptive-Repo-current-integrated-2026-10-23.zip`. The zip holds only changed files at their repository-relative paths, plus this file.

## What changed

### Goal 1: self-requested deletion in Needs attention
- `admin/overview-model.js`: new exported `selfDeletionsGroup(resp)`, added to `buildAttention()` as a fifth source (`selfDeletions`).
  - Group id `selfDeletions`, title **Self-requested deletions**, noun "self-requested deletion", route `ROUTES.deletionRequests` (`#/people/deletion-requests`).
  - Separate from the admin-initiated dual-control group (`deletions`). Counts are never merged, item keys are namespaced (`s<id>` vs `d<id>`), and the self items carry a blue "Self-requested" pill and the line "requested by the account owner · single admin review".
  - Only entries with a usable `id` (the user-account id) are counted. Duplicate ids collapse. Newest request first.
  - Unreadable entries are not counted and are disclosed in a note ("N self-requested deletion entries could not be read…"). A note makes the inbox "incomplete", so it can never reach "All clear".
  - Group order is now: reports, deletions, **selfDeletions**, flagged.
  - The overall total is a plain sum of the independent groups. It is a count of open items, not an approval-queue count.
- `admin/views/overview.js`:
  - New source `selfDeletions` loading `GET /api/admin/self-deletion-requests`. The validator requires an object with a `requests` array and `success !== false`.
  - Uses the same per-source machinery as the other sources: independent retry, token/abort stale-response guard, unmount cleanup, and textContent-only rendering.
  - The group retry reloads only `selfDeletions`. The source is part of the inbox refresh and the 60 s stale refresh.
  - A malformed body is now reported differently from a failed request. It gets a different title ("Could not **read** …" vs "Could not **load** …") and `data-error-kind="malformed|request"`. This applies to all sources, because the error block is shared.
  - All-clear and fine-print copy now mention self-requested deletions.
  - Added `data-group` attributes on groups, an optional per-item pill `tone`, and a read-only export `OVERVIEW_SOURCES` (used by tests so they check the real validators and paths).
- `admin/overview.css`: unchanged. The blue pill class already existed.
- The deletion-request view and the sidebar badge are untouched. The People › Deletion requests badge still reflects only the dual-control pending count.

### Goal 2: entry points
- `login.js`: admins go to `/admin.html#/overview`. The non-admin path (`data.redirect || "/dashboard.html"`) is untouched.
- `AuthInterceptor.java`: new constant `ADMIN_LANDING = "/admin.html#/overview"`, used by exactly the three places that sent an admin to `/admindashboard.html`:
  1. `/` and `/index.html` (admin)
  2. login/register pages while logged in (admin)
  3. student-only pages visited by an admin
  Nothing else in the file changed.
- `admin.html`: removed the "Dashboard (legacy page)" sidebar link. Logout is kept.
- `admindashboard.html` was not touched. It remains a redirect page and remains in `ADMIN_ONLY_PAGES`.

### Goal 3: dev harness (`admin/mock-api.js`)
- Added `/api/admin/quiz-activity` returning `{ success: true, quizHistory: [], avgScore: null }`. Previously it hit the catch-all body, so the Overview correctly reported a malformed response, which was a false error in the harness. It is empty on purpose, so no attempts are invented.
- `/api/admin/self-deletion-requests` already existed (one fixture) and is now consumed.
- Added opt-in failure injection. Nothing fails by default.
  - `admin-dev.html?view=overview&mockFail=/api/admin/self-deletion-requests:500` makes that endpoint reject with HTTP 500.
  - `...:malformed` returns a 200 whose body lacks the expected fields.
  - The `mockFail` parameter can be repeated, and `window.__adminMockFailures[path] = 500|"malformed"` works at runtime.
- No counts were invented for any unavailable source.

## Authorization checks that remain unchanged
- The `/admin.html` and `/admindashboard.html` entries in `ADMIN_ONLY_PAGES`, and the redirect of non-admins from them to `/dashboard.html`.
- `STUDENT_ONLY_PAGES`, including the admin `adminPreview=1` exception for `/quizpage.html` only.
- Logged-out, suspended, and unconsented handling.
- All server-side admin endpoint authorization, including `GET /api/admin/self-deletion-requests`. No controller was edited and no endpoint access was broadened.
- `test-admin-overview.mjs` asserts the full list of `sendRedirect(...)` arguments. Only the three admin-landing arguments differ from before.

## Tests
Run in this environment (Node 22, Playwright/Chromium via a global install):

| Script | Result |
|---|---|
| `test-admin-overview.mjs` | pass, 214 checks (new: self-deletion aggregation, dedupe, ordering, unreadable entries, failure vs loading vs empty vs data, dual-control separation, validators, dev-mock shapes and failure injection, redirect and interceptor static checks) |
| `test-admin-shell.mjs` | pass, 242 checks (one assertion inverted: the legacy sidebar link must now be absent) |
| `test-admin-contracts.mjs`, `test-admin-ui-hardening.mjs`, `test-ai-testing-lifecycle.mjs`, `test-ai-testing-preview.mjs` | pass |
| `smoke-admin-overview.mjs` | 48/48 (new: own group and counts, canonical hrefs, hostile text inert, 500 + Try again + recovery, 4 malformed bodies, partly unreadable list, loading state, leaving mid-load, login redirect for admin and non-admin, no legacy sidebar link) |
| `smoke-admin-shell.mjs` | 51/51 |
| `smoke-people-users.mjs` | 15/15 |
| `smoke-study-views.mjs` | 19/21. The same 2 checks fail on the untouched baseline. Not caused by this work and not investigated. |
| `admin-dev.html` loaded in Chromium | default: no errors and a self-deletion group; forced 500 and forced malformed: both visible as errors, no All clear |
| `AuthInterceptor` | compiled against hand-written stubs of the servlet/Spring types and run over 20 role × page cases, before and after. Only the 7 admin-landing outcomes differ. This was a throwaway check and is not in the zip. |

Test-side fix outside the Overview files: in `smoke-admin-shell.mjs`, the "Overview quick action navigates to Users" check read the page title immediately after the hash changed. The title is applied about 7–42 ms later on both the baseline and this tree, so the check passed by timing luck. It now waits for the title. Router behavior was not changed.

## Not tested / not verified
- **Maven and the Spring backend were not run.** There is no Maven or Spring jars here and no network path to Maven Central. The server-side redirects are NOT production-verified.
- The stub-based `AuthInterceptor` run does not exercise the real Spring/Tomcat stack. In particular it does not prove that a servlet container preserves the `#/overview` fragment in the `Location` header. Browsers keep it, and `sendRedirect` with a fragment is normal practice, but confirm it once against the running app. Test as an admin hitting `/`, `/login.html` while logged in, and `/dashboard.html`.
- No authenticated session and no real `GET /api/admin/self-deletion-requests` call. The response shape comes from `AdminController.java` and was exercised only against mocks.
- `jsdom`-based DOM scenarios in `test-people-users.mjs` and `test-study-views.mjs` skip here (jsdom not installed). Their browser smoke counterparts ran.
- No stale-response race test for the self-deletion source specifically. The Refresh button is disabled while loading, so the race cannot be driven from the UI. The guard is shared code, asserted statically, and the leave-mid-load case is tested.
- `smoke-admin-shell.mjs` still answers the new endpoint with the generic `{}`, so the Overview shows it as unreadable there. No assertion depends on it.

## Later follow-ups (deliberately not done)
- The People › Deletion requests sidebar badge still counts only dual-control pending requests. It could include self-requests once Claude 1's queue lands.
- Overview and the shell both fetch some of the same endpoints. Sharing one cache would remove duplicate requests.
- `dashboard.html`, `learninghub.html`, `profile.html`, `quizfinish.html`, `quizhub.html`, `quizpage.html` and `reports.html` still link to `/admindashboard.html` (asserted by a test, so removing the file later will flag them).
- Once those links are moved, delete `admindashboard.html` and its `ADMIN_ONLY_PAGES` entry together.
- The 2 failing `smoke-study-views` checks should be looked at by the Study owner.
