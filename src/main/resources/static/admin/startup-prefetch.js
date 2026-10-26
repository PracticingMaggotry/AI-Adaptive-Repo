/**
 * One-shot hand-off of requests that admin.js already made while booting.
 *
 * Why: initAdmin() has to load users / overview / topics / banned emails / activity log / material categories
 * for the classic `adminData` global (the Users view and the legacy actions still read it). The Overview view
 * then asked the server for exactly the same six things a second time, including the full users table.
 * That doubled the database work and the transfer on every admin page load.
 *
 * How it stays correct:
 *  - ONE-SHOT: an entry is handed to the first reader and removed, so later reads (refresh button, going back to
 *    the page, a range change) always hit the server.
 *  - SHORT-LIVED: an entry older than ttlMs once it has settled is ignored, so a slow click-through can never
 *    show boot-time data.
 *  - FAILURE-SAFE: if the boot request failed, the reader makes its own live request instead of inheriting the error.
 *  - MUTATION-SAFE: any non-GET request through this layer drops every pending entry.
 *  - ISOLATED: the reader gets its own copy, so one side mutating arrays cannot change what the other side sees.
 *  - ABORTABLE: a reader's AbortSignal still rejects promptly with an AbortError, like a real fetch would.
 *
 * Pure module (no DOM, no globals): scripts/test-admin-startup-prefetch.mjs covers every rule above.
 */

export const STARTUP_TTL_MS = 15000;

const copyOf = (value) => {
  if (value === null || typeof value !== "object") return value;
  return typeof structuredClone === "function" ? structuredClone(value) : JSON.parse(JSON.stringify(value));
};

function abortError() {
  if (typeof DOMException === "function") return new DOMException("The operation was aborted.", "AbortError");
  const err = new Error("The operation was aborted.");
  err.name = "AbortError";
  return err;
}

/** Resolve with `promise`, but reject at once with an AbortError if `signal` aborts first. */
function withSignal(promise, signal) {
  if (!signal) return promise;
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(abortError()); return; }
    const onAbort = () => reject(abortError());
    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => { signal.removeEventListener("abort", onAbort); resolve(value); },
      (error) => { signal.removeEventListener("abort", onAbort); reject(error); }
    );
  });
}

/**
 * @param {(path: string, options?: object) => Promise<any>} fetcher the real request function (apiFetch)
 * @param {{ttlMs?: number, now?: () => number}} [config]
 */
export function createStartupPrefetch(fetcher, { ttlMs = STARTUP_TTL_MS, now = () => Date.now() } = {}) {
  const entries = new Map();

  /** Start a boot-time GET and remember it for one hand-off. Returns the promise for the booting code itself. */
  function prefetch(path) {
    const entry = { settledAt: 0, promise: null };
    let started;
    try { started = Promise.resolve(fetcher(path)); } catch (error) { started = Promise.reject(error); } // fire immediately
    entry.promise = started
      .then(
        (value) => { entry.settledAt = now(); return value; },
        (error) => { if (entries.get(path) === entry) entries.delete(path); throw error; }
      );
    entries.set(path, entry);
    return entry.promise;
  }

  /** Mutations invalidate everything: whatever was prefetched may no longer be true. */
  function request(path, options) {
    entries.clear();
    return fetcher(path, options);
  }

  /** What views call. Hands over a still-fresh boot request once; otherwise behaves exactly like fetcher. */
  function get(path, options = {}) {
    const method = String((options && options.method) || "GET").toUpperCase();
    if (method !== "GET") return request(path, options);

    const entry = entries.get(path);
    if (!entry) return fetcher(path, options);
    entries.delete(path); // one-shot, whatever happens next

    const stale = entry.settledAt && now() - entry.settledAt > ttlMs;
    if (stale) return fetcher(path, options);

    const shared = entry.promise.then(copyOf, () => fetcher(path, options)); // boot request failed -> try live
    return withSignal(shared, options && options.signal);
  }

  return { prefetch, get, request, pending: () => entries.size };
}

export default createStartupPrefetch;
