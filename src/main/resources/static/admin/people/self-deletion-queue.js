/**
 * People > Deletion requests: the SELF-REQUESTED deletion queue.
 *
 * This is a different workflow from the admin-initiated dual-control queue rendered by
 * admin/legacy/deletionrequests.js. A user asked to delete their own account (the account is
 * already suspended and locked behind a placeholder email); ONE administrator reviews it and either
 * deletes it permanently or restores access. There are no votes, no status filters, no request record.
 *
 * Endpoints (all existing; nothing here invents an endpoint or a response field):
 *   GET  /api/admin/self-deletion-requests               -> { success, requests: [{ id, fullName, originalEmail, requestedAt }] }
 *   POST /api/admin/self-deletion-requests/{id}/execute  -> permanently deletes the account
 *   POST /api/admin/self-deletion-requests/{id}/restore  -> restores the original email and access
 *
 * ID: `id` is the USER ACCOUNT id, not an admin-initiated deletion-request id. The two id spaces
 * overlap numerically and must never be mixed (see admin/account-status.js).
 *
 * Safety rules implemented here:
 *   - All server-provided text is inserted with textContent. No innerHTML anywhere.
 *   - Permanent deletion uses ui.confirm({ tier: "destructive", typeToConfirm: "DELETE" }).
 *   - Restoration has its own, separate confirm.
 *   - An item leaves the list only after the server confirmed success (or a fresh list no longer has it).
 *     A failed request never removes anything.
 *   - One action at a time; repeated clicks while one is running are ignored.
 */
import { parseUserId } from "./users-data.js";

export const SELF_DELETION_PATHS = Object.freeze({
  list: "/api/admin/self-deletion-requests",
  execute: id => `/api/admin/self-deletion-requests/${id}/execute`,
  restore: id => `/api/admin/self-deletion-requests/${id}/restore`
});

export const DELETE_CONFIRM_TEXT = "DELETE";

let stylesRequested = false;
function ensureStyles() {
  if (stylesRequested || typeof document === "undefined") return;
  stylesRequested = true;
  const href = new URL("./self-deletion-queue.css", import.meta.url).href;
  if ([...document.querySelectorAll('link[rel="stylesheet"]')].some(l => l.href === href)) return;
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = href;
  document.head.append(link);
}

function node(tag, className, text) {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text != null) el.textContent = String(text);
  return el;
}

function formatWhen(value) {
  if (!value) return "Unknown";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? "Unknown" : d.toLocaleString();
}

function messageOf(error, fallback = "Request failed.") {
  const text = error && typeof error.message === "string" ? error.message.trim() : "";
  return text || fallback;
}

/**
 * Sorts failures into the cases the UI treats differently.
 *   stale     the account is gone or no longer has a pending self-deletion request (404, or the
 *             controller's 400 "No pending self-deletion request on this account.")
 *   conflict  restore only: the original email now belongs to another account (controller 400)
 *   error     anything else (network failure, 401/403, 5xx, unexpected 4xx)
 */
export function classifyFailure(kind, error) {
  const status = error && error.status;
  const text = messageOf(error, "");
  if (status === 404 || (status === 400 && /no pending self-deletion request/i.test(text))) return "stale";
  if (kind === "restore" && status === 400 && /already used by|can.?t restore/i.test(text)) return "conflict";
  return "error";
}

/** Returns usable items only, oldest request first (the longest-waiting user is first). */
export function normalizeRequests(response) {
  const list = Array.isArray(response) ? response : response && Array.isArray(response.requests) ? response.requests : null;
  if (!list) throw new Error("The server returned an unexpected response for self-requested deletions.");
  const time = r => { const t = new Date(r.requestedAt).getTime(); return Number.isNaN(t) ? Infinity : t; };
  return list
    .filter(r => r && typeof r === "object")
    .map(r => ({ ...r, userId: parseUserId(r.id) }))
    .sort((a, b) => time(a) - time(b) || String(a.id).localeCompare(String(b.id)));
}

/**
 * @param {object} options
 * @param {{get:Function, request:Function}} options.api   ctx.api (apiFetch wrappers; request adds the CSRF header)
 * @param {object} options.ui                              admin/ui.js default export (confirm, toast, empty, statusBadge)
 * @param {{activityLog?:Function, users?:Function, badge?:Function}} [options.refresh]  refreshers run after a successful action
 *        (`badge` also runs after a stale outcome, when the server shows the queue changed without this admin's action)
 * @param {(state:{count:(number|null), status:string})=>void} [options.onChange]
 */
export function createSelfDeletionQueue({ api, ui, refresh = {}, onChange = () => {} } = {}) {
  if (!api || typeof api.get !== "function" || typeof api.request !== "function") throw new TypeError("Self-deletion queue requires ctx.api.get and ctx.api.request");
  if (!ui || typeof ui.confirm !== "function") throw new TypeError("Self-deletion queue requires the shared ui.confirm component");
  ensureStyles();

  let destroyed = false;
  let seq = 0;
  let items = null;          // null until the first successful load
  let loading = false;
  let loadError = null;      // message of the latest failed load
  let inFlight = null;       // { kind, id } while an action runs
  const notes = new Map();   // user id -> { type, text } (inline per-row messages)

  const root = node("section", "admin-card sdq");
  root.id = "selfDeletionSection";
  const headingId = "selfDeletionHeading";
  root.setAttribute("aria-labelledby", headingId);

  const head = node("div", "sdq__head");
  const headText = node("div", "sdq__headtext");
  headText.append(node("div", "sdq__label", "User-initiated · one-admin review"));
  const heading = node("h2", "admin-card-title sdq__title");
  heading.id = headingId;
  heading.tabIndex = -1;
  const headingLabel = node("span", "", "Self-requested deletions");
  const headingCount = node("span", "sdq__count");
  heading.append(headingLabel, headingCount);
  headText.append(heading);
  headText.append(node("p", "admin-card-hint sdq__hint",
    "These users asked to delete their own accounts and are already locked out. One administrator decides each one: delete the account permanently, or restore the user's access. " +
    "There are no votes here, and this queue is separate from the admin-initiated dual-control requests above."));
  const refreshButton = node("button", "admin-ui-button sdq__refresh", "Refresh");
  refreshButton.type = "button";
  head.append(headText, refreshButton);

  const banner = node("div", "sdq__banner");
  banner.setAttribute("role", "alert");
  banner.hidden = true;
  const body = node("div", "sdq__body");
  body.setAttribute("aria-live", "polite");
  root.append(head, banner, body);

  const rows = new Map();    // user id -> { note, buttons: [button, ...] }

  function allActionButtons() { return [...root.querySelectorAll("button[data-sdq-action]")]; }

  function syncControls() {
    const busy = Boolean(inFlight) || loading;
    refreshButton.setAttribute("aria-disabled", String(busy));
    for (const b of allActionButtons()) {
      const mine = inFlight && String(inFlight.id) === b.dataset.sdqId && inFlight.kind === b.dataset.sdqAction;
      b.setAttribute("aria-disabled", String(Boolean(inFlight) || b.dataset.sdqUnusable === "true"));
      if (b.dataset.sdqIdle === undefined) b.dataset.sdqIdle = b.textContent;
      b.textContent = mine && inFlight.phase === "request" ? (inFlight.kind === "execute" ? "Deleting…" : "Restoring…") : b.dataset.sdqIdle;
    }
    body.setAttribute("aria-busy", String(loading));
  }

  function emit() {
    headingCount.textContent = items ? ` (${items.length})` : "";
    onChange({ count: items ? items.length : null, status: items ? "ready" : loadError ? "error" : "loading" });
  }

  function setNote(id, type, text) {
    if (type) notes.set(id, { type, text }); else notes.delete(id);
    const row = rows.get(id);
    if (!row) return;
    row.note.replaceChildren();
    row.note.hidden = !type;
    row.note.className = `sdq__note${type ? ` sdq__note--${type}` : ""}`;
    if (type) row.note.append(node("p", "", text));
  }

  function renderBanner() {
    banner.replaceChildren();
    banner.hidden = !(loadError && items);   // a failed first load is a full error state instead
    if (banner.hidden) return;
    banner.append(node("span", "", `Could not refresh the queue (${loadError}). The list below is the last one that loaded and may be out of date. `));
    const retry = node("button", "admin-ui-button", "Retry");
    retry.type = "button";
    retry.addEventListener("click", () => { void load(); });
    banner.append(retry);
  }

  function renderItem(item) {
    const id = item.userId;
    const name = typeof item.fullName === "string" && item.fullName.trim() ? item.fullName.trim() : "Unnamed user";
    const li = node("li", "sdq__item");
    if (id != null) li.dataset.userId = String(id);

    const top = node("div", "sdq__itemhead");
    top.append(node("strong", "sdq__name", name), ui.statusBadge("DELETION_PENDING"));
    li.append(top);

    const facts = node("dl", "sdq__facts");
    const fact = (label, value, className) => { facts.append(node("dt", "", label), node("dd", className || "", value)); };
    fact("Original email", item.originalEmail || "Not recorded");
    fact("Requested", formatWhen(item.requestedAt));
    fact("Account ID", id != null ? String(id) : `${String(item.id ?? "missing")} (invalid)`);
    li.append(facts);

    const note = node("div", "sdq__note");
    note.hidden = true;
    note.setAttribute("role", "status");
    li.append(note);

    const actions = node("div", "sdq__actions");
    const buttons = [];
    const unusable = id == null;
    const addButton = (label, kind, className) => {
      const b = node("button", `admin-ui-button ${className}`.trim(), label);
      b.type = "button";
      b.dataset.sdqAction = kind;
      b.dataset.sdqId = String(id);
      b.dataset.sdqIdle = label;
      if (unusable) { b.dataset.sdqUnusable = "true"; b.setAttribute("aria-disabled", "true"); }
      b.addEventListener("click", () => { void runAction(kind, item); });
      actions.append(b); buttons.push(b);
    };
    if (unusable) {
      li.append(node("p", "sdq__note sdq__note--error", "This record has no valid account ID, so it cannot be acted on from here."));
    } else {
      const link = node("a", "sdq__link", "View account");
      link.href = `#/people/users/${id}`;
      actions.append(link);
    }
    addButton("Restore access", "restore", "sdq__restore");
    addButton("Delete permanently", "execute", "admin-ui-button--danger sdq__delete");
    li.append(actions);

    if (id != null) {
      rows.set(id, { note, buttons });
      const saved = notes.get(id);
      if (saved) { note.hidden = false; note.className = `sdq__note sdq__note--${saved.type}`; note.append(node("p", "", saved.text)); }
    }
    return li;
  }

  function render() {
    rows.clear();
    body.replaceChildren();
    renderBanner();
    if (items === null) {
      if (loadError) {
        const wrap = node("div", "sdq__state");
        wrap.setAttribute("role", "alert");
        wrap.append(ui.empty({ title: `Could not load self-requested deletions: ${loadError}`, action: { label: "Retry", onClick: () => { void load(); } } }));
        body.append(wrap);
      } else {
        body.append(node("p", "sdq__state", "Loading self-requested deletions…"));
      }
    } else if (items.length === 0) {
      body.append(ui.empty({ title: "No self-requested deletions are waiting for review." }));
    } else {
      const list = node("ul", "sdq__list");
      list.setAttribute("aria-label", "Self-requested account deletions");
      for (const item of items) list.append(renderItem(item));
      body.append(list);
    }
    emit();
    syncControls();
  }

  /** Loads the queue. Latest call wins; a failed refresh keeps the previous list visible. */
  async function load({ keepNotes = false } = {}) {
    if (destroyed) return;
    const mine = ++seq;
    loading = true;
    if (!keepNotes) notes.clear();
    if (items === null) { loadError = null; render(); } else syncControls();
    try {
      const next = normalizeRequests(await api.get(SELF_DELETION_PATHS.list));
      if (mine !== seq || destroyed) return;
      items = next;
      loadError = null;
    } catch (error) {
      if (mine !== seq || destroyed) return;
      loadError = messageOf(error, "Unknown error");
    }
    loading = false;
    render();
  }

  function confirmOptions(kind, item) {
    const who = typeof item.fullName === "string" && item.fullName.trim() ? item.fullName.trim() : "This user";
    const email = item.originalEmail || "an email that was not recorded";
    if (kind === "execute") {
      return {
        tier: "destructive",
        title: "Permanently delete this account?",
        message: `${who} (${email}) asked to delete their own account. If you continue, the account will be irreversibly removed. This cannot be undone and the account cannot be restored afterwards.`,
        impact: "The account is removed for good. Unlike admin-initiated deletions, no other administrator votes on this: your confirmation is the only review step.",
        typeToConfirm: DELETE_CONFIRM_TEXT
      };
    }
    return {
      tier: "standard",
      title: "Restore this account?",
      message: `${who}'s deletion request will be cancelled and the account will be restored to ${email}. The user's access is restored and they can log in again with that email.`,
      impact: "This does nothing if that email has since been registered by a newer account; the server refuses the restore and the request stays in this queue."
    };
  }

  async function afterSuccess(kind, item, response) {
    const fallback = kind === "execute" ? "Account permanently deleted." : "Account restored. The user can log in again with their original email.";
    const message = response && typeof response.message === "string" && response.message.trim() ? response.message.trim() : fallback;
    if (!destroyed) {
      // The server confirmed this exact request is done, so it is safe to drop it right away; the reload below stays authoritative.
      items = (items || []).filter(r => r.userId !== item.userId);
      notes.delete(item.userId);
      render();
      ui.toast(message, { type: "success" });
      heading.focus({ preventScroll: true });
    }
    const jobs = [refresh.activityLog, refresh.users, refresh.badge].filter(fn => typeof fn === "function").map(fn => Promise.resolve().then(fn));
    if (!destroyed) jobs.push(load({ keepNotes: true }));
    await Promise.allSettled(jobs);   // a failed refresh must never turn a completed action into a failure
  }

  async function runAction(kind, item) {
    if (destroyed || inFlight || item.userId == null) return;   // repeated clicks / unusable records
    const id = item.userId;
    inFlight = { kind, id, phase: "confirm" };
    setNote(id, null);
    syncControls();
    try {
      let decision;
      try { decision = await ui.confirm(confirmOptions(kind, item)); }
      catch (error) { setNote(id, "error", `Could not open the confirmation: ${messageOf(error)}`); return; }
      if (destroyed || !decision || decision.confirmed !== true) return;   // cancelled, mismatched DELETE text, or navigated away
      inFlight.phase = "request";
      syncControls();

      let response;
      try {
        response = await api.request(SELF_DELETION_PATHS[kind](id), { method: "POST" });
        if (response && response.success === false) throw Object.assign(new Error(messageOf(response, "The server did not complete the request.")), { status: 400 });
      } catch (error) {
        if (destroyed) return;
        const type = classifyFailure(kind, error);
        const text = messageOf(error);
        if (type === "stale") {
          setNote(id, "error", `${text} This request may already have been handled, so the list is being refreshed.`);
          ui.toast(`${text} The list has been refreshed.`, { type: "error" });
          inFlight = null; syncControls();
          await load({ keepNotes: true });     // authoritative: the item disappears only if the server no longer lists it
          // The reload replaced the row that held keyboard focus; do not leave focus on <body>.
          if (!destroyed && !root.contains(document.activeElement)) heading.focus({ preventScroll: true });
          if (typeof refresh.badge === "function") void Promise.resolve().then(refresh.badge).catch(() => {});   // the server says the queue changed elsewhere: resync the sidebar count too
        } else if (type === "conflict") {
          setNote(id, "conflict", `${text} The account was not restored and is still waiting in this queue. Permanent deletion is still available.`);
          ui.toast("Restore failed: the original email is already used by another account.", { type: "error" });
        } else {
          setNote(id, "error", `${kind === "execute" ? "Deletion" : "Restore"} failed: ${text} Nothing was changed in this list; you can try again.`);
          ui.toast(`${kind === "execute" ? "Deletion" : "Restore"} failed: ${text}`, { type: "error" });
        }
        return;
      }
      inFlight = null;
      await afterSuccess(kind, item, response);
    } finally {
      inFlight = null;
      if (!destroyed) syncControls();
    }
  }

  refreshButton.addEventListener("click", () => { if (!inFlight && !loading) void load(); });
  render();

  return {
    element: root,
    heading,
    load,
    getItems: () => (items ? items.slice() : null),
    destroy() {
      if (destroyed) return;
      destroyed = true;
      seq += 1;
      notes.clear(); rows.clear();
      root.remove();
    }
  };
}

/** The short "two different workflows" guide shown above both queues. */
export function createWorkflowGuide({ onJump } = {}) {
  const guide = node("nav", "sdq-guide");
  guide.setAttribute("aria-label", "Deletion workflows");
  const card = (kindClass, label, text, target, linkText) => {
    const box = node("div", `sdq-guide__card ${kindClass}`);
    box.append(node("div", "sdq__label", label), node("p", "", text));
    const jump = node("button", "sdq-guide__jump", linkText);
    jump.type = "button";
    jump.addEventListener("click", () => { if (typeof onJump === "function") onJump(target); });
    box.append(jump);
    guide.append(box);
    return jump;
  };
  card("sdq-guide__card--dual", "Admin-initiated · dual control",
    "An administrator asked to delete another user's account. Every current admin must approve; deleting is a separate final click.",
    "dual", "Go to admin-initiated requests");
  const selfJump = card("sdq-guide__card--self", "Self-requested · one-admin review",
    "The user asked to delete their own account and is already locked out. One administrator either deletes it permanently or restores access.",
    "self", "Go to self-requested deletions");
  return {
    element: guide,
    setSelfCount(count) { selfJump.textContent = count == null ? "Go to self-requested deletions" : `Go to self-requested deletions (${count})`; }
  };
}

export default createSelfDeletionQueue;
