# Claude 3 – Ctrl/Cmd+K admin navigation search

Source of truth: `AI-Adaptive-Repo-phase3-integrated-2026-10-24.zip`. Paths below are relative to the repository root (the folder inside the uploaded zip, `AI-Adaptive-Repo-main/`).

## Files

New
- `src/main/resources/static/admin/search/search-model.js` – pure matching/ranking (no DOM).
- `src/main/resources/static/admin/search/palette-keys.js` – shortcut, IME and editable-target helpers (no DOM globals).
- `src/main/resources/static/admin/search/command-palette.js` – dialog/combobox UI, listeners, cleanup.
- `src/main/resources/static/admin/search/command-palette.css` – palette + topbar trigger styles.
- `scripts/test-admin-command-palette.mjs` – Node-only tests (174 checks).
- `scripts/smoke-admin-command-palette.mjs` – Chromium tests (44 checks).

Modified (small)
- `src/main/resources/static/admin.js` – one new function `initAdminCommandPalette(routesModule)` (+ one call after `adminRouter.start()`, 25 added lines). It dynamically imports the module in its own try/catch, so a failure cannot affect the shell.
- `src/main/resources/static/admin.html` – one `<link>` for the CSS and one `hidden` trigger button (`#adminSearchTrigger`) in the topbar.

Not touched: `admin/routes.js`, `admin/router.js`, People views, Overview, Study, Configuration, badge code.

## What the palette searches

- **Every static route in the registry**, read from `routes`/`sections`/`aliases` passed in by `admin.js` and re-read on every open. There is no second route list; a route added to `routes.js` appears automatically. A test asserts the palette's destinations equal the sidebar's, in order.
- **Match fields:** page title/label, section name ("people", "study" list that section), the registry's old-hash aliases (`reports`, `deletionrequests`, `aitesting`, …), words from the path, and an optional `keywords: string[]` on a route record **if the registry ever adds one** (supported, currently unused – Claude 2 can add it without touching the palette).
- **Parameterised route (User detail, `/people/users/:id`)** is not listed. It is offered only when the query supplies a value: `user 12`, `users #12`, `/people/users/12`. Each candidate is validated with `router.resolve()`, so only paths the router accepts are offered. The record is **not** looked up; the entry says so.
- **Status tags from the registry:** "Current page", "Not built" (Configuration), "Partial" (Reliability), "Opens dialog" (Usage limits).
- **Action:** "Reload current page" (`router.reload()`), supplied by `admin.js`. Actions are an option, not hard-coded in the module.
- Selection calls `adminRouter.navigate(path)` with the canonical path: hash + history entries behave as for sidebar links (Back/Forward verified).

## What it deliberately does not search

No users, materials, categories, questions, reports, handouts, participants, log entries, banned emails or deletion requests. There is no global search endpoint, and the only list-style data already in the page (`adminData.users`, loaded unbounded by existing code) is not a bounded, purpose-built search source, so it is not used. The palette makes **no network request** (tested) and says in its footer: "Searches admin pages and actions only…". Entity search needs a new bounded backend endpoint (query + limit, authorization, audit) – out of scope here.

No fuzzy/typo matching: matching is exact/prefix/substring on words.

## Behavior notes

- Opens with Ctrl+K (Windows/Linux) or Cmd+K (macOS; detected via `userAgentData.platform`/`navigator.platform`). Only the platform's own modifier counts (Ctrl+K on macOS is left to text fields). Topbar button also opens it (touch/mouse); it is shown only after the shell and palette are up (never for non-admins).
- Not intercepted when: focus is in a text input/textarea/contenteditable/known editor (`.monaco-editor`, `.cm-editor`, `.CodeMirror`, `.ProseMirror`, `.ql-editor`, `[role=textbox]`); IME composition is active; the event was already `preventDefault`ed by a component; another modal is open (native `<dialog[open]>`, legacy `#modal`, `#crModal.open`, other `aria-modal`). Consequence: Ctrl+K inside a page's own filter box does nothing – use the button or move focus first.
- While open: capture-phase key handling means the legacy document-level Escape handlers (mobile nav drawer, review modal) do not also fire. Tab cycles Close ↔ input; focus that escapes is pulled back. Enter/arrows/Escape are ignored during IME composition. Enter on the Close button closes instead of activating a result.
- Escape/Close/backdrop/same shortcut/Back-button (`hashchange`) close it; focus returns to the opener (fallback: trigger button, then page heading). On selection focus is restored first, then the router moves it to the new page heading as it already does.
- Rendering uses `createElement`/`textContent` only (no `innerHTML`; a test greps the modules). Highlighting uses `<mark>` nodes.
- Cleanup: `initCommandPalette()` is a singleton – a repeat call destroys the previous instance first. All listeners use one `AbortSignal`; `destroy()` removes them, the DOM, and re-hides the trigger. `pagehide` closes it and destroys it unless the page enters bfcache (`persisted`).
- Print: palette and trigger hidden.

## What was actually tested

Run from repo root (`PLAYWRIGHT_BROWSERS_PATH`/`NODE_PATH` set for the browser test in this environment):
- `node scripts/test-admin-command-palette.mjs` → 174 checks passed: matching, ranking, aliases from the real registry, keywords, dynamic user-detail validation (incl. hostile/over-long/malformed values), actions, hostile text, highlight ranges, shortcut/IME/editable helpers, static wiring checks, router accepts every palette target.
- `node scripts/smoke-admin-command-palette.mjs` → 44/44 passed in headless **Chromium**, three consecutive runs. Part A: module on a test page with the real registry and real router (open/focus/semantics, Escape + focus restore, Close/backdrop, arrows/wrap/Enter, pointer, Back/Forward, no-results and empty-registry states, focus trap, editable/IME/handled-event/other-modal non-interception, Cmd vs Ctrl, hostile query and hostile registry text, user-detail entry, throwing/rejecting navigate, repeated init = one set of live listeners, destroy, pagehide, no network). Part B: the real `admin.html` with `/api/*` mocked: wiring, sidebar parity, "ban"+Enter→Banned emails with Back/Forward, no API requests during search, user detail, Usage limits modal, Reload action, Escape not closing the mobile drawer, 380px layout, mac platform, non-admin, print, and shell still working when the palette module fails to load.
- Existing suites after the change: `test-admin-shell` (242), `test-admin-contracts`, `test-admin-overview` (214), `test-report-reliability` (140), `smoke-admin-shell` 51/51, `smoke-admin-overview` 48/48, `smoke-report-reliability` 20/20, `smoke-people-users` 15/15, `smoke-self-deletion-queue` 8/8, `test-admin-ui-hardening` pass.

## Not verified / known issues

- **No authenticated browser verification.** There is no Spring backend or real session in any test; the API is mocked.
- Chromium only. Not run in Firefox/Safari, on a real Mac keyboard (Cmd+K is tested by overriding the reported platform and sending Meta+K), on a real touch device, or with a screen reader. ARIA semantics (dialog, combobox, listbox, `aria-activedescendant`, live status) follow the WAI-ARIA 1.2 pattern and are asserted in DOM, but not heard.
- Pre-existing, unrelated: `smoke-study-views` fails 3 checks identically on the untouched baseline zip (participants/exports focus target, legacy Platform report). `test-people-users`, `test-study-views`, `test-self-deletion-queue` DOM scenarios skip because jsdom is not installed. `smoke-admin-overview` had one failure ("inbox item navigates through the router") on one baseline run and passed on the others – looks timing-dependent, not related to this change.
- During development one run of my own test saw the palette fail to reopen right after a bfcache-style `pagehide` (~1 in 6 in a stripped-down reproduction). I added a defensive fix (blur the input when closing; never record the palette's own input as the focus-return target); it did not recur in 3 full runs plus 12 reproduction runs, but I could not prove the exact cause of the original flake.
- "Reload current page" re-renders the current route like any navigation; unsaved in-page form input is lost, as with other route changes.
