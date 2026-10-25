# People > Deletion requests: sidebar badge reflects both workflows

Source of truth: `AI-Adaptive-Repo-phase3-integrated-2026-10-24.zip`. Paths below are repository-relative (the zip's `AI-Adaptive-Repo-main/` wrapper folder is omitted).

## Files

New
- `src/main/resources/static/admin/people/deletion-badge.js` – derivation, last-known state, coalesced refresh, painting
- `scripts/test-deletion-badge.mjs` – pure + coordinator + jsdom regression test (18 scenarios, 211 assertions)
- `scripts/smoke-deletion-badge.mjs` – real-Chromium smoke (14 checks, mocked API)

Modified
- `src/main/resources/static/admin/legacy/deletionrequests.js` – `refreshDeletionBadge()` now delegates to the module (same global name and call sites)
- `src/main/resources/static/admin/views/deletionrequests.js` – binds the shared badge, passes a refresh hook to the self queue, resyncs on arrival, adds one explanatory sentence
- `src/main/resources/static/admin/people/self-deletion-queue.js` – optional `refresh.badge` hook (called after a confirmed execute/restore and after a "stale" outcome); nothing else changed
- `src/main/resources/static/admin/views/overview.js` – `syncBadges()` hands the three deletion responses it already loaded to the shared badge; records `startedAt` per source
- `scripts/smoke-admin-overview.mjs` – ONE assertion: the sidebar deletion badge expectation changed from `2` (pending only) to `5` (see "Expected behaviour change")

Not touched (verified byte-identical to the input zip): `admin/routes.js`, `admin/router.js`, `admin.html`, `admin.js`, `admin/ui.js`, `admin/badges.js`, `admin/overview-model.js`, `admin/shared.js`, all CSS, `legacy/users.js`, `views/users.js`, the Configuration page, global search, and all backend Java. No endpoint was added.

## How the count is derived

`badge = (distinct actionable admin-initiated requests) + (distinct self-deletion accounts)`

| Part | Request (existing, GET) | Counted | ID space |
|---|---|---|---|
| Admin-initiated, dual control | `/api/admin/deletion-requests?status=PENDING` and `?status=APPROVED` | status PENDING (awaiting approvals, including ones this admin already voted on) and APPROVED (awaiting execution). Rejected / executed / cancelled are not actionable. A missing `status` takes the list it came from. | dual-control **request** id |
| Self-requested, one-admin review | `/api/admin/self-deletion-requests` | every entry with a valid account id | **user account** id |

- Dedupe is within each id space only. A request that appears in both dual-control lists (approved between the two GETs) counts once. Duplicate account ids (number or numeric string) count once. Request 104 and account 104 are different items and both count; the Overview smoke fixture has exactly this overlap and expects 5, not 3.
- A "valid" id is a positive safe integer (the same `parseUserId` rule the Users drawer and the self queue use). Entries that are not objects or have no valid id cannot be acted on, so they are skipped and counted in `ignored`; they do not make the whole count unknown.
- The same person can legitimately appear once in each workflow (an admin requested their deletion and they also requested their own). That is two actionable items in two queues and counts as two.
- The tooltip on the badge (and `data-badge-state`) lists the parts separately, e.g. "4 open: 2 admin-initiated requests (dual control: 1 pending approval, 1 approved and awaiting execution) and 2 self-requested deletions (one-admin review)". On the page, each queue keeps its own count and explanation; a one-line note states the badge is a navigation shortcut only.

## When a source is unavailable

A count is published only from ONE round in which all three responses arrived and were well formed. Otherwise nothing from that round is used, including the sources that did succeed (no partial total).

"Failed" means: request rejected (network, 401/403, 4xx, 5xx), or body not an object, `success: false`, or `requests` missing / not an array (a plain-text reply counts).

| Situation | Badge | State (`data-badge-state`) |
|---|---|---|
| All three OK | combined count; hidden only if it is a real 0 | `known` |
| Any source fails, a previous complete count exists | previous count unchanged; shell store untouched; tooltip says it may be out of date | `stale` |
| Any source fails, no complete count yet | `?` (visible), tooltip "count unavailable"; shell store never receives a number | `unknown` |

A failure is logged with `console.warn`, never `console.error`. It never becomes 0, never a dual-control-only or self-only subtotal.

Recovery happens on the next complete round, which is triggered by: arrival on the Deletion requests page; a successful self-queue load while the badge is not `known` (e.g. Retry); any workflow action below; an Overview load/refresh.

## Where the badge updates

- Dual-control vote, cancel, execute (legacy script): they already `await refreshDeletionBadge()` after the POST succeeds, now combined. A failed POST refreshes nothing.
- Self-requested permanent deletion and restore: after server confirmation, via the new `refresh.badge` hook. Failed, cancelled and mismatched-confirmation actions refresh nothing.
- Self request already handled by someone else (the queue's "stale" outcome): resync, because the server just showed the queue changed.
- Legacy "request deletion" from Users (`legacy/users.js`): unchanged, still calls `refreshDeletionBadge()`.
- Boot: `admin.js` still calls `refreshDeletionBadge()` before the shell exists. It now does `import("../people/deletion-badge.js")` from the classic script (relative to the script's own URL, `/admin/legacy/`), which creates the per-document instance at `window.adminDeletionBadge`.
- Overview: no extra requests. It already loads the three sources and passes them over once all three have settled (never while one is loading).

## Calls and loops

- At most one round (3 GETs) in flight per page. Callers that need to see their own change share ONE follow-up round no matter how many ask; arrival joins a round already running (so landing on the page during the boot round costs no second round).
- No timers or polling. Observed: after settling, request count stays flat (asserted for 200 ms in jsdom).
- An observation older than the one already shown is ignored (an older Overview response cannot overwrite a newer page refresh); a stale failure cannot downgrade a newer complete count.
- Cost: each deletion workflow action now triggers 3 GETs instead of 1 (the previous pending-only refresh). On arrival, the page's own 2 GETs are joined by the badge round.

## Expected behaviour change (please review)

1. The badge now shows pending + approved + self-requested. Before: pending dual-control only. In `smoke-admin-overview.mjs` the fixture has 2 pending + 1 approved dual-control and 2 unique self requests, so the expectation moved `2` -> `5`.
2. When a source fails at boot the badge shows `?` instead of staying hidden.
3. The Overview "Needs attention" box and the badge can differ when a self-request entry has an unusable id (e.g. `"abc"`): the Overview group (not modified) counts any non-empty id, the badge counts valid account ids only, matching what the queue can act on.

## Verification actually performed

Environment: Node 22, jsdom 30.1.3 (installed outside the repo), Playwright with headless Chromium 141. No Spring backend, no authenticated session; only `/api/*` is mocked. Nothing was checked by a human in a visible browser, and no screenshots were reviewed.

Run: `node --experimental-default-type=module scripts/test-deletion-badge.mjs` (needs jsdom) and `node --experimental-default-type=module scripts/smoke-deletion-badge.mjs` (needs playwright + Chromium).

- `test-deletion-badge.mjs`: 18/18 scenarios, 211 assertions. Covers combined count, each workflow's successful update (vote, execute, cancel, self delete, self restore) with the number of badge rounds, failed/cancelled actions, stale outcome, duplicates inside and across id spaces, junk entries, every malformed body shape for every source, endpoint failure for every source, `?` / stale / recovery, coalescing, joining, ordering, boot-path with no view mounted, and the Overview (no extra requests, no partial total while loading, failure, malformed, retry of a single source).
- `smoke-deletion-badge.mjs`: 14/14 in Chromium, including the real boot `import()` from the classic script on a route with no badge-aware view, and that this check fails if the import path is wrong (verified by mutation).
- Mutation checks (temporary breakages, reverted): cross-id-space dedupe, a failed request treated as an empty list, older observations overwriting newer ones, legacy refresh removed, self-queue hook removed, Overview reverted to pending-only, arrival no longer joining. Each is caught by at least one test.
- Existing suites after the change (also re-run from the original zip with this package applied on top): `test-self-deletion-queue` 14/14, `test-admin-overview` 214, `test-admin-contracts`, `test-admin-shell` 242, `test-report-reliability` 140, `test-study-views` 143, `test-people-users` 62, `test-ai-testing-lifecycle` 16/16; `smoke-self-deletion-queue` 8/8, `smoke-people-users` 15/15, `smoke-admin-shell` 51/51, `smoke-report-reliability` 20/20, `test-admin-ui-hardening` passed (Playwright driver).

## Failures that exist without my change (compared against the unmodified zip)

- `smoke-study-views.mjs`: 3 checks fail (18/21), identically and every time on the original zip (Study views, not part of this task).
- `smoke-admin-overview.mjs`, check "inbox item navigates through the router": intermittent on the original zip too (it reads the page title right after the hash changes, before the lazy route has finished). With my change the rest is 47/48 to 48/48 depending on the run.

## Not verified

- A real Spring backend, real sessions, or multi-admin concurrency.
- Visual appearance of the `?` badge and tooltip (no screenshots reviewed). `?` is a text glyph on a plain span; screen-reader wording was not tested.
- Behaviour in Firefox/Safari. `import()` from a classic script resolves relative to the script's URL per spec; only Chromium was exercised.
