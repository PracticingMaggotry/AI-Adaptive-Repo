/**
 * Data model for Audit > Platform report. Pure and import-safe in Node: no DOM access.
 *
 * These are the SAME sources and data meanings the legacy renderReports() used (it read the shared
 * adminData cache that admin.js fills in initAdmin()); the dedicated view reads them directly so it does
 * not depend on that cache or on any admin.js global:
 *
 *   stats                GET /api/admin/overview                   -> .stats
 *   users                GET /api/admin/users                      -> .users  (archived, flagged, isAdmin, fullName, email)
 *   topics               GET /api/admin/topics                     -> array of topic names
 *   categories           GET /api/admin/material-categories?range= -> categoryBreakdown / subCategoryBreakdown / topCategory / ...
 *   banned emails        GET /api/admin/banned-emails              -> .bannedEmails
 *   activity log         GET /api/admin/activity-log               -> .log   (entries with type === "delete")
 *
 * Nothing new is computed beyond what the legacy report computed. Unlike the legacy cache (which swallowed
 * failures into zeros/empty lists), a failed source is reported as failed so the report never prints a
 * fabricated "0" or "None".
 */

export const REPORT_ENDPOINTS = Object.freeze({
  stats: "/api/admin/overview",
  users: "/api/admin/users",
  topics: "/api/admin/topics",
  categories: (range) => `/api/admin/material-categories?range=${encodeURIComponent(range)}`,
  banned: "/api/admin/banned-emails",
  activity: "/api/admin/activity-log"
});

export const CATEGORY_RANGES = Object.freeze([
  { key: "week", label: "This Week" },
  { key: "month", label: "This Month" },
  { key: "all", label: "All Time" }
]);

/** Verbatim legacy wording. */
export function rangeLabel(range) {
  return range === "week" ? "This Week (last 7 days)" : range === "month" ? "This Month (last 30 days)" : "All Time (universal timeline)";
}

export function normalizeRange(range) {
  return range === "week" || range === "month" ? range : "all";
}

const EMPTY_CATEGORIES = Object.freeze({ categoryBreakdown: [], subCategoryBreakdown: [] });

/** One fetch per source; each settles to { ok, data, error } so one failure never hides the others. */
async function settle(promise, pick) {
  try { return { ok: true, data: pick(await promise) }; }
  catch (error) { return { ok: false, data: null, error }; }
}

const isObject = (v) => v !== null && typeof v === "object";

export function loadCategories(api, range, options) {
  return settle(api.get(REPORT_ENDPOINTS.categories(range), options), (r) => {
    if (!isObject(r)) throw new Error("Unexpected response from the server.");
    return r;
  });
}

export async function loadReportData(api, range, options = {}) {
  const get = (path) => api.get(path, options);
  const [stats, users, topics, categories, banned, activity] = await Promise.all([
    settle(get(REPORT_ENDPOINTS.stats), (r) => { if (!isObject(r)) throw new Error("Unexpected response from the server."); return r.stats || {}; }),
    settle(get(REPORT_ENDPOINTS.users), (r) => { if (!isObject(r)) throw new Error("Unexpected response from the server."); return r.users || []; }),
    settle(get(REPORT_ENDPOINTS.topics), (r) => (Array.isArray(r) ? r : [])),
    loadCategories(api, range, options),
    settle(get(REPORT_ENDPOINTS.banned), (r) => { if (!isObject(r)) throw new Error("Unexpected response from the server."); return r.bannedEmails || []; }),
    settle(get(REPORT_ENDPOINTS.activity), (r) => { if (!isObject(r)) throw new Error("Unexpected response from the server."); return r.log || []; })
  ]);
  return { stats, users, topics, categories, banned, activity };
}

export function failedSources(data) {
  return Object.entries(data || {}).filter(([, s]) => s && s.ok === false).map(([name]) => name);
}

/** Section 1 rows: [label, value] — labels and order are the legacy ones; missing values read 0 as before. */
export function statRows(stats) {
  const s = stats || {};
  return [
    ["Total Registered Users", s.totalUsers ?? 0],
    ["Total Topics", s.totalTopics ?? 0],
    ["Total Questions", s.totalQuestions ?? 0],
    ["Total Quiz Attempts", s.totalAttempts ?? 0],
    ["Total Uploaded Materials", s.totalMaterials ?? 0]
  ];
}

export function categoryRows(mc) {
  const m = mc || EMPTY_CATEGORIES;
  return (m.categoryBreakdown || []).filter((c) => c.count > 0).map((c, i) => ({ n: i + 1, name: c.category, count: c.count, pct: sharePct(c.count, m.totalMaterials) }));
}

export function subCategoryRows(mc) {
  const m = mc || EMPTY_CATEGORIES;
  return (m.subCategoryBreakdown || []).map((c, i) => ({ n: i + 1, name: c.subCategory, count: c.count, pct: sharePct(c.count, m.totalMaterials) }));
}

function sharePct(count, total) { return total ? Math.round((count / total) * 100) : 0; }

/** Derived collections, exactly as the legacy renderer derived them. */
export function deriveSecurity({ users, banned, activity }) {
  const userList = users || [];
  const blocked = banned || [];
  const suspended = userList.filter((u) => u.archived).map((u) => u.email);
  const flagged = userList.filter((u) => u.flagged).map((u) => u.email);
  const deleted = (activity || []).filter((e) => e.type === "delete");
  return { blocked, suspended, flagged, deleted };
}

/** Status precedence used by section 6 (legacy): banned > suspended > flagged > active. */
export function userStatus(user, { blocked, flagged }) {
  const isBlocked = blocked.some((b) => (b.email || "").toLowerCase() === (user.email || "").toLowerCase());
  return isBlocked ? "Email Banned" : user.archived ? "Suspended" : flagged.includes(user.email) ? "Flagged" : "Active";
}
