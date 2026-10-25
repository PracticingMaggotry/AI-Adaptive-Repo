/**
 * People > Deletion requests.
 *
 * Two DIFFERENT workflows share this page:
 *   1. Admin-initiated, dual-control requests. Still rendered by the legacy renderer
 *      (renderDeletionRequests in admin/legacy/deletionrequests.js) and left behaviorally untouched:
 *      status filters, votes, rejection notes, execution, cancellation and the audit refreshes.
 *   2. User-initiated self-deletion requests, a one-admin review, rendered below it by
 *      admin/people/self-deletion-queue.js using ctx.api and the shared ui.confirm component.
 *
 * This adapter only adds presentation around the legacy card (a label, an accent, a short guide) and
 * mounts the second queue. It also connects both queues to the combined sidebar badge
 * (admin/people/deletion-badge.js): a navigation indicator only; the two queues keep separate counts and rules. A failure in the second queue can never prevent the first from rendering.
 * The legacy renderer is still hosted by admin.js, so mount keeps the transitional #adminContent contract.
 */
import { createSelfDeletionQueue, createWorkflowGuide } from "../people/self-deletion-queue.js";
import { getDeletionBadge } from "../people/deletion-badge.js";

const legacyRenderer = "renderDeletionRequests";
const BADGE_NOTE = "The Deletion requests badge in the sidebar adds the open items from both queues below so you can tell something is waiting. " +
  "It is only a navigation shortcut: the two queues keep separate counts and separate rules.";

let active = null; // per-mount state; reset by unmount()

function apiFor(ctx) {
  if (ctx && ctx.api && typeof ctx.api.get === "function" && typeof ctx.api.request === "function") return ctx.api;
  // Same transport the legacy renderer uses; only reached when a harness mounts the view without a context.
  const call = (path, options) => window.apiFetch(path, options);
  return { get: call, request: call };
}

function globalRefresher(name) {
  return () => (typeof window[name] === "function" ? window[name]() : undefined);
}

function decorateLegacyCard(root) {
  const card = root.querySelector(":scope > .admin-card");
  if (!card) return null;
  card.classList.add("sdq-dual");
  card.id = "dualControlSection";
  const label = document.createElement("div");
  label.className = "sdq__label sdq__label--dual";
  label.textContent = "Admin-initiated · dual control";
  card.prepend(label);
  const title = card.querySelector(".admin-card-title");
  if (title) {
    title.id = "dualControlHeading";
    title.tabIndex = -1;
    title.textContent = "🗑 Admin-initiated deletion requests";
  }
  return card;
}

const view = {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Deletion Requests view requires a mount element");
    const target = document.getElementById("adminContent");
    if (!target || target !== el) {
      throw new Error("Transitional admin views must mount into #adminContent until extracted from admin.js");
    }
    const render = window[legacyRenderer];
    if (typeof render !== "function") throw new Error(`Legacy renderer ${legacyRenderer} is not available`);
    view.unmount(); // idempotent: never leave a previous queue behind
    // One badge per document; binding here lets legacy vote / cancel / execute refreshes (which run through the
    // global refreshDeletionBadge) use the same transport and the shell's badge store as this view.
    const badge = getDeletionBadge({ api: apiFor(ctx), badges: ctx && ctx.badges });
    const state = { queue: null, guide: null, note: null };
    active = state;

    const result = await render(); // the existing dual-control workflow, unchanged
    if (active !== state) return result; // superseded while the legacy list was loading

    try {
      const card = decorateLegacyCard(el);
      const jump = which => {
        const t = which === "self" ? state.queue?.heading : el.querySelector("#dualControlHeading");
        if (!t) return;
        t.scrollIntoView?.({ block: "start" });
        t.focus({ preventScroll: true });
      };
      state.guide = createWorkflowGuide({ onJump: jump });
      state.queue = createSelfDeletionQueue({
        api: apiFor(ctx),
        ui: ctx.ui,
        refresh: { activityLog: globalRefresher("refreshActivityLog"), users: globalRefresher("refreshUsers"), badge: () => badge.refresh() },
        onChange: ({ count, status }) => {
          state.guide.setSelfCount(count);
          // A successful self-queue load (e.g. Retry after a failure) is the moment to recover a badge that is stale or
          // unknown. It joins a round already in flight, and does nothing while the badge already holds a complete count.
          if (status === "ready" && badge.getState().status !== "known") void badge.refresh({ fresh: false });
        }
      });
      el.insertBefore(state.guide.element, el.firstChild);
      state.note = document.createElement("p");
      state.note.className = "admin-card-hint sdq-guide__badge-note";
      state.note.textContent = BADGE_NOTE;
      state.guide.element.after(state.note);
      (card || el.lastElementChild || el).after?.(state.queue.element);
      if (!state.queue.element.isConnected) el.append(state.queue.element);
      void state.queue.load(); // not awaited: the legacy queue is usable immediately; load() reports its own errors
    } catch (error) {
      console.error("Self-requested deletion queue failed to start:", error);
      const notice = document.createElement("div");
      notice.className = "empty-state";
      notice.setAttribute("role", "alert");
      notice.textContent = `Self-requested deletions could not be shown: ${error && error.message ? error.message : "unknown error"}.`;
      el.append(notice);
    }
    // Resync the sidebar count on arrival (other admins may have acted since boot). Issued after both queues started
    // loading, and it joins a refresh already in flight (e.g. the boot one) instead of starting a second round.
    if (active === state) void badge.refresh({ fresh: false });
    return result;
  },
  unmount() {
    if (!active) return;
    const state = active;
    active = null;
    state.queue?.destroy();
    state.guide?.element.remove();
    state.note?.remove();
  }
};

export default view;
