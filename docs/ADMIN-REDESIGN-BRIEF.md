# Admin Redesign — Authoritative Brief

**Purpose:** redesign the admin interface around admin tasks, improve wayfinding, reduce tab overload, make destructive actions safer, and preserve existing study data workflows. This file supersedes earlier task prompts that treated the main goal as simply converting individual legacy tabs into modules.

## Design goal

Move from 10 horizontally scrolling tabs and a separate `admindashboard.html` into one admin application with six persistent sidebar sections and task-based sub-pages. Keep the app vanilla JavaScript/HTML/CSS; no new framework or build step.

## Information architecture

- **Overview**: Needs attention, platform KPIs, recent activity. Eventually combines the useful unique cards and quick actions currently on `admindashboard.html` with the Overview tab.
- **People**: Users (table plus detail drawer), Deletion requests (admin-initiated approvals and user self-deletion requests), Banned emails.
- **Content**: Materials (current Topics & Content > Uploaded Materials), Reported questions, Categories (current Topics & Content > Categories).
- **Study**: Participants, Assigned handouts, Data exports.
- **System**: Usage limits, Configuration, AI testing, Reliability.
- **Audit**: Activity log, Printable platform report.

Navigation has six top-level items, with sub-pages under the appropriate item. Use a persistent left sidebar, page title, breadcrumb, and badges for outstanding work. The sidebar is expanded by default and becomes a compact/hamburger navigation on narrow screens. Avoid horizontal scrolling tabs.

## Canonical URL model

This is a static Spring Boot app. Use **hash routes** so deep links do not require a server-side SPA fallback. The URL format is `admin.html#/people/users`, and the route table stores the internal path without `#`.

Canonical paths:

- `/overview`
- `/people/users` and `/people/users/:id`
- `/people/deletion-requests`
- `/people/banned-emails`
- `/content/materials`
- `/content/reported-questions`
- `/content/categories`
- `/study/participants`
- `/study/assigned-handouts`
- `/study/data-exports`
- `/system/usage-limits`
- `/system/configuration`
- `/system/ai-testing`
- `/system/reliability`
- `/audit/activity-log`
- `/audit/platform-report`

Support old incoming hashes (`#overview`, `#users`, `#topics`, `#bannedemails`, `#deletionrequests`, `#reports`, `#activitylog`, `#aitesting`, `#reportedquestions`, `#assignedhandouts`) as aliases. Preserve existing quick-action links as compatible entry points. Do not use URL paths like `/admin/users`, because the server currently serves static `.html` pages and does not provide a protected SPA fallback.

## Existing screen mapping for the low-risk shell phase

The initial shell/navigation phase should reuse current renderers unchanged, through the existing `admin/views/*.js` adapters or an equally small compatibility bridge. Do not rewrite every screen in the first pass.

| Canonical page | Existing behavior to reuse initially |
|---|---|
| Overview | `renderOverview()` |
| People > Users | `renderUsers()` |
| People > Deletion requests | `renderDeletionRequests()`; user self-deletion queue is a later enhancement |
| People > Banned emails | `renderBannedEmails()` |
| Content > Materials | `renderTopics()` then select `materials` sub-tab |
| Content > Categories | `renderTopics()` then select `categories` sub-tab |
| Content > Reported questions | `renderReportedQuestions()` |
| Study > Participants | `renderReports()` then focus `#participantsCard` |
| Study > Assigned handouts | `renderAssignedHandouts()` |
| Study > Data exports | `renderReports()` and focus the existing export controls (`#printExclude`) |
| System > Usage limits | Render Users and invoke the existing global `openDailyLimitsModal()` after the view is ready |
| System > AI testing | `renderAiTesting()` |
| System > Reliability | Initially link to/focus the existing Reliability CSV export; a full reliability view is a later phase |
| System > Configuration | The backend API exists, but there is no UI; mark it as not yet implemented until its dedicated phase rather than pretending a feature exists |
| Audit > Activity log | `renderActivityLog()` |
| Audit > Platform report | `renderReports()` and focus `#printTarget` |
| User detail | The drawer is a later phase; the existing `user-detail.js` depends on an endpoint that does not exist yet |

Because multiple routes intentionally reuse `renderReports()` or `renderTopics()`, the router needs a safe route-specific action after the legacy renderer finishes (for example, select the requested sub-tab or scroll the target into view). Do not create duplicate copies of these screens.

## Shared view/runtime contract

Each independently migrated view exports:

```js
export default {
  mount(el, ctx) {},
  unmount() {}
};
```

`ctx = { params, query, api, ui, router, session, badges }`.

The router may keep a compatibility bridge during phase 2, but it must own navigation, update the hash route, load/mount one view, call the previous view's `unmount()` before replacement, and support Back/Forward. Do not remove legacy global renderers or the AI Testing upload lifecycle until the replacement view passes parity tests.

## Safety, accessibility, and data constraints

- Keep vanilla JavaScript, static resource serving, and current Railway deployment assumptions.
- Do not change the consent, participants, identity-key, assigned-handout, or CSV export API contracts during the shell/navigation phase. These are live study workflows.
- Keep existing admin authorization checks. A hidden sidebar item is not security; backend controllers remain authoritative.
- Keep AI Testing upload cancellation and cleanup: active upload ID, cancellation endpoint, `pagehide`, `beforeunload`, and upload progress. Fix the known `loadAitPreviewFrame()` undefined `el` bug without weakening cleanup.
- The accessible shared components should use native `<dialog>` where appropriate, support Escape, preserve/restore focus, have clear accessible labels, and provide status text not color alone.
- Replace `prompt()`, `confirm()`, and `alert()` incrementally with shared UI components; do not replace them in bulk unless each flow's behavior is preserved.
- Account status is derived as `ACTIVE < FLAGGED < SUSPENDED < BANNED < DELETION_PENDING`; “Deleted” is an audit event, not a current row status. “Suspend”, “ban email”, and “request deletion” are distinct concepts.
- High-impact actions must clearly describe impact and require the proper confirmation. Client-side confirmation is not authorization.
- Do not redirect or remove `admindashboard.html` until unique dashboard features have been inventoried and either merged into Overview or explicitly preserved as legacy access. Do not cause loss of any current dashboard feature just to remove the second entry point.

## Implementation phases

1. **Extract shared CSS/UI with behavior unchanged; fix the AI Testing preview bug.** Keep the old admin screen available. Freeze function signatures and shared tokens.
2. **Build the sidebar shell and hash router; regroup existing screens without rewriting their internal business logic.** Add old-hash aliases and ensure browser Back/Forward works. Merge/port unique dashboard content before redirecting `admindashboard.html`; validate login and `AuthInterceptor` redirect paths before changing them.
3. **Improve People UX:** user detail drawer, unified status display, self-deletion queue, consistent confirmation tiers and undo where a reverse endpoint exists.
4. **Backend data correctness and workflows:** server-side user pagination/search/status, summary/inbox counts, detail payload, targetable/paginated activity log, overview needs-attention inbox and global search. Add fields additively so legacy consumers continue working.
5. **System/accessibility completion:** configuration UI with key-specific validation, a Reliability view beyond CSV, and WCAG/accessibility review across views.

Phase 1/2 are the current objective. Do not burn tokens implementing phases 3–5 before the navigation shell and compatibility strategy are reviewed.

## Definition of done for the current phase

- Six top-level sidebar sections and correctly grouped sub-pages are visible and understandable.
- No horizontal scrolling tab strip remains in the production admin shell.
- Every existing current tab feature remains reachable and works through the new navigation.
- Existing old hash links still lead to the equivalent page.
- Back/Forward, refresh on a hash deep link, login, access denied, and logout/auth behavior work.
- Participant enrollment, consent/data access, identity-key handling, all CSV/TXT exports, assigned handouts, and AI Testing cleanup are preserved.
- No route claims to provide configuration or a full reliability screen before those screens exist.
- Syntax and repeatable browser checks pass. Report Maven/network blockers honestly.
