/**
 * People > Users adapter.
 *
 * Still hosts the legacy renderer (renderUsers in admin/legacy/users.js) for the list, and adds
 * the user detail drawer for #/people/users/:id. routes.js points that route's `shellLoad` at this
 * module, so the shell mounts it with ctx.params.id set; the list is rendered underneath and the
 * drawer opens on top of it.
 *
 * Navigation contract (router is not modified):
 *   - A name link in the list is a real `<a href="#/people/users/ID">`, so the router pushes a
 *     history entry, Back/Forward/refresh/direct links all land on the same canonical hash.
 *   - Closing the drawer after opening it from the list goes history.back() (list entry, no new entry).
 *     Closing after a direct visit/refresh/Forward replaces the entry with #/people/users.
 *   - The search text, the flag filter (kept by the legacy renderer) and the scroll position of the
 *     list survive the list -> detail -> list round trip.
 *   - Legacy admin actions open legacy modals (#modal). A <dialog> drawer would sit above them, so the
 *     drawer is parked while one runs and reopens (with fresh data) when the legacy modal closes.
 *
 * Lifecycle: mount(el, ctx) / unmount(). The router calls unmount() without arguments, so ctx is kept.
 */
import { deriveAccountStatusDetail } from "../account-status.js";
import { createUserDrawer } from "../people/user-drawer.js";
import { loadStatusSources } from "../people/users-data.js";

const legacyRenderer = "renderUsers";
const USERS_HASH = /^#\/?(people\/users(\/[^/?]*)?|users)\/?(\?.*)?$/i;

// Survives unmount/mount so the list -> detail -> list round trip keeps its context.
const memory = { filter: "", scrollY: null, openedId: null, openedFromList: false };

// Per-mount state; reset by unmount().
let active = null;

const LEGACY_ACTIONS = {
  topics: ["viewUserTopics", u => [u.email, u.fullName]],
  usage: ["viewUserUsage", u => [u.id, u.email, u.fullName]],
  flag: ["flagUser", u => [u.email, u.fullName]],
  unflag: ["unflagUser", u => [u.email]],
  archive: ["promptArchiveUser", u => [u.email, u.fullName]],
  unarchive: ["promptUnarchiveUser", u => [u.email, u.fullName]],
  ban: ["promptBanEmail", u => [u.email, u.fullName]],
  unban: ["promptUnbanEmail", u => [u.email]],
  promote: ["promptPromoteUser", u => [u.email, u.fullName]],
  delete: ["promptRequestDeletion", u => [u.email, u.fullName]]
};

function routeParam(ctx) {
  if (ctx?.params && ctx.params.id !== undefined) return ctx.params.id;
  const match = String(window.location?.hash || "").match(/^#\/people\/users\/([^/?]+)/i);
  return match ? decodeURIComponent(match[1]) : undefined;
}

function currentUsers() {
  // adminData is a global of the classic admin.js script and is refreshed by the legacy actions.
  return typeof adminData !== "undefined" && Array.isArray(adminData.users) ? adminData.users : [];
}

function currentBannedEmails(fallback) {
  return typeof adminData !== "undefined" && Array.isArray(adminData.bannedEmails) ? adminData.bannedEmails : fallback;
}

function legacyModalVisible() {
  const modal = document.getElementById("modal");
  return Boolean(modal) && modal.style.display !== "none" && modal.style.display !== "";
}

function removeStaleNotice() {
  // routes.js still attaches "User detail is not available yet" to this route (see INTEGRATION-NOTES.md).
  const notice = document.getElementById("adminRouteNotice");
  if (notice && /User detail is not available/i.test(notice.textContent || "")) {
    notice.replaceChildren();
    notice.hidden = true;
  }
}

function decorateList(state) {
  const root = document.getElementById("adminContent");
  if (!root || !state.ui) return;
  const users = currentUsers();
  const sources = { ...state.sources, bannedEmails: currentBannedEmails(state.sources.bannedEmails) };
  for (const tr of root.querySelectorAll(".admin-table tbody tr")) {
    const usageButton = tr.querySelector('button[data-action="view-usage"][data-id]');
    if (!usageButton) continue;
    const user = users.find(u => String(u.id) === usageButton.dataset.id);
    const cells = tr.children;
    if (!user || cells.length < 6) continue;

    if (!cells[1].querySelector("a.user-link")) {
      const link = document.createElement("a");
      link.className = "user-link";
      link.href = `#/people/users/${encodeURIComponent(user.id)}`;
      link.dataset.userId = String(user.id);
      link.textContent = user.fullName || `User ${user.id}`;
      link.title = "Open user details";
      cells[1].replaceChildren(link);
    }

    if (!state.sourcesLoaded) continue;   // keep the legacy badges until the status sources have been fetched
    const detail = deriveAccountStatusDetail(user, sources);
    const badge = state.ui.statusBadge(detail.status);
    if (detail.uncertain.length) {
      badge.title = detail.uncertain.map(item => item.text).join(" ");
      badge.dataset.uncertain = "true";
    } else if (detail.reasons.length > 1) {
      badge.title = detail.reasons.map(item => item.text).join(" ");
    }
    cells[4].replaceChildren(badge);
  }
}

async function refreshSources(state) {
  try {
    const { sources, failed } = await loadStatusSources(state.api);
    if (state !== active) return;
    state.sources = sources;
    state.failed = failed;
  } catch {
    // keep the previous sources
  }
  if (state === active) state.sourcesLoaded = true;
}

function installRenderWrapper(state) {
  const current = window[legacyRenderer];
  const original = current && current.__peopleOriginal ? current.__peopleOriginal : current;
  const wrapped = function (...args) {
    if (args.length) memory.filter = String(args[0] ?? "");
    const result = original.call(this, args.length ? args[0] : memory.filter);
    if (state === active) {
      decorateList(state);
      // unflagUser() has no modal: its re-render is the signal that the action finished.
      if (state.parked && !legacyModalVisible()) state.unpark();
    }
    return result;
  };
  wrapped.__peopleOriginal = original;
  window[legacyRenderer] = wrapped;
  state.original = original;
  state.wrapped = wrapped;
}

function removeRenderWrapper(state) {
  if (window[legacyRenderer] === state.wrapped) window[legacyRenderer] = state.original;
}

export default {
  async mount(el, ctx = {}) {
    if (!el) throw new TypeError("User Management view requires a mount element");
    const target = document.getElementById("adminContent");
    if (!target || target !== el) {
      throw new Error("Transitional admin views must mount into #adminContent until extracted from admin.js");
    }
    if (typeof window[legacyRenderer] !== "function") throw new Error(`Legacy renderer ${legacyRenderer} is not available`);
    if (active) this.unmount();

    const api = ctx.api && typeof ctx.api.get === "function" ? ctx.api : { get: (path, options) => window.apiFetch(path, options) };
    const ui = ctx.ui || (await import("../ui.js")).default;
    const rawId = routeParam(ctx);
    const state = {
      ctx, api, ui, sources: {}, failed: [], parked: false, drawer: null, observer: null,
      original: null, wrapped: null, cleanup: [], openedFromList: false,
      unpark: null, sourcesLoaded: false
    };
    active = state;

    installRenderWrapper(state);
    window[legacyRenderer](memory.filter);             // synchronous, as before: restores search text, links the names
    // Normalized status badges need three extra requests; apply them when they arrive instead of delaying the list.
    refreshSources(state).then(() => { if (state === active) decorateList(state); });

    // Remember where the list was when a person opens a user from it.
    const onClick = event => {
      const link = event.target instanceof Element ? event.target.closest("a.user-link") : null;
      if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      memory.openedFromList = true;
      memory.openedId = link.dataset.userId || null;
      memory.scrollY = window.scrollY;
    };
    el.addEventListener("click", onClick, true);
    state.cleanup.push(() => el.removeEventListener("click", onClick, true));

    // A legacy modal closing means an action finished or was cancelled: refresh statuses and reopen the drawer.
    const modalEl = document.getElementById("modal");
    if (modalEl && typeof MutationObserver === "function") {
      let wasVisible = legacyModalVisible();
      state.observer = new MutationObserver(() => {
        const visible = legacyModalVisible();
        // While parked we are waiting for the legacy modal to go away, even if show+hide were batched into one callback.
        const closed = !visible && (wasVisible || state.parked);
        wasVisible = visible;
        if (!closed || state !== active) return;
        refreshSources(state).then(() => {
          if (state !== active) return;
          decorateList(state);
          if (state.parked) state.unpark();
        });
      });
      state.observer.observe(modalEl, { attributes: true, attributeFilter: ["style"] });
      state.cleanup.push(() => state.observer.disconnect());
    }

    if (rawId === undefined) {
      memory.openedFromList = false;
      if (memory.scrollY != null) window.scrollTo(0, memory.scrollY);
      const focusId = memory.openedId;
      memory.scrollY = null;
      memory.openedId = null;
      if (focusId != null) {
        // The router focuses the page heading after mount; focus the row afterwards.
        setTimeout(() => {
          if (state !== active) return;
          const link = el.querySelector(`a.user-link[data-user-id="${String(focusId).replace(/"/g, "")}"]`);
          if (link) link.focus({ preventScroll: true });
        }, 0);
      }
      return;
    }

    // ---- detail route ----
    state.openedFromList = memory.openedFromList;
    memory.openedFromList = false;
    if (memory.scrollY != null) window.scrollTo(0, memory.scrollY);
    removeStaleNotice();

    const closeToList = () => {
      if (state !== active) return;
      if (state.openedFromList) window.history.back();
      else if (ctx.router && typeof ctx.router.navigate === "function") ctx.router.navigate("/people/users", { replace: true });
      else window.location.hash = "#/people/users";
    };
    state.drawer = createUserDrawer({
      ui, api,
      userId: rawId,
      goToDeletionRequests: () => {
        if (ctx.router && typeof ctx.router.navigate === "function") ctx.router.navigate("/people/deletion-requests");
        else window.location.hash = "#/people/deletion-requests";
      },
      onUserClose: closeToList,
      runAction(kind, user) {
        const entry = LEGACY_ACTIONS[kind];
        const fn = entry && window[entry[0]];
        if (typeof fn !== "function") return false;
        state.parked = true;
        state.drawer.hide();
        try {
          Promise.resolve(fn(...entry[1](user))).catch(error => {
            console.error(`User action ${kind} failed:`, error);
            if (state === active && state.parked) state.unpark();
          });
        } catch (error) {
          console.error(`User action ${kind} failed:`, error);
          state.unpark();
        }
        return true;
      }
    });
    state.unpark = () => {
      if (!state.parked) return;
      state.parked = false;
      if (state === active && state.drawer) state.drawer.open();
    };
    state.drawer.open();
  },

  unmount() {
    const state = active;
    if (!state) return;
    active = null;
    state.cleanup.forEach(fn => fn());
    removeRenderWrapper(state);
    state.drawer?.dispose();
    if (state.parked && legacyModalVisible() && typeof window.closeModal === "function") window.closeModal();
    state.parked = false;
    // Keep list context only when the next page is still Users (list or detail).
    if (!USERS_HASH.test(String(window.location?.hash || ""))) {
      memory.filter = "";
      memory.scrollY = null;
      memory.openedId = null;
      memory.openedFromList = false;
    }
  }
};
