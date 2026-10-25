# Claude 2 — First bounded task: standalone activity log view

You are implementing one frontend view in my existing project. I am a beginner; make the code change and report how it was checked.

Read `docs/ADMIN-ARCHITECTURE-AUDIT.md`, `admin/views/activitylog.js`, `admin/legacy/activitylog.js`, `admin/ui.js`, `admin/routes.js`, `admin/mock-api.js`, and the relevant parts of `admin-dev.html`.

**Task:** replace only `src/main/resources/static/admin/views/activitylog.js` with a real standalone view. Do not edit shared files, API backend files, `admin.html`, `admin.js`, routes, mocks, styles, or any other view.

Requirements:
1. Export the frozen interface `{ mount(el, ctx), unmount() }` and use the supplied `ctx` (`ctx.api`, `ctx.ui`, `ctx.router`, etc.) instead of global `adminData` or `document.getElementById('adminContent')`.
2. Preserve the current Activity Log information and visual behavior: event-type icon/color mapping, message, detail, performer, timestamp, and the empty state. Escape/render user-controlled data safely; prefer DOM `textContent` over interpolated HTML.
3. Support both response formats during backend migration: new `{items,total,page}` and legacy `{success,log}`. Do not require the not-yet-implemented filtering endpoint to be live; where practical, handle filtering locally from the returned data until server filtering is implemented.
4. Add sensible loading and error states. `unmount()` must remove listeners and cancel any pending work/resources the view creates.
5. Keep the legacy renderer untouched as the parity reference. Do not change global contracts to make your module easier.
6. Use the development harness with mock data and run `node --check` and `node --experimental-default-type=module scripts/test-admin-contracts.mjs` if available. Be honest if browser/Maven tools are unavailable.
7. Return only the changed file and a short report listing preserved behavior, tests actually run, and remaining integration dependencies. Prefer a downloadable file or small ZIP rather than a long code dump.
