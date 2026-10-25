# People → Users: detail drawer and account status

## Changed files
Modified
- `src/main/resources/static/admin/views/users.js` – Users adapter; now also hosts the drawer for `/people/users/:id`
- `src/main/resources/static/admin/views/user-detail.js` – delegates to `users.js` (the old version called `GET /api/admin/users/{id}`, which does not exist)
- `src/main/resources/static/admin/account-status.js` – evidence-based derivation (see below)
- `scripts/test-admin-contracts.mjs` – one assertion: the old one matched `deletionRequests` by `id`, which is a *request* id; it now uses `selfDeletionRequests: [{ id: 5 }]`
- `scripts/smoke-admin-shell.mjs` – one check: it asserted the "User detail is not available" placeholder; it now asserts the drawer (not-found for id 42)

New
- `src/main/resources/static/admin/people/users-data.js`, `user-drawer.js`, `user-drawer.css` (CSS is loaded by the module; no `admin.html` change)
- `scripts/test-people-users.mjs` (pure + jsdom), `scripts/smoke-people-users.mjs` (real Chromium, mocked API)

Not touched: `routes.js`, `router.js`, `admin.html`, `admin.js`, `ui.js`, shared CSS, Overview/Study views, `legacy/*`, backend.

## Existing endpoints reused (all via `ctx.api.get`, GET only from the drawer)
`/api/admin/users`, `/api/admin/banned-emails`, `/api/admin/self-deletion-requests`, `/api/admin/deletion-requests`, `/api/admin/users/{id}/daily-usage`.
Administrative actions (flag/unflag, suspend/restore, ban/unban email, promote, request deletion, uploads, usage and personal-cap editing) still run the existing legacy functions, so they hit the same endpoints with the same prompts. No new endpoint or field is used.

## Hash navigation
- `routes.js` sends `/people/users/:id` to `users.js` via `shellLoad`, so the shell mounts the adapter with `ctx.params.id`: the list renders first (synchronously, as before), the drawer opens on top.
- List names are real links (`#/people/users/ID`): history entry pushed, Back/Forward/refresh/direct link all resolve to the same canonical hash.
- Close (button, Escape, backdrop): if opened from the list in this session → `history.back()` (no new entry); otherwise (direct visit, refresh, Forward) → `router.navigate('/people/users', { replace: true })`.
- Search text, flag filter and scroll position survive list → detail → list; focus returns to the row. The remembered context is dropped when navigating to any non-Users page.
- Unknown/invalid id → "User not found" state; user-list fetch failure → error state with Retry; usage has its own loading/empty/error state.
- Legacy actions open `#modal`, which a modal `<dialog>` would cover. The drawer therefore steps aside (URL unchanged) and reopens with fresh data when the legacy modal closes (watched with a MutationObserver on `#modal`) or when `unflagUser` re-renders the list.
- `renderUsers` is wrapped while the view is mounted (restored in `unmount`). `admin.js` keeps working because it resolves `renderUsers` as a global.

## Account status
Order: `ACTIVE < FLAGGED < SUSPENDED < BANNED < DELETION_PENDING`; highest established condition wins. Used in the drawer and in the list's Status column (one badge per row; replaces the old multi-badge cell once the three status requests return).

| Status | Rule | Basis |
|---|---|---|
| FLAGGED | `user.flagged` | listing field |
| SUSPENDED | `user.archived` | listing field (set only by `archive-user` and self-deletion) |
| BANNED | current `user.email` is in `/banned-emails` | `AccountAccessService.checkEmail` blocks login/sessions for a banned email |
| DELETION_PENDING | `user.id` in `/self-deletion-requests` (its `id` is the user id) **or** `user.email` equals `targetEmail` of a PENDING/APPROVED `/deletion-requests` item | same lookups the backend uses |

"Deleted" is never a status (audit event only).

Limitations / uncertain
- If a source endpoint fails, the drawer shows an "uncertain" note for any higher-severity condition that could not be ruled out, instead of assuming it is absent. The list badge carries the same text as a tooltip.
- A pending *admin* deletion request does not restrict the account (the user can still log in); a *self* request suspends it. Both display DELETION_PENDING per the requested ordering.
- Bans match the current email only. A ban of a self-deleted user's original email is not applied to the placeholder-email account (it can matter again after a restore).
- Not shown because the backend does not return them: created date, who/when for flag or suspension, per-user activity (activity-log rows have no target field), upload counts (use the Uploads button).
- Fields in `api-contracts.json`/`mock-api.js` that the real backend lacks (`accountStatus`, `banned`, `suspended`, `deletionRequested`, `createdAt`) are ignored.
- Statuses come from four separate requests, so they are not an atomic snapshot.

## Behaviour changes to be aware of
- No pagination exists in the current list (the backend returns all users and filtering is client-side), so there was nothing to preserve; the paged contract in `api-contracts.json` is not implemented.
- Search text is now kept after list actions (previously reset).
- Drawer withholds flag/suspend/ban/promote/delete for self-deletion-pending accounts (their email is a placeholder; acting on it would leave an inconsistent state). Use Deletion requests. List-row buttons are unchanged.

## Outstanding integration requirements (outside my scope)
1. `routes.js`, route `/people/users/:id`: set `status: "ready"` and remove `after.notice`. Until then the adapter hides that stale notice itself (temporary workaround in `removeStaleNotice`). `test-admin-shell.mjs` (lines ~46, ~256) asserts the placeholder wiring and must be updated with that change; `shellLoad` can then be dropped in favour of `load` (`user-detail.js` already delegates).
2. `api-contracts.json` lists `GET /api/admin/users/{id}` as NEW; the drawer does not need it.
3. `admin-dev.html` / `mock-api.js`: its `accountStatus()` reads `user.deletionRequested`, which the real API does not return, and its self-deletion mock id (104) matches no mock user. Harness only.
4. `renderUsers`, `adminData`, `ACTION_TYPE_LABELS`, the legacy action functions, `#modal` and `closeModal` must stay global while the legacy renderer is hosted by `admin.js`.

## Verification
Run:
- `test-admin-contracts`, `test-admin-shell`: pass.
- `test-admin-ui-hardening` (Playwright): pass.
- `test-people-users` (62 assertions, 13 jsdom scenarios, with the real router, `ui.js` and `legacy/users.js`): pass. Needs `jsdom` (not in the repo); exits 2 if missing.
- `smoke-people-users` (15 checks, real Chromium, mocked API): pass. Covers deep link, push/close/Back/Forward/Escape/refresh, search kept, focus return, the legacy-modal parking, not-found, 390px width.
- `smoke-admin-shell`: 51/51 on most runs. It is flaky on the *unmodified* baseline too (3 of 8 runs failed there, in varying checks), and intermittently here.
- `node --check` on every changed JS file.

Not verified: live Spring backend, real authenticated session, scroll restoration on a long list (implemented via `window.scrollY`, untested), screen-reader behaviour, drawer error/usage-failure states in a real browser (covered in jsdom only).
