# Self-requested deletion queue: integration notes

Task: add self-requested account-deletion management to `#/people/deletion-requests`, keeping the admin-initiated dual-control workflow intact. No route, router, shell, Overview, shared-UI or backend file was edited.

## Files (repo-relative)
- `src/main/resources/static/admin/views/deletionrequests.js` (modified): adapter. Still mounts the legacy `renderDeletionRequests` unchanged, then decorates it (label, accent, retitled card, workflow guide) and mounts the self queue below it. A failure in the self queue cannot stop the dual-control list from rendering.
- `src/main/resources/static/admin/people/self-deletion-queue.js` (new)
- `src/main/resources/static/admin/people/self-deletion-queue.css` (new)
- `scripts/test-self-deletion-queue.mjs` (new): jsdom regression test, 14 scenarios, 88 assertions
- `scripts/smoke-self-deletion-queue.mjs` (new): real-Chromium smoke, 8 checks, mocked API

## Endpoints used (all existing; none invented)
- `GET  /api/admin/self-deletion-requests` -> `{ requests: [{ id, fullName, originalEmail, requestedAt }] }`
- `POST /api/admin/self-deletion-requests/{id}/execute`
- `POST /api/admin/self-deletion-requests/{id}/restore`

Dual-control endpoints are still used only by the legacy renderer: `/api/admin/deletion-requests` (list, vote, cancel, execute). POSTs go through `ctx.api.request`, so the CSRF header is added.

## How IDs are interpreted
`id` in the self-deletion list is the **user account ID**, not a deletion-request record. It is validated with `parseUserId` (positive safe integer) before being put in a path. Records without a valid ID are shown but cannot be acted on. Self-request IDs and dual-control request IDs are never compared or mixed; the tests use 104 for both. "View account" links to `#/people/users/{id}`.

## Confirmation behavior
- Permanent deletion: `ui.confirm({ tier: "destructive", typeToConfirm: "DELETE" })`. The text states the account will be irreversibly removed and that no other administrator votes on this step. Cancel, Escape, a wrong typed value, or navigating away while the dialog is open all send nothing.
- Restore: a separate `ui.confirm` (standard tier, no typing) stating the user's access is restored.
- Only one action runs at a time; further clicks are ignored while one is in flight.

## Failure handling
- Items leave the list only after server success, or after a fresh list no longer contains them.
- Stale (404, or 400 "No pending self-deletion request…"): toast, then reload; the row disappears only if the server no longer lists it.
- Restore email collision (400 "…already used by a newer account…"): item stays, server message shown inline as text, deletion remains available, nothing is refreshed.
- Any other failure: inline error plus toast, item kept, retry possible.
- After success: local removal, authoritative reload, then `refreshActivityLog` and `refreshUsers` (the `admin.js` globals, skipped if absent). A failed refresh never turns a completed action into a failure.
- All server text is rendered with `textContent`.

## Verification actually performed
- jsdom scenarios pass (empty/error/retry, execute, restore, restore email collision, stale, repeated clicks, injection, unmount). Mutation checks confirmed the test fails if items are removed on failure, `DELETE` is dropped, or the click guard is removed.
- Real Chromium smoke 8/8 against the production `admin.html` with a mocked API; desktop and 390px screenshots reviewed.
- Existing contract, shell, overview, people-users, study-views and AI-testing jsdom/contract suites pass.
- NOT performed: Spring backend, authenticated session, screen-reader or keyboard audit. `test-admin-ui-hardening` skipped (could not load a browser driver).
- Existing browser smoke tests are timing-flaky: `smoke-study-views` has 2 failures identical on the original zip; `smoke-admin-shell` fails one varying check in roughly 1 of 3-4 runs on both trees; `smoke-admin-overview` failed once and passed twice serially; `smoke-people-users` failed "refresh on the detail hash reopens the same user" in 2 of 5 runs on this tree and 0 of 3 on the original. The Users view was not modified, but that last result is unresolved.

## Unresolved limitations
- Sidebar badge `people.deletion-requests` still counts admin-initiated requests only (shared with Overview). Self-request count appears only in the section heading and guide. Shell/Overview owner may decide to combine.
- Overview inbox does not list self-requests (Claude 3's area).
- Confirm dialogs render top-left: `dashboard.css` `* { margin: 0 }` cancels the browser's `margin: auto` on `<dialog>`. Suggested shared fix: `.admin-ui-dialog { margin: auto; }`. Affects other confirms too.
- `ui.confirm` validates the typed text after closing; a mismatch closes the dialog with a toast and the admin must click again.
- Deletion copy makes no claim about related data; what the backend cascades was not verified.
- The legacy dual-control error state still has no Retry button (left unchanged).
