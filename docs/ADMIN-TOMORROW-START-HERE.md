# Tomorrow's Work — Read This First

This is the current execution plan and supersedes the previous first-assignment plan. The real goal is the admin redesign described in `docs/ADMIN-REDESIGN-BRIEF.md`, not merely turning old tabs into modules.

Use `AI-Adaptive-Repo-audited-handoff.zip` / the enclosed Step 0 project as the shared baseline. Each Claude should work in its own copy/branch and return only its changed files in a ZIP preserving original relative paths, along with a short report. Do not merge anything automatically. ChatGPT will review each result and update the canonical project status.

## Three bounded tasks

1. **Claude 1 — Shared UI primitives/tokens**
   `docs/claude-prompts/CLAUDE-1-SHARED-UI.md`
   Owns `admin/ui.js`, `admin/admin-tokens.css`, and a new UI component stylesheet only. Keeps every public signature/return shape stable.

2. **Claude 2 — AI Testing preview bug and regression check**
   `docs/claude-prompts/CLAUDE-2-AITESTING-BUG.md`
   Owns `admin/legacy/aitesting.js` and one focused regression check. No shell or API changes.

3. **Claude 3 — Sidebar shell and hash-router compatibility**
   `docs/claude-prompts/CLAUDE-3-SHELL-ROUTER.md`
   Owns `admin.html`, `admin.js`, `admin/routes.js`, new `admin/router.js`, a new `admin/admin-shell.css`, and focused route tests. The first pass must reuse legacy renderers and preserve behavior, not rewrite all screens.

These tasks have disjoint primary file ownership. All must follow the route plan and constraints in `ADMIN-REDESIGN-BRIEF.md`.

## Important limits for tomorrow

- Do not implement the summary API, user detail API, pagination, unified status workflow, new deletion queue, or configuration UI in this first batch. They belong to later phases.
- Do not redirect `admindashboard.html` or alter `login.js`/`AuthInterceptor` until the new shell is verified and the old dashboard's unique features have been preserved. Treat that as the next integration task.
- Do not change participants, consent, identity-key, assigned-handout, or CSV/TXT export API contracts.
- Do not delete legacy renderer functions or the AI Testing cancellation path.
- Do not return a full reconstructed repository if only a few files changed. Return the changed files with relative paths plus test results.

## After they finish

Upload the three result ZIPs here, one at a time or together, plus their short reports. Do not let one account's ZIP overwrite another's files. I will compare the changes, run static checks, inspect any behavior-risking edits, and tell you what to merge and what the next three tasks should be.
