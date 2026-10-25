/**
 * People > Deletion requests: the COMBINED SIDEBAR BADGE.
 *
 * The badge is a navigation indicator ("something under Deletion requests is waiting for an admin"). It is NOT a
 * claim that the two deletion workflows share an approval process; the page itself keeps them apart.
 *
 * What it counts (all through endpoints that already exist, GET only):
 *
 *   A. Admin-initiated, dual-control requests that are still actionable
 *        GET /api/admin/deletion-requests?status=PENDING    -> awaiting approvals
 *        GET /api/admin/deletion-requests?status=APPROVED   -> approved, awaiting execution
 *      Keyed by the dual-control REQUEST id. A request that shows up in both lists (it was approved between the two
 *      requests) is counted once. Rejected / executed / cancelled entries are not actionable and are not counted.
 *
 *   B. User-initiated self-deletion requests awaiting one-admin review
 *        GET /api/admin/self-deletion-requests
 *      Keyed by the USER ACCOUNT id (`id` is the account id here, not a request record). Entries without a valid
 *      account id cannot be acted on from the queue, so they are not counted.
 *
 *   badge = (distinct actionable dual-control requests) + (distinct self-deletion accounts)
 *
 * The two id spaces overlap numerically (request 104 and user 104 are unrelated), so they are never deduplicated
 * against each other: a request and an account that happen to share a number are two items.
 *
 * Failure rules (never a false zero, never a partial total):
 *   - A count is published only when ALL THREE responses arrived and were well formed. If any one request fails
 *     or any body is malformed (not an object, `success: false`, `requests` missing or not an array), nothing from
 *     that round is used, not even the sources that did succeed.
 *   - After a failed round the last complete count stays on screen (state "stale", flagged in the tooltip and in
 *     `data-badge-state`). If there never was a complete count the badge shows "?" (state "unknown"). A stale count of
 *     ZERO cannot "stay on screen" (a zero badge is hidden, which would read as a confirmed zero), so it is shown as "?".
 *   - Individual unusable ENTRIES inside a well-formed list (null, no valid id) are not actionable items; they are
 *     skipped and reported in `ignored`, they do not turn the whole count into "unknown".
 *
 * Refresh policy: at most one round (three GETs) is in flight. Callers that need to see a change they just made
 * (`refresh()`) share ONE follow-up round no matter how many ask while a round is running; callers that only
 * want "an up-to-date count" (`refresh({ fresh: false })`) simply join the round in flight. The Overview does not
 * fetch anything for the badge: it hands over the three responses it already loaded (`applyResults`).
 * A result observed earlier than the one already shown is ignored, so a slow older response cannot overwrite a
 * newer count.
 *
 * One instance per document lives at `window.adminDeletionBadge`. The legacy (non-module) deletion-request script
 * and the ES-module views reach the same instance through it.
 */
import { parseUserId } from "./users-data.js";

export const DELETION_BADGE_KEY = "people.deletion-requests";
export const DELETION_BADGE_SLOT = "adminDeletionBadge";
export const DELETION_BADGE_PATHS = Object.freeze({
  pending: "/api/admin/deletion-requests?status=PENDING",
  approved: "/api/admin/deletion-requests?status=APPROVED",
  self: "/api/admin/self-deletion-requests"
});
const SOURCE_KEYS = Object.freeze(["pending", "approved", "self"]);
const SOURCE_LABELS = Object.freeze({
  pending: "pending admin-initiated deletion requests",
  approved: "approved admin-initiated deletion requests",
  self: "self-requested deletions"
});

export class DeletionBadgeSourceError extends Error {
  constructor(source, message, { malformed = false, cause } = {}) {
    super(message);
    this.name = "DeletionBadgeSourceError";
    this.source = source;
    this.malformed = malformed;
    if (cause !== undefined) this.cause = cause;
  }
}

const isRecord = value => value !== null && typeof value === "object" && !Array.isArray(value);
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

function listOf(source, body) {
  if (!isRecord(body) || body.success === false || !Array.isArray(body.requests)) {
    throw new DeletionBadgeSourceError(source, `The server's answer for ${SOURCE_LABELS[source]} was not in the expected format.`, { malformed: true });
  }
  return body.requests;
}

/**
 * Pure derivation from the three raw response bodies. Throws DeletionBadgeSourceError (with `.source`) when a body
 * is malformed; never returns a partial result.
 *
 * @returns {{count:number, dualControl:{pending:number, approved:number, total:number}, selfRequested:number,
 *            ignored:{dualControl:number, selfRequested:number}}}
 */
export function deriveDeletionBadge({ pending, approved, self } = {}) {
  // Validate every body before counting anything: a malformed source must fail the whole derivation.
  const pendingList = listOf("pending", pending);
  const approvedList = listOf("approved", approved);
  const selfList = listOf("self", self);

  const dual = new Map();           // dual-control request id -> "PENDING" | "APPROVED"
  let ignoredDual = 0;
  for (const [list, fallback] of [[pendingList, "PENDING"], [approvedList, "APPROVED"]]) {
    for (const entry of list) {
      const id = isRecord(entry) ? parseUserId(entry.id) : null;     // same positive-integer rule as every other People id
      if (id == null) { ignoredDual += 1; continue; }
      const status = String(entry.status || fallback).trim().toUpperCase();
      if (status !== "PENDING" && status !== "APPROVED") continue;  // not actionable
      dual.set(id, status);                                         // later list wins: a request that just became APPROVED is counted once
    }
  }
  const accounts = new Set();       // USER account ids: a separate id space from `dual`
  let ignoredSelf = 0;
  for (const entry of selfList) {
    const id = isRecord(entry) ? parseUserId(entry.id) : null;
    if (id == null) { ignoredSelf += 1; continue; }
    accounts.add(id);
  }

  const statuses = [...dual.values()];
  const pendingCount = statuses.filter(s => s === "PENDING").length;
  const approvedCount = statuses.length - pendingCount;
  return {
    count: dual.size + accounts.size,
    dualControl: { pending: pendingCount, approved: approvedCount, total: dual.size },
    selfRequested: accounts.size,
    ignored: { dualControl: ignoredDual, selfRequested: ignoredSelf }
  };
}

function describeView(view) {
  if (view.status === "unknown") return "Deletion request count unavailable: at least one queue could not be loaded. Open Deletion requests to check.";
  const d = view.detail;
  if (!d) return "";
  const parts = `${plural(d.dualControl.total, "admin-initiated request")} (dual control: ${d.dualControl.pending} pending approval, ${d.dualControl.approved} approved and awaiting execution) and ${plural(d.selfRequested, "self-requested deletion")} (one-admin review)`;
  return view.status === "stale"
    ? `${view.count} open at the last successful check: ${parts}. The latest refresh failed, so this may be out of date.`
    : `${view.count} open: ${parts}.`;
}

/** Writes the view onto every nav badge element for the key. A missing document (unit tests in Node) is a no-op. */
export function paintDeletionBadgeDom(view) {
  if (typeof document === "undefined" || !view || view.status === "idle") return;
  // A last-known ZERO that the latest (failed) round could not confirm must not look like a confirmed zero: a hidden
  // badge reads as "nothing waiting". It is shown as "?" until a complete round succeeds again.
  const unconfirmed = view.status === "unknown" || (view.status === "stale" && !(view.count > 0));
  for (const el of document.querySelectorAll(`[data-badge-key="${DELETION_BADGE_KEY}"]`)) {
    if (unconfirmed) { el.textContent = "?"; el.style.display = "inline-block"; }
    else if (view.count > 0) { el.textContent = String(view.count); el.style.display = "inline-block"; }
    else el.style.display = "none";
    el.dataset.badgeState = view.status;
    if (unconfirmed || view.count > 0) el.title = view.title; else el.removeAttribute("title");
  }
}

/**
 * @param {object} [options]
 * @param {{get:Function}} [options.api]       transport; replaceable later with bind({ api })
 * @param {{set:Function}} [options.badges]    shell badge store (ctx.badges); mirrored on every complete count
 * @param {Function} [options.paint]           receives { status, count, detail, title, error }
 * @param {Function} [options.clock]           ms clock used to order observations
 * @param {Function} [options.warn]            sink for failed-refresh diagnostics (console.warn by default)
 */
export function createDeletionBadge({ api = null, badges = null, paint = paintDeletionBadgeDom, clock = Date.now, warn } = {}) {
  const report = typeof warn === "function" ? warn : (...args) => { if (typeof console !== "undefined") console.warn(...args); };
  let transport = api;
  let store = badges;
  let lastGood = null;              // derivation of the most recent COMPLETE round
  let lastError = null;
  let lastSuccessAt = -Infinity;    // when that round started
  let active = null;                // promise of the running drain loop
  let rerun = false;                // a fresh round was requested while one was running

  function view() {
    const status = lastGood ? (lastError ? "stale" : "known") : (lastError ? "unknown" : "idle");
    const v = { status, count: lastGood ? lastGood.count : null, detail: lastGood, error: lastError };
    v.title = describeView(v);
    return v;
  }

  function publish() {
    const v = view();
    if (v.status === "known" && store && typeof store.set === "function") {
      try { store.set(DELETION_BADGE_KEY, v.count); } catch (error) { report("Could not mirror the deletion badge into the badge store:", error); }
    }
    try { paint(v); } catch (error) { report("Could not paint the deletion badge:", error); }
    return v;
  }

  /**
   * Applies one observation of the three sources.
   * @param {{pending:Result, approved:Result, self:Result}} results   Result = { ok:true, value } | { ok:false, error }
   * @param {{observedAt?:number}} [options]   when the earliest of these requests started
   */
  function applyResults(results, { observedAt = clock() } = {}) {
    let derived = null;
    let failure = null;
    const bad = SOURCE_KEYS.map(key => (results && results[key]) || { ok: false, error: new DeletionBadgeSourceError(key, `No result was provided for ${SOURCE_LABELS[key]}.`) }).find(r => !r.ok);
    if (bad) {
      failure = bad.error || new Error("A deletion queue could not be loaded.");
    } else {
      try { derived = deriveDeletionBadge({ pending: results.pending.value, approved: results.approved.value, self: results.self.value }); }
      catch (error) { failure = error; }
    }
    if (observedAt < lastSuccessAt) return view();   // an older observation never replaces a newer complete count
    if (derived) {
      lastGood = derived; lastError = null; lastSuccessAt = observedAt;
    } else {
      lastError = failure;   // keep lastGood: the last COMPLETE count stays; with none, the badge is "unknown"
      report("Deletion badge not updated:", failure && failure.message ? failure.message : failure);
    }
    return publish();
  }

  async function round() {
    const startedAt = clock();
    const fetchOne = async path => {
      if (!transport || typeof transport.get !== "function") throw new Error("No API transport is available for the deletion badge.");
      return transport.get(path);
    };
    const settled = await Promise.allSettled(SOURCE_KEYS.map(key => fetchOne(DELETION_BADGE_PATHS[key])));
    const results = {};
    SOURCE_KEYS.forEach((key, i) => { results[key] = settled[i].status === "fulfilled" ? { ok: true, value: settled[i].value } : { ok: false, error: settled[i].reason }; });
    return applyResults(results, { observedAt: startedAt });
  }

  /**
   * Re-reads all three sources. Never rejects. Resolves with the view after the round that covers the call.
   * `fresh: true` (default) guarantees a round that STARTS after this call (use after a change you just made);
   * `fresh: false` may join a round already in flight.
   */
  function refresh({ fresh = true } = {}) {
    if (active) {
      if (fresh) rerun = true;
      return active;
    }
    active = (async () => {
      let latest = view();
      try {
        do { rerun = false; latest = await round(); } while (rerun);
      } catch (error) {
        report("Deletion badge refresh failed:", error);
        latest = view();
      } finally {
        active = null;
      }
      return latest;
    })();
    return active;
  }

  function bind({ api: nextApi, badges: nextBadges } = {}) {
    if (nextApi && typeof nextApi.get === "function") transport = nextApi;
    if (nextBadges && typeof nextBadges.set === "function" && nextBadges !== store) {
      store = nextBadges;
      if (lastGood && !lastError) { try { store.set(DELETION_BADGE_KEY, lastGood.count); } catch { /* the DOM is already painted */ } }
    }
  }

  return {
    refresh,
    applyResults,
    bind,
    getState: view,
    isRefreshing: () => active !== null,
    reset() { lastGood = null; lastError = null; lastSuccessAt = -Infinity; rerun = false; }
  };
}

const lateBoundApi = {
  get(path, options) {
    const call = typeof window !== "undefined" ? window.apiFetch : undefined;
    if (typeof call !== "function") return Promise.reject(new Error("apiFetch is not available yet."));
    return call(path, options);
  }
};

/** The per-document instance (`window.adminDeletionBadge`), created on first use. Optionally (re)binds ctx.api / ctx.badges. */
export function getDeletionBadge({ api, badges } = {}) {
  const host = typeof window !== "undefined" ? window : globalThis;
  let instance = host[DELETION_BADGE_SLOT];
  if (!instance || typeof instance.refresh !== "function" || typeof instance.applyResults !== "function") {
    instance = createDeletionBadge({ api: lateBoundApi });
    host[DELETION_BADGE_SLOT] = instance;
  }
  instance.bind({ api, badges });
  return instance;
}

export default getDeletionBadge;
