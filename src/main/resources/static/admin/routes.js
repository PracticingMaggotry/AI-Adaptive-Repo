/**
 * Admin shell route manifest (Phase 2).
 *
 * Internal route paths are stored WITHOUT the leading "#". The browser URL is
 * "admin.html#/people/users". Never use server paths such as "/admin/users":
 * the Spring Boot app serves static files and has no SPA fallback.
 *
 * Frozen record fields kept from Step 0: path, section, title, legacyTab, load.
 * Additive fields used by the shell:
 *   label     sidebar text (defaults to title)
 *   nav       false = route is reachable but has no sidebar entry
 *   navPath   sidebar entry to highlight (defaults to path)
 *   status    ready | shared | partial | not-implemented | unavailable
 *               ready           own legacy screen, nothing misleading
 *               shared          reuses a legacy screen that also serves other routes
 *               partial         only part of the promised page exists (Reliability: CSV export only)
 *               not-implemented no UI exists yet (reserved for future routes; Configuration now has its own view)
 *               unavailable     depends on a backend endpoint that does not exist (User detail)
 *   shellLoad what the production shell mounts; defaults to load. `load` stays the
 *             "real" module so admin-dev.html and the contract test can exercise it.
 *   after     post-render actions executed by the compatibility bridge in admin.js:
 *               subTab  select a sub-tab of the legacy screen (Topics: materials | categories)
 *               focus   CSS selector inside #adminContent to scroll to and focus
 *               modal   name of a global legacy function that opens a modal
 *               notice  { type: "info" | "warning", text } shown above the content
 */

export const sections = Object.freeze([
  { id: "overview", label: "Overview" },
  { id: "people", label: "People" },
  { id: "content", label: "Content" },
  { id: "study", label: "Study" },
  { id: "system", label: "System" },
  { id: "audit", label: "Audit" }
]);

/**
 * Old hashes ("admin.html#users") -> canonical route path.
 * Keys are lower-case, without "#" and without slashes.
 */
export const aliases = Object.freeze({
  overview: "/overview",
  users: "/people/users",
  topics: "/content/materials",
  reportedquestions: "/content/reported-questions",
  bannedemails: "/people/banned-emails",
  deletionrequests: "/people/deletion-requests",
  reports: "/audit/platform-report",
  activitylog: "/audit/activity-log",
  assignedhandouts: "/study/assigned-handouts",
  aitesting: "/system/ai-testing"
});

export const defaultPath = "/overview";

const viewLoaders = {
  overview: () => import("./views/overview.js"),
  users: () => import("./views/users.js"),
  userDetail: () => import("./views/user-detail.js"),
  deletionrequests: () => import("./views/deletionrequests.js"),
  bannedemails: () => import("./views/bannedemails.js"),
  topics: () => import("./views/topics.js"),
  reportedquestions: () => import("./views/reportedquestions.js"),
  // Legacy adapter around renderReports(). Kept for compatibility only: no route mounts it any more
  // (Platform report and Reliability have dedicated views below).
  reports: () => import("./views/reports.js"),
  platformReport: () => import("./views/platform-report.js"),
  reliability: () => import("./views/reliability.js"),
  studyParticipants: () => import("./views/study-participants.js"),
  studyDataExports: () => import("./views/study-data-exports.js"),
  assignedhandouts: () => import("./views/assignedhandouts.js"),
  aitesting: () => import("./views/aitesting.js"),
  activitylog: () => import("./views/activitylog.js"),
  configuration: () => import("./views/configuration.js")
};

export const routes = [
  { path: "/overview", section: "overview", title: "Overview", legacyTab: "overview", status: "ready", load: viewLoaders.overview },

  { path: "/people/users", section: "people", title: "Users", legacyTab: "users", status: "ready", load: viewLoaders.users },
  {
    path: "/people/users/:id", section: "people", title: "User detail", label: "User detail", nav: false,
    navPath: "/people/users", legacyTab: "users", status: "ready",
    load: viewLoaders.userDetail, shellLoad: viewLoaders.users
  },
  { path: "/people/deletion-requests", section: "people", title: "Deletion requests", legacyTab: "deletionrequests", status: "ready", load: viewLoaders.deletionrequests },
  { path: "/people/banned-emails", section: "people", title: "Banned emails", legacyTab: "bannedemails", status: "ready", load: viewLoaders.bannedemails },

  { path: "/content/materials", section: "content", title: "Materials", legacyTab: "topics", status: "shared", load: viewLoaders.topics, after: { subTab: "materials" } },
  { path: "/content/reported-questions", section: "content", title: "Reported questions", legacyTab: "reportedquestions", status: "ready", load: viewLoaders.reportedquestions },
  { path: "/content/categories", section: "content", title: "Categories", legacyTab: "topics", status: "shared", load: viewLoaders.topics, after: { subTab: "categories" } },

  { path: "/study/participants", section: "study", title: "Participants", status: "ready", load: viewLoaders.studyParticipants },
  // Own view, no legacyTab (see Reliability): with legacyTab set, renderCurrentTab() (reached through setCategoryRange() and
  // the in-place legacy actions) re-ran the legacy renderAssignedHandouts() over this view. The old "#assignedhandouts" hash still resolves here through `aliases`.
  { path: "/study/assigned-handouts", section: "study", title: "Assigned handouts", status: "ready", load: viewLoaders.assignedhandouts },
  { path: "/study/data-exports", section: "study", title: "Data exports", status: "ready", load: viewLoaders.studyDataExports },

  { path: "/system/usage-limits", section: "system", title: "Usage limits", legacyTab: "users", status: "shared", load: viewLoaders.users, after: { modal: "openDailyLimitsModal" } },
  { path: "/system/configuration", section: "system", title: "Configuration", status: "ready", load: viewLoaders.configuration },
  { path: "/system/ai-testing", section: "system", title: "AI testing", legacyTab: "aitesting", status: "ready", load: viewLoaders.aitesting },
  {
    // Own view, no legacyTab: renderCurrentTab() (setCategoryRange, in-place legacy actions) must never
    // re-render the legacy Reports screen over it. Export-only, so status stays "partial".
    path: "/system/reliability", section: "system", title: "Reliability", status: "partial", load: viewLoaders.reliability,
    after: {
      focus: 'a[href="/api/admin/export/reliability.csv"]',
      notice: {
        type: "info",
        text: "Reliability is export-only for now: the Reliability CSV is the only reliability feature. A full Reliability view is planned for a later phase."
      }
    }
  },

  { path: "/audit/activity-log", section: "audit", title: "Activity log", legacyTab: "activitylog", status: "ready", load: viewLoaders.activitylog },
  // Own view, no legacyTab (see Reliability). The old "#reports" hash still resolves here through `aliases`.
  { path: "/audit/platform-report", section: "audit", title: "Platform report", status: "ready", load: viewLoaders.platformReport, after: { focus: "#printTarget" } }
];

export default routes;
