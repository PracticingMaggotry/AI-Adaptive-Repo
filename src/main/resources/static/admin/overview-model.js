/**
 * Overview data model (pure functions, no DOM, no network).
 *
 * Everything here turns responses from EXISTING admin endpoints into display data. Nothing is invented:
 * a missing value stays `null` (rendered as an em dash), never a fabricated zero.
 *
 * Endpoints consumed by the Overview view (all existing, all admin-only on the server):
 *   GET /api/admin/overview                       -> { stats: { totalUsers, totalTopics, totalQuestions, totalAttempts, totalMaterials } }
 *   GET /api/admin/users                          -> { users: [{ id, fullName, email, isAdmin, flagged, flagReason, archived, ... }] }
 *   GET /api/admin/banned-emails                  -> { bannedEmails: [{ id, email, reason, bannedBy, bannedAt }] }
 *   GET /api/admin/activity-log                   -> { log: [{ type, message, detail, performedBy, time }] }   (server caps at 200, newest first)
 *   GET /api/admin/reports?status=PENDING         -> { total, reports: [{ id, questionId, topic, reason, reportedAt, reportCount, ... }] }
 *   GET /api/admin/deletion-requests?status=PENDING|APPROVED
 *                                                 -> { requests: [{ id, targetEmail, targetName, requestedBy, requestedAt, status, approveCount, requiredCount, callerVoted }] }
 *   GET /api/admin/self-deletion-requests         -> { success, requests: [{ id, fullName, originalEmail, requestedAt }] }   (id IS the user-account id; user-initiated, single-admin review)
 *   GET /api/admin/quiz-activity                  -> { quizHistory: [{ topic, difficulty, score, date }], avgScore }
 *   GET /api/admin/topics                         -> [ "topic name", ... ]
 *   GET /api/admin/material-categories?range=     -> { categoryBreakdown, subCategoryBreakdown, topCategory, topSubCategory }
 */

/** Canonical route paths (without "#") used by quick actions and inbox items. Verified against routes.js by tests. */
export const ROUTES = Object.freeze({
  users: "/people/users",
  deletionRequests: "/people/deletion-requests",
  bannedEmails: "/people/banned-emails",
  materials: "/content/materials",
  reportedQuestions: "/content/reported-questions",
  activityLog: "/audit/activity-log",
  platformReport: "/audit/platform-report"
});

export const toHref = (path) => "#" + path;

/** Quick actions migrated from the old dashboard and the old Overview, pointing at canonical routes. */
export const QUICK_ACTIONS = Object.freeze([
  { icon: "🚩", label: "Review reported questions", path: ROUTES.reportedQuestions },
  { icon: "👥", label: "Manage users", path: ROUTES.users },
  { icon: "⚑", label: "Review flagged users", path: ROUTES.users },
  { icon: "🚫", label: "Ban an email", path: ROUTES.bannedEmails },
  { icon: "🗑", label: "Review deletion requests", path: ROUTES.deletionRequests },
  { icon: "📚", label: "Review uploaded topics", path: ROUTES.materials },
  { icon: "📋", label: "Generate & print report", path: ROUTES.platformReport }
]);

export const CATEGORY_RANGES = Object.freeze([
  { id: "week", label: "This Week" },
  { id: "month", label: "This Month" },
  { id: "all", label: "All Time" }
]);

export const CHART_PALETTE = Object.freeze(["#0093ad", "#7c3aed", "#f59e0b", "#10b981", "#ef4444", "#3b82f6", "#ec4899", "#14b8a6", "#a855f7", "#64748b"]);

const isNum = (v) => typeof v === "number" && Number.isFinite(v);
const num = (v) => (isNum(v) ? v : null);
const arr = (v) => (Array.isArray(v) ? v : []);
const lower = (v) => String(v ?? "").trim().toLowerCase();
const plural = (n, one, many) => (n === 1 ? one : many || one + "s");

export function parseDate(value) {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

export function truncate(text, max = 120) {
  const s = String(text ?? "").replace(/\s+/g, " ").trim();
  return s.length > max ? s.slice(0, max - 1).trimEnd() + "…" : s;
}

// ───────────────────────────── KPIs ─────────────────────────────

/** Five platform KPI tiles. Hints come only from data that was actually returned. */
export function buildKpis(stats, users) {
  const s = stats && typeof stats === "object" ? stats : null;
  const haveUsers = Array.isArray(users);
  const admins = haveUsers ? users.filter((u) => u && u.isAdmin).length : null;
  const totalUsers = s ? num(s.totalUsers) : null;
  const totalTopics = s ? num(s.totalTopics) : null;
  const totalQuestions = s ? num(s.totalQuestions) : null;
  return [
    { key: "users", icon: "👤", label: "Registered Users", value: totalUsers, tone: "blue",
      hint: totalUsers != null && admins != null ? `${Math.max(0, totalUsers - admins)} students · ${admins} admins` : null },
    { key: "topics", icon: "📚", label: "Topics", value: totalTopics, tone: "green",
      hint: s && num(s.totalMaterials) != null ? `${s.totalMaterials} uploaded materials` : null },
    { key: "questions", icon: "❓", label: "Questions", value: totalQuestions, tone: "purple",
      hint: totalQuestions != null && totalTopics ? `${Math.round(totalQuestions / totalTopics)} avg per topic` : null },
    { key: "attempts", icon: "📝", label: "Quiz Attempts", value: s ? num(s.totalAttempts) : null, tone: "amber", hint: "Platform-wide total" },
    { key: "materials", icon: "📄", label: "Uploaded Materials", value: s ? num(s.totalMaterials) : null, tone: "red", hint: null }
  ];
}

/** Ratios from the old "Platform Activity Overview" card. No decorative bars: only the numbers. */
export function platformRatios(stats) {
  const s = stats && typeof stats === "object" ? stats : null;
  if (!s) return [];
  const ratio = (a, b, digits) => (isNum(a) && isNum(b) && b > 0 ? (digits ? (a / b).toFixed(digits) : String(Math.round(a / b))) : null);
  return [
    { label: "Questions per topic", value: ratio(s.totalQuestions, s.totalTopics, 0) },
    { label: "Avg attempts per user", value: ratio(s.totalAttempts, s.totalUsers, 0) },
    { label: "Materials per user", value: ratio(s.totalMaterials, s.totalUsers, 1) }
  ];
}

// ─────────────────────── Accounts and security ───────────────────────

/**
 * Account-status counts (old dashboard "User Breakdown" definitions):
 *   banned = users whose email is on the banned list, suspended = archived and not banned,
 *   active = not archived and not banned, flagged = users with the flagged marker.
 * `deletedInLog` counts "delete" entries in the (server-capped, newest-200) activity log, so it is a
 * lower bound and is labeled that way by the view.
 */
export function accountBreakdown(users, bannedEmails, log) {
  const list = arr(users);
  const bannedSet = new Set(arr(bannedEmails).map((b) => lower(b && b.email)).filter(Boolean));
  const isBanned = (u) => bannedSet.has(lower(u && u.email));
  return {
    active: list.filter((u) => !u.archived && !isBanned(u)).length,
    suspended: list.filter((u) => u.archived && !isBanned(u)).length,
    banned: list.filter(isBanned).length,
    flagged: list.filter((u) => u.flagged).length,
    bannedEmails: arr(bannedEmails).length,
    deletedInLog: arr(log).filter((e) => e && e.type === "delete").length
  };
}

const ACTIVITY_ICONS = { delete: "🗑", "delete-topic": "📚", "delete-question": "🚩", "ban-email": "🚫", "unban-email": "✓", archive: "⏸", unarchive: "✓", "request-deletion": "🗑", "deletion-approved": "✅", "deletion-rejected": "✕", "deletion-cancelled": "↩", promote: "⬆", flag: "⚑", unflag: "✓", "flag-topic": "⚑" };
const ACTIVITY_TONES = { delete: "red", "delete-topic": "red", "delete-question": "red", "ban-email": "red", "unban-email": "green", archive: "amber", unarchive: "green", "request-deletion": "amber", "deletion-approved": "green", "deletion-rejected": "red", "deletion-cancelled": "gray", promote: "purple", flag: "amber", unflag: "green", "flag-topic": "amber" };

export function activityPresentation(type) {
  return { icon: ACTIVITY_ICONS[type] || "📝", tone: ACTIVITY_TONES[type] || "gray" };
}

/** Newest-first recent admin actions (the endpoint already returns newest first; sort defensively). */
export function recentActivity(log, limit = 5) {
  return arr(log)
    .filter((e) => e && e.message)
    .map((e) => ({ ...e, _t: parseDate(e.time) }))
    .sort((a, b) => (b._t ? b._t.getTime() : 0) - (a._t ? a._t.getTime() : 0))
    .slice(0, limit)
    .map(({ _t, ...rest }) => ({ ...rest, when: _t }));
}

export function recentBanned(bannedEmails, limit = 4) {
  return arr(bannedEmails)
    .filter((b) => b && b.email)
    .map((b) => ({ ...b, _t: parseDate(b.bannedAt) }))
    .sort((a, b) => (b._t ? b._t.getTime() : 0) - (a._t ? a._t.getTime() : 0))
    .slice(0, limit)
    .map(({ _t, ...rest }) => ({ ...rest, when: _t }));
}

// ───────────────────────── Quiz analytics ─────────────────────────

export function difficultyBuckets(history) {
  const buckets = { Easy: 0, Medium: 0, Hard: 0, Other: 0 };
  for (const h of arr(history)) {
    const d = String((h && h.difficulty) || "").trim();
    const name = d.charAt(0).toUpperCase() + d.slice(1).toLowerCase();
    if (Object.prototype.hasOwnProperty.call(buckets, name)) buckets[name] += 1;
    else if (d) buckets.Other += 1;
  }
  return buckets;
}

export function scoreBuckets(history) {
  const buckets = { "0-49%": 0, "50-64%": 0, "65-79%": 0, "80-100%": 0 };
  for (const h of arr(history)) {
    const s = Number((h && h.score) || 0);
    if (s < 50) buckets["0-49%"] += 1;
    else if (s < 65) buckets["50-64%"] += 1;
    else if (s < 80) buckets["65-79%"] += 1;
    else buckets["80-100%"] += 1;
  }
  return buckets;
}

/** Mean score across recorded attempts, or null when there are none (so the view shows no badge, not "0%"). */
export function averageScore(history) {
  const list = arr(history);
  if (!list.length) return null;
  return Math.round(list.reduce((a, h) => a + (Number(h && h.score) || 0), 0) / list.length);
}

/** Per-topic attempts and average score keyed by lower-cased topic name. */
export function topicStats(history) {
  const acc = new Map();
  for (const h of arr(history)) {
    const key = lower((h && h.topic) || "Unknown");
    const cur = acc.get(key) || { total: 0, count: 0 };
    cur.total += Number(h && h.score) || 0;
    cur.count += 1;
    acc.set(key, cur);
  }
  const out = new Map();
  for (const [key, v] of acc) out.set(key, { attempts: v.count, avgScore: Math.round(v.total / v.count) });
  return out;
}

export function topicPerformance(stat) {
  if (!stat || !stat.attempts) return { tone: "none", label: "No attempts yet" };
  const s = stat.avgScore;
  if (s >= 80) return { tone: "strong", label: `Strong · ${s}%` };
  if (s >= 65) return { tone: "ok", label: `OK · ${s}%` };
  if (s >= 50) return { tone: "weak", label: `Needs work · ${s}%` };
  return { tone: "bad", label: `Weak · ${s}%` };
}

// ───────────────────── Needs-attention inbox ─────────────────────

/**
 * Each builder returns a group:
 *   { id, title, route, noun, count, secondary?, items: [{ key, title, meta?, detail?, badge?, when? }], extra, notes: [] }
 * `count` is the number of actionable entries the server actually returned for that source. Items are de-duplicated
 * by a stable key so a report/request/account can never appear twice.
 */
const MAX_ITEMS = 5;
const dedupeBy = (list, keyOf) => {
  const seen = new Map();
  for (const item of list) {
    const key = keyOf(item);
    if (key == null || seen.has(key)) continue;
    seen.set(key, item);
  }
  return [...seen.values()];
};

/** Pending question reports, grouped per question: one inbox item per question, with how many reports it has. */
export function reportsGroup(resp) {
  const reports = dedupeBy(arr(resp && resp.reports).filter((r) => r && (r.status == null || String(r.status).toUpperCase() === "PENDING")), (r) => (r.id != null ? "r" + r.id : null));
  const byQuestion = new Map();
  for (const r of reports) {
    const qKey = r.questionId != null ? "q" + r.questionId : "r" + r.id;
    const cur = byQuestion.get(qKey) || { key: qKey, reports: 0, topic: r.topic, reasons: new Set(), text: null, last: null };
    cur.reports += 1;
    if (r.reason) cur.reasons.add(String(r.reason));
    cur.text = cur.text || r.currentQuestionText || r.questionText || null;
    const t = parseDate(r.reportedAt);
    if (t && (!cur.last || t > cur.last)) cur.last = t;
    byQuestion.set(qKey, cur);
  }
  const questions = [...byQuestion.values()].sort((a, b) => b.reports - a.reports || (b.last ? b.last.getTime() : 0) - (a.last ? a.last.getTime() : 0));
  return {
    id: "reports", title: "Reported questions", route: ROUTES.reportedQuestions, noun: "pending report",
    count: reports.length,
    secondary: reports.length ? `${questions.length} question${questions.length === 1 ? "" : "s"}` : null,
    items: questions.slice(0, MAX_ITEMS).map((q) => ({
      key: q.key,
      title: truncate(q.text || "(question text unavailable)", 110),
      meta: [q.topic, q.reasons.size ? [...q.reasons].slice(0, 2).join(", ") : null].filter(Boolean).join(" · "),
      badge: q.reports > 1 ? `${q.reports} reports` : "1 report",
      when: q.last
    })),
    extra: Math.max(0, questions.length - MAX_ITEMS), notes: []
  };
}

/**
 * Dual-control deletion requests: PENDING (waiting for votes) plus APPROVED (ready for an admin to execute).
 * Merged by request id so a request can never be listed twice. Either response may be missing (its fetch failed);
 * the group then carries a note instead of pretending to be complete.
 */
export function deletionsGroup(pendingResp, approvedResp) {
  const notes = [];
  if (pendingResp == null) notes.push("Pending requests could not be loaded.");
  if (approvedResp == null) notes.push("Approved requests could not be loaded.");
  const merged = new Map();
  const add = (list, fallbackStatus) => {
    for (const r of arr(list)) {
      if (!r || r.id == null) continue;
      const status = String(r.status || fallbackStatus).toUpperCase();
      if (status !== "PENDING" && status !== "APPROVED") continue; // only actionable states
      merged.set("d" + r.id, { ...r, status });
    }
  };
  add(pendingResp && pendingResp.requests, "PENDING");
  add(approvedResp && approvedResp.requests, "APPROVED"); // later wins: a request that just became APPROVED is not double counted
  const all = [...merged.values()];
  const pending = all.filter((r) => r.status === "PENDING");
  const approved = all.filter((r) => r.status === "APPROVED");
  const awaitingMe = pending.filter((r) => r.callerVoted === false);
  const rank = (r) => (r.status === "PENDING" && r.callerVoted === false ? 0 : r.status === "APPROVED" ? 1 : 2);
  const sorted = all.sort((a, b) => rank(a) - rank(b) || (parseDate(b.requestedAt)?.getTime() || 0) - (parseDate(a.requestedAt)?.getTime() || 0));
  const parts = [];
  if (awaitingMe.length) parts.push(`${awaitingMe.length} awaiting your vote`);
  if (approved.length) parts.push(`${approved.length} approved, ready to execute`);
  return {
    id: "deletions", title: "Deletion requests", route: ROUTES.deletionRequests, noun: "open request",
    count: all.length, secondary: parts.length ? parts.join(" · ") : null,
    items: sorted.slice(0, MAX_ITEMS).map((r) => ({
      key: "d" + r.id,
      title: r.targetName || r.targetEmail || "Unknown account",
      meta: [r.targetName && r.targetEmail ? r.targetEmail : null, r.requestedBy ? `requested by ${r.requestedBy}` : null,
        isNum(r.approveCount) && isNum(r.requiredCount) ? `${r.approveCount}/${r.requiredCount} approvals` : null].filter(Boolean).join(" · "),
      badge: r.status === "APPROVED" ? "Approved · ready" : r.callerVoted === false ? "Needs your vote" : "Pending",
      when: parseDate(r.requestedAt)
    })),
    extra: Math.max(0, sorted.length - MAX_ITEMS), notes
  };
}

/**
 * User-initiated (self-requested) account deletions: GET /api/admin/self-deletion-requests.
 *
 * Deliberately a SEPARATE group from deletionsGroup(): the admin-initiated flow is dual-control (several admins vote, then
 * an admin executes), whereas a self-request needs a single admin review because the account owner already consented. The
 * two counts are never merged. Both link to the same canonical People route, which is where each queue is handled.
 *
 * `resp` is the full response body. An entry is usable only if it is an object with a finite numeric or non-empty string id
 * (the user-account id). Entries that are not usable are NOT counted and NOT shown; they are reported in `notes` so the
 * group is never presented as complete when part of the server's answer was discarded. A body without a `requests` array is
 * rejected by the view's validator before it gets here; if it reaches this function anyway it yields an incomplete group
 * (note, count 0) rather than a false "nothing pending" - buildAttention() treats a note as "not complete".
 */
export function selfDeletionsGroup(resp) {
  const notes = [];
  const rawList = resp && Array.isArray(resp.requests) ? resp.requests : null;
  if (rawList == null) notes.push("The self-requested deletion response was not in the expected format.");
  const usableId = (r) => r && typeof r === "object" && ((typeof r.id === "number" && Number.isFinite(r.id)) || (typeof r.id === "string" && r.id.trim() !== ""));
  const valid = (rawList || []).filter(usableId);
  const dropped = (rawList || []).length - valid.length;
  if (dropped > 0) notes.push(`${dropped} ${plural(dropped, "self-requested deletion entry", "self-requested deletion entries")} could not be read and ${dropped === 1 ? "is" : "are"} not counted.`);
  const unique = dedupeBy(valid, (r) => "s" + String(r.id).trim())
    .sort((a, b) => (parseDate(b.requestedAt)?.getTime() || 0) - (parseDate(a.requestedAt)?.getTime() || 0));
  const text = (v) => (typeof v === "string" && v.trim() ? v.trim() : null);
  return {
    id: "selfDeletions", title: "Self-requested deletions", route: ROUTES.deletionRequests, noun: "self-requested deletion",
    count: unique.length,
    secondary: unique.length ? "requested by the account owner · single admin review" : null,
    items: unique.slice(0, MAX_ITEMS).map((r) => ({
      key: "s" + String(r.id).trim(),
      title: text(r.fullName) || text(r.originalEmail) || "Unknown account",
      meta: [text(r.fullName) && text(r.originalEmail) ? text(r.originalEmail) : null, "requested by the user"].filter(Boolean).join(" · "),
      badge: "Self-requested", tone: "blue",   // blue (not the amber "Pending" pill) so it reads as a different process
      when: parseDate(r.requestedAt)
    })),
    extra: Math.max(0, unique.length - MAX_ITEMS), notes
  };
}

/** Flagged accounts. Suspended (archived) accounts are an admin-applied state, not a pending decision, so they are not inbox items. */
export function flaggedUsersGroup(users) {
  const flagged = dedupeBy(arr(users).filter((u) => u && u.flagged), (u) => (u.id != null ? "u" + u.id : u.email ? "e" + lower(u.email) : null))
    .sort((a, b) => String(a.fullName || a.email || "").localeCompare(String(b.fullName || b.email || "")));
  return {
    id: "flagged", title: "Flagged accounts", route: ROUTES.users, noun: "flagged account",
    count: flagged.length, secondary: null,
    items: flagged.slice(0, MAX_ITEMS).map((u) => ({
      key: u.id != null ? "u" + u.id : "e" + lower(u.email),
      title: u.fullName || u.email || "Unknown account",
      meta: [u.fullName && u.email ? u.email : null, u.flagReason ? `Reason: ${truncate(u.flagReason, 80)}` : null].filter(Boolean).join(" · "),
      badge: u.archived ? "Flagged · suspended" : "Flagged", when: null
    })),
    extra: Math.max(0, flagged.length - MAX_ITEMS), notes: []
  };
}

/**
 * Combine source states into the inbox view model.
 * `sources` = { reports, delPending, delApproved, selfDeletions, users } where each is { status: "loading"|"ok"|"error", data, error }.
 * `data` is the full response for reports/delPending/delApproved/selfDeletions and the users ARRAY for users.
 * A missing source entry is treated as still loading, never as empty.
 * Groups whose sources failed or are loading are reported as such, never as "0 items".
 */
export function buildAttention(sources) {
  const ok = (s) => s && s.status === "ok";
  const groups = [];
  const failures = [];
  const loading = [];

  const track = (id, title, states, builder) => {
    if (states.some((s) => !s || s.status === "loading")) { loading.push(id); groups.push({ id, title, state: "loading" }); return; }
    if (states.every((s) => s.status === "error")) { failures.push(id); groups.push({ id, title, state: "error", error: states[0].error }); return; }
    const group = builder();
    groups.push({ ...group, state: "ok" });
  };

  track("reports", "Reported questions", [sources.reports], () => reportsGroup(sources.reports.data));
  track("deletions", "Deletion requests", [sources.delPending, sources.delApproved], () =>
    deletionsGroup(ok(sources.delPending) ? sources.delPending.data : null, ok(sources.delApproved) ? sources.delApproved.data : null));
  track("selfDeletions", "Self-requested deletions", [sources.selfDeletions], () => selfDeletionsGroup(sources.selfDeletions.data));
  track("flagged", "Flagged accounts", [sources.users], () => flaggedUsersGroup(sources.users.data));

  const okGroups = groups.filter((g) => g.state === "ok");
  const total = okGroups.reduce((n, g) => n + g.count, 0);
  const complete = failures.length === 0 && loading.length === 0 && okGroups.every((g) => g.notes.length === 0);
  return { groups, total, complete, loading, failures, empty: complete && total === 0 };
}
