# Phase 5 stabilization notes

Source of truth: `AI-Adaptive-Repo-phase4-integrated-2026-10-24.zip`.
Result: **three test scripts changed (two edited, one added). No product file was modified.** `admin.html`, `admin.js`, `login.js`, `AuthInterceptor.java`, Overview modules and palette modules are byte-identical to the input. No defect in them was demonstrated.

## Files in this zip

| File | Change |
|---|---|
| `scripts/smoke-admin-overview.mjs` | Fixed a race in "inbox item navigates through the router" |
| `scripts/smoke-admin-shell.mjs` | Fixed two load-dependent races |
| `scripts/smoke-admin-phase5.mjs` | New: 15 Chromium checks on the real `admin.html` |
| `INTEGRATION-NOTES.md` | This file |

## Environment

Node v22.22.2, Playwright 1.56.0, Chromium 141.0.7390.37 headless on Linux, jsdom installed, 1 CPU.
Browser tests need `PLAYWRIGHT_BROWSERS_PATH` pointing at the Chromium install, and `playwright`/`jsdom` resolvable (the zip has no package.json).
Everything is a **mocked API** (Playwright interception of `/api/*` and `/logout`). There was no Spring backend, no Maven/Java run, no real session and no production verification.

## Test commands and results (final tree, from repo root)

Each is `node scripts/<name>.mjs`; every command below exited 0 except `smoke-study-views`.

Unit tests (Node, no browser): `test-admin-shell` 242 checks; `test-admin-contracts` passed; `test-admin-overview` 214; `test-admin-command-palette` 174; `test-admin-ui-hardening` passed; `test-system-configuration` 350 assertions; `test-report-reliability` 140; `test-ai-testing-preview` passed; `test-ai-testing-lifecycle` 16.

jsdom tests (not a real browser): `test-deletion-badge` 18/18 scenarios, 211 assertions; `test-self-deletion-queue` 14/14, 88; `test-people-users` 13 scenarios, 62 assertions.

Chromium browser automation (mocked API): `smoke-admin-shell` 51/51; `smoke-admin-overview` 48/48; `smoke-admin-command-palette` 44/44; `smoke-admin-phase5` 15/15; `smoke-report-reliability` 20/20; `smoke-people-users` 15/15; `smoke-self-deletion-queue` 8/8; `smoke-deletion-badge` 14/14.

**Still failing, unchanged and out of ownership:** `smoke-study-views` 18/21. Failing: "participants: own view, not the Reports screen", "exports: own view with the seven legacy links", "legacy Platform report is untouched and still renders its own participants card and export bar". Same as the phase 4 baseline; not investigated, not loosened.

## 1. Overview navigation flake (root cause)

Baseline: `smoke-admin-overview` failed 4 of 6 runs on "inbox item navigates through the router" (title still `Overview`).
Cause: **test race, not a product defect.** `location.hash` changes synchronously at click, but the hash router renders on the asynchronous `hashchange` event (unmount, then title/breadcrumb/nav, then mount). Instrumented timeline in Chromium: title changed 6-14 ms after the click. The test read the title in that gap.
Fix: after the hash wait, wait for the rendered state (title equals target and `.ov-root` gone), then make the original assertions unchanged. Added: sidebar item is active; Back returns to a freshly mounted Overview. No timeout was changed; no assertion was removed.
After: 20/20 consecutive passes (unloaded). Not run under load.

## 2. Shell smoke flakes

Baseline: `smoke-admin-shell` failed once (50/51) on "Back while the usage-limits modal is open closes it". That failure **did not recur in roughly 70 later runs and its cause is undetermined**; that check is unchanged.
Under artificial CPU load (busy loops on the 1-CPU sandbox) two other failures reproduced (about half the runs), both the same pattern:
- "content rendered" asserted after the title plus a blind 80 ms sleep. The title is applied before the view mounts. Fix: wait for the router's afterMount focus signal (heading or `data-route-focus`) and non-empty content. Same-route click and the modal route are excluded from the focus wait.
- "refresh on #/content/categories shows Categories" read `isVisible()` once. Fix: wait for the view to be visible, then also assert Materials is hidden.
After: 51/51 unloaded, and 5/5 under the load that failed about half the time before.
The 150 ms sleeps in the AI-testing beacon checks were left: they are bounded negative/"exactly one" checks and did not fail under load.

## 3. New `smoke-admin-phase5.mjs` (Chromium, real admin.html, mocked API)

- Ctrl+K inside a real page input (`#userSearchInput`) is not intercepted; focus and text kept.
- IME composition (`isComposing`) does not open it.
- Not opened while the legacy `#modal` (Usage limits) or a native `<dialog>` drawer (user detail) is open.
- 40 rapid open/close cycles (Escape and toggle): focus returns to the opener each time; one root; reopens.
- Palette destinations equal the sidebar `data-route` set, and each resolves through `adminRouter.resolve()`.
- Search + Enter navigates through the router (router state checked, not just the hash); Back/Forward work; palette closed and reusable; query cleared on reopen.
- Back while open closes it; no-result message, Enter does nothing, clearing restores the list.
- Hostile query text is not parsed as HTML.
- Calling `initCommandPalette` three times on the live page leaves one root and one working listener.
- Non-admin: access-denied page, dashboard link, no palette, no `/api/admin/*` requests.
- 401 on `/api/me` goes to `/login.html`.
- `/admindashboard.html` redirects to `admin.html#/overview`; `admin.html` has no link back to it.
Mutation check: disabling the dialog guard and the editable-target guard made exactly the two matching checks fail (13/15); files restored. Stability: 8/8 unloaded, 3/3 under load.

## 4. Review findings (code reading plus existing tests; no change made)

- **Overview data states:** every source has a validator; a malformed body is an error with Retry, never an empty list. The self-deletion source rejects non-objects, `success:false` and a missing `requests` array. The existing 48-check smoke covers: genuine empty inbox ("All clear"), single-source failure, half-failed deletions, self-deletion failure/malformed/recovery, loading, leaving mid-load, 401, stale-response guards (token + abort) and no duplicate requests on retry. I did not read `buildAttention` in `overview-model.js` line by line; I rely on those tests (214 unit checks).
- **Redirects (`AuthInterceptor.java`, `login.js`):** logged-out goes to `/login.html`; logged-in on login/register or `/` goes to `/admin.html#/overview` (admin) or `/dashboard.html`; non-admin on `/admin.html` or `/admindashboard.html` goes to `/dashboard.html`; admin on student pages goes to the admin landing (except `quizpage.html?adminPreview=1`). `login.js` sends admins straight to `admin.html#/overview`. Consistent with the tests; no defect found. **Not run against Spring/Tomcat.**
- **Legacy dashboard link:** seven student pages (`profile`, `learninghub`, `dashboard`, `quizpage`, `quizhub`, `reports`, `quizfinish`) still send admins to `/admindashboard.html`. It must stay; it redirects with `location.replace`, makes no API calls, and is still in `ADMIN_ONLY_PAGES`. No broken internal navigation found. The comment inside `admindashboard.html` says `login.js` sends admins there; that is stale (it no longer does). Cosmetic, not changed.

## Remaining risks

1. **Not verified in a real session:** authentication, authorization, 401 handling, and every redirect against the real Spring stack and cookies. Mock only.
2. **Case-sensitivity (not demonstrated):** `AuthInterceptor` matches exact-case paths (`/admin.html`) while detecting HTML by lower-cased suffix. On a case-insensitive file system, `/ADMIN.HTML` might not match `ADMIN_ONLY_PAGES`. Backend controllers still authorize every `/api/admin` call, and `admin.html` re-checks `/api/me`. Could not test here; test it on the real deployment before treating it as safe or as a defect.
3. **Possible flash on refresh at `#/content/categories` (inferred, not seen visually):** legacy `renderTopics()` writes the Materials view, awaits, and only then `afterMount` applies the Categories sub-tab. Files: `admin/views/topics.js`, `admin/legacy/topics.js` (not in this phase's ownership).
4. **Router-level gap:** between a hash change and the render (about 6-14 ms measured), the URL and the visible page disagree. By design of the hashchange-driven router; `router.js` was not touched.
5. **`login.js`** assigns the server-provided `data.redirect` to `location.href` without checking it is a same-origin path. The server currently only returns `/dashboard.html`. Hardening idea, not a demonstrated defect.
6. **Accessibility:** ARIA roles/states are asserted in the DOM only. No screen reader, keyboard-only manual pass, or forced-colors check. Mobile drawer focus and print were only exercised by existing smoke checks.
7. **Browser coverage:** Chromium 141 only. No Firefox, Safari, real macOS keyboard (Cmd+K is simulated by overriding the platform), or touch device. Palette bfcache/`pagehide` behavior is covered only by synthetic events.
8. **Not run:** Maven/Java tests, `admin-dev.html` harness, `mock-api.js`, `smoke-study-views` fix.
9. The undetermined single failure described in section 2.
