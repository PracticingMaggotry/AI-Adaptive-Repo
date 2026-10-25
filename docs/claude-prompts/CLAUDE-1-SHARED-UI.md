# Claude 1 — Shared UI primitives and tokens (Phase 1)

You are implementing one bounded task in an existing vanilla JavaScript/Spring Boot admin redesign. Read `docs/ADMIN-REDESIGN-BRIEF.md` and `docs/admin-contract-freeze.md` first. I am a beginner; make the technical changes, but do not redesign unrelated features.

## Own only these files
- `src/main/resources/static/admin/ui.js`
- `src/main/resources/static/admin/admin-tokens.css`
- `src/main/resources/static/admin/admin-components.css` (create if needed)

Do not edit `admin.html`, `admin.js`, routes, view modules, legacy renderers, Java backend, or shared contracts.

## Task
Audit the existing shared UI module and make the components consistent, accessible, and token-driven without changing their public signatures or return shapes:

- `ui.modal({title, body, actions, size}) -> Promise<result>`
- `ui.drawer({title, content, onClose}) -> controller`
- `ui.toast(msg, {type, undo})`
- `ui.confirm({tier, title, message, impact, typeToConfirm, reasonRequired}) -> Promise<{confirmed, reason}>`
- `ui.table({columns, fetch, rowKey, onRowClick, selectable}) -> controller`
- `ui.statusBadge(accountStatus)`
- `ui.empty({title, action})`

Use the existing CSS tokens where appropriate and add only useful missing tokens. Prefer native `<dialog>` for modal behavior. Ensure keyboard close via Escape where appropriate, clear labels, sensible focus placement/restoration, status announcements, and no accidental HTML injection from user/server strings. Preserve Undo behavior and cancellation return values. Do not add a new framework or build step.

If any behavior is ambiguous, preserve current behavior and document the question instead of silently changing the contract.

## Verify
Run syntax checks and the existing contract test if available. Report exactly what you ran and any limitations. Return a ZIP containing only changed/new files with original relative paths, plus a brief change report. Do not claim tests pass unless run.
