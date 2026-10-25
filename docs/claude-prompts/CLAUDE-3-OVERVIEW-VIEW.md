# Claude 3 — First bounded task: standalone overview view

You are implementing one frontend view in my existing project. I am a beginner; make the code change and report how it was checked.

Read `docs/ADMIN-ARCHITECTURE-AUDIT.md`, `admin/views/overview.js`, `admin/legacy/overview.js`, `admin/shared.js`, `admin/ui.js`, `admin/routes.js`, `admin/mock-api.js`, `admin/account-status.js`, and the relevant parts of `admin-dev.html`.

**Task:** replace only `src/main/resources/static/admin/views/overview.js` with a real standalone view. Do not edit shared files, API backend files, `admin.html`, `admin.js`, routes, mocks, styles, or any other view.

Requirements:
1. Export the frozen interface `{ mount(el, ctx), unmount() }` and use `ctx.api`, `ctx.ui`, `ctx.router`, `ctx.session`, and `ctx.badges` where relevant. Do not rely on global `adminData`, global legacy renderer functions, or `document.getElementById('adminContent')`.
2. Preserve all current Overview behavior shown in `admin/legacy/overview.js`: platform stat tiles, banned/suspended/flagged/deleted-account summary, quick actions, activity metrics, material categorization, range filters (`week`, `month`, `all`), category/subcategory empty states, and charts.
3. Use existing backend endpoints for existing data. `/api/admin/summary` is being implemented separately and may be used for its five new counters, but do not remove the existing `/api/admin/overview` stats or material-category data. Handle API failure with sensible empty/error states.
4. Quick actions should use the approved router interface and route manifest where possible; do not assume path-based production routing is already enabled. Do not modify the production shell.
5. Clean up Chart.js instances, event listeners, and any other view resources in `unmount()`; mounting twice must not duplicate charts or handlers. Handle the case where Chart.js is unavailable in the harness gracefully.
6. Render user-controlled values safely; prefer DOM methods or escaping instead of unsafe interpolation.
7. Keep the legacy renderer untouched as the parity reference. Do not change shared contracts to make your module easier.
8. Run `node --check` and `node --experimental-default-type=module scripts/test-admin-contracts.mjs` if available, plus the mock development harness if your environment supports it. State exactly what you could verify.
9. Return only the changed file and a short report listing preserved behavior, tests actually run, and remaining integration dependencies. Prefer a downloadable file or small ZIP rather than a long code dump.
