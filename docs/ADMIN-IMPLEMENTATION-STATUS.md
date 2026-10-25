# Admin Redesign Implementation Status

**Last updated:** 2026-10-24  
**Current source of truth:** `docs/integration/CURRENT-INTEGRATION-STATUS.md`  
**Detailed latest merge:** `docs/integration/PHASE3-INTEGRATION-REPORT.md`  
**Architecture/design source of truth:** `docs/ADMIN-REDESIGN-BRIEF.md`

## Implemented in the current integrated snapshot

- Shared UI primitives and table hardening, AI Testing preview regression fix, and upload lifecycle regression coverage.
- Six-section responsive shell, canonical hash router, old-hash aliases, Back/Forward, route lifecycle, and keyboard navigation.
- People > Users detail drawer, evidence-derived account status, and distinct admin-initiated dual-control and user-initiated self-deletion queues.
- Separate Study views for Participants, Assigned handouts, and Data exports.
- Overview with KPIs, recent activity, quick actions, and separate attention groups for reports, dual-control deletions, self-requested deletions, and flagged accounts.
- Dedicated Platform Report and Reliability views; Reliability is accurately marked export-only.
- Direct admin landing destinations to `admin.html#/overview`, while retaining the protected `admindashboard.html` redirect for existing links.

## Verification status

Locally rerun checks: 49 static JavaScript/MJS syntax checks; admin contracts; shell tests (242); Overview tests (214); AI Testing preview; AI Testing lifecycle (16); report/reliability (140 assertions); People pure status checks (28); self-deletion pure checks (14).

Browser tests were skipped in this environment because the Node Playwright package was unavailable. DOM suites were skipped because jsdom was unavailable. No Maven/Java, live backend, production-authenticated session, real CSV/print, screen-reader, or cross-browser tests were run. Claude-authored test reports are preserved separately and are not equivalent to independently reproduced local results.

## Remaining gaps

1. Run the browser and DOM suites with the needed dependencies, then run the project against a real authenticated Spring backend.
2. Confirm admin redirects in the real servlet container.
3. Decide whether the People sidebar deletion badge should include self-requested items while maintaining separate workflow counts.
4. Fix the two known Study smoke checks that also fail on the original baseline.
5. Implement global Ctrl+K search and a real Configuration page.
6. Audit duplicate API calls, confirmation tiers, keyboard/screen-reader accessibility, and large-data performance.

This archive is a development integration snapshot, not a production-verified release. Do not replace `src/main/resources/static/admin/routes.js` with an older ZIP's version.
