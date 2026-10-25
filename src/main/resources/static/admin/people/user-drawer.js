/**
 * User detail drawer (People > Users > :id).
 *
 * Built on ui.drawer(). All dynamic text goes in through textContent. The controller owns the
 * drawer only; routing (open/close vs. hash) is decided by the caller through `onUserClose`.
 *
 * States: loading -> ready | not-found | error. Daily usage loads separately and has its own
 * loading / empty / error state so a usage failure never hides the account details.
 */
import { deriveAccountStatusDetail } from "../account-status.js";
import { loadUsers, loadStatusSources, loadUsage, findUser, parseUserId } from "./users-data.js";

let stylesReady = false;
function ensureStyles() {
  if (stylesReady || typeof document === "undefined") return;
  stylesReady = true;
  const href = new URL("./user-drawer.css", import.meta.url).href;
  if ([...document.querySelectorAll('link[rel="stylesheet"]')].some(l => l.href === href)) return;
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = href;
  document.head.append(link);
}

function node(tag, text, className) {
  const el = document.createElement(tag);
  if (text != null) el.textContent = String(text);
  if (className) el.className = className;
  return el;
}

function formatWhen(value) {
  if (!value) return "";
  return String(value).replace("T", " ").slice(0, 16);
}

function actionLabel(actionType) {
  // ACTION_TYPE_LABELS is a global of the classic admin.js script; fall back to the raw key.
  const labels = typeof ACTION_TYPE_LABELS !== "undefined" ? ACTION_TYPE_LABELS : null;
  return (labels && labels[actionType]) || actionType;
}

function definitionList(rows) {
  const dl = node("dl", null, "user-drawer__list");
  for (const [label, value] of rows) {
    if (value == null || value === "") continue;
    dl.append(node("dt", label), node("dd", value));
  }
  return dl;
}

function section(title) {
  const el = node("section", null, "user-drawer__section");
  el.append(node("h3", title));
  return el;
}

function actionButton(label, onClick, { danger = false } = {}) {
  const b = node("button", label, `admin-ui-button${danger ? " admin-ui-button--danger" : ""}`);
  b.type = "button";
  b.addEventListener("click", onClick);
  return b;
}

/**
 * @param {object} options
 * @param {object} options.ui          admin/ui.js default export (drawer, statusBadge, empty)
 * @param {object} options.api         ctx.api ({ get })
 * @param {string} options.userId      raw :id route parameter
 * @param {(kind: string, user: object) => boolean} options.runAction
 *        runs an existing administrative action; returns false when it is unavailable
 * @param {() => void} options.goToDeletionRequests
 * @param {() => void} options.onUserClose   the person closed the drawer (Escape, backdrop, Close)
 */
export function createUserDrawer({ ui, api, userId, runAction, goToDeletionRequests, onUserClose }) {
  ensureStyles();
  const id = parseUserId(userId);
  let drawer = null;
  let body = null;
  let seq = 0;
  let disposed = false;
  let silentClose = false;
  let lastSnapshot = null; // { user, detail, extras }

  function setTitle(text) {
    const heading = drawer?.element?.querySelector?.(".admin-ui-drawer__title");
    if (heading) heading.textContent = text;
  }

  function show(...nodes) {
    if (!body) return;
    body.replaceChildren(...nodes);
    body.removeAttribute("aria-busy");
  }

  function renderLoading() {
    const p = node("p", "Loading user details…", "user-drawer__muted");
    p.setAttribute("role", "status");
    show(p);
    body?.setAttribute("aria-busy", "true");
  }

  function renderError(message) {
    setTitle("User details");
    const wrap = node("div", null, "user-drawer");
    const p = node("p", message || "Could not load user details.", "user-drawer__error");
    p.setAttribute("role", "alert");
    wrap.append(p, actionButton("Retry", () => refresh()));
    show(wrap);
  }

  function renderNotFound() {
    setTitle("User not found");
    const wrap = node("div", null, "user-drawer");
    wrap.append(ui.empty({ title: "User not found", action: { label: "Back to users", onClick: () => onUserClose() } }));
    wrap.append(node("p", id == null
      ? `"${userId}" is not a valid user ID.`
      : `No user with ID ${id} exists in the current user list. The account may have been deleted.`, "user-drawer__muted"));
    show(wrap);
  }

  function renderUsage(container, state) {
    container.replaceChildren(node("h3", "Daily usage today"));
    if (state.status === "loading") {
      const p = node("p", "Loading usage…", "user-drawer__muted");
      p.setAttribute("role", "status");
      container.append(p);
    } else if (state.status === "error") {
      const p = node("p", `Could not load usage: ${state.message}`, "user-drawer__error");
      p.setAttribute("role", "alert");
      container.append(p, actionButton("Retry", () => loadUsageInto(container)));
    } else if (!state.rows.length) {
      container.append(node("p", "No daily limits are configured.", "user-drawer__muted"));
    } else {
      const table = node("table", null, "user-drawer__table");
      const head = node("tr");
      for (const [label, num] of [["Action", false], ["Used", true], ["Limit", true]]) {
        const th = node("th", label, num ? "num" : "");
        th.scope = "col";
        head.append(th);
      }
      const thead = node("thead");
      thead.append(head);
      const tbody = node("tbody");
      for (const row of state.rows) {
        const tr = node("tr");
        const personal = row.personalLimit !== null && row.personalLimit !== undefined;
        const limit = row.limit ?? (personal ? row.personalLimit : row.globalLimit);
        tr.append(
          node("td", actionLabel(row.actionType)),
          node("td", row.used ?? 0, "num"),
          node("td", personal ? `${limit} (personal)` : limit, "num")
        );
        tbody.append(tr);
      }
      table.append(thead, tbody);
      container.append(table);
    }
  }

  async function loadUsageInto(container) {
    const mine = seq;
    renderUsage(container, { status: "loading" });
    try {
      const rows = await loadUsage(api, id);
      if (disposed || mine !== seq) return;
      renderUsage(container, { status: "ready", rows });
    } catch (error) {
      if (disposed || mine !== seq) return;
      renderUsage(container, { status: "error", message: error?.message || "Unknown error" });
    }
  }

  function buildActions(user, detail, extras) {
    const wrap = section("Actions");
    const row = node("div", null, "user-drawer__actions");
    const act = (kind, label, opts) => row.append(actionButton(label, () => {
      if (!runAction(kind, user)) {
        const p = node("p", "That action is not available on this page. Reload and try again.", "user-drawer__error");
        p.setAttribute("role", "alert");
        wrap.append(p);
      }
    }, opts));

    const selfDeletion = Boolean(extras.selfDeletion);
    act("topics", "Uploads");
    act("usage", "Edit usage");
    if (selfDeletion) {
      // The backend swaps the email for a placeholder and suspends the account. Changing flag, suspension
      // or ban state against that placeholder would leave the account in an inconsistent state.
      row.append(actionButton("Open deletion requests", () => goToDeletionRequests()));
      wrap.append(row, node("p", "This account has a self-requested deletion pending. Restore or delete it from Deletion requests.", "user-drawer__muted"));
      return wrap;
    }
    if (user.flagged) act("unflag", "Unflag"); else act("flag", "Flag");
    if (!user.isAdmin) {
      if (user.archived) act("unarchive", "Restore"); else act("archive", "Suspend");
      if (extras.bannedEntry) act("unban", "Unban email"); else act("ban", "Ban email", { danger: true });
      act("promote", "Promote to admin");
      act("delete", "Request deletion", { danger: true });
    }
    wrap.append(row);
    return wrap;
  }

  function renderReady({ user, detail, extras }) {
    setTitle(user.fullName || "User details");
    const wrap = node("div", null, "user-drawer");

    const head = node("div", null, "user-drawer__head");
    head.append(ui.statusBadge(detail.status), node("span", user.isAdmin ? "Administrator" : "Student", "user-drawer__role"));
    wrap.append(head);

    if (detail.uncertain.length) {
      const box = node("div", null, "user-drawer__uncertain");
      box.setAttribute("role", "note");
      for (const item of detail.uncertain) box.append(node("p", item.text));
      wrap.append(box);
    }

    const account = section("Account");
    account.append(definitionList([
      ["User ID", user.id],
      ["Name", user.fullName],
      [extras.selfDeletion ? "Current email" : "Email", user.email],
      ["Original email", extras.selfDeletion?.originalEmail],
      ["Role", user.isAdmin ? "Administrator" : "Student"]
    ]));
    wrap.append(account);

    const status = section("Status");
    const list = node("ul", null, "user-drawer__notes");
    if (!detail.reasons.length) list.append(node("li", "No flag, suspension, ban or deletion request applies."));
    for (const reason of detail.reasons) list.append(node("li", reason.text));
    status.append(list);
    const facts = definitionList([
      ["Flag reason", user.flagged ? user.flagReason : null],
      ["Suspension reason", user.archived && !extras.selfDeletion ? user.archiveReason : null],
      ["Ban reason", extras.bannedEntry?.reason],
      ["Banned by", extras.bannedEntry?.bannedBy],
      ["Banned at", formatWhen(extras.bannedEntry?.bannedAt)],
      ["Deletion requested", formatWhen(extras.selfDeletion?.requestedAt)],
      ["Admin deletion request", extras.adminDeletion ? `${extras.adminDeletion.status}${extras.adminDeletion.approveCount != null ? ` — ${extras.adminDeletion.approveCount} of ${extras.adminDeletion.requiredCount} approvals` : ""}` : null],
      ["Deletion reason", extras.adminDeletion?.reason],
      ["Requested by", extras.adminDeletion?.requestedBy]
    ]);
    if (facts.childElementCount) status.append(facts);
    wrap.append(status);

    const usage = node("section", null, "user-drawer__section");
    wrap.append(usage);
    wrap.append(buildActions(user, detail, extras));
    show(wrap);
    loadUsageInto(usage);
  }

  function extrasFor(user, sources) {
    const email = String(user.email ?? "").trim().toLowerCase();
    return {
      selfDeletion: (sources.selfDeletionRequests || []).find(r => r && String(r.id) === String(user.id)) || null,
      bannedEntry: (sources.bannedEmails || []).find(b => b && String(b.email ?? "").trim().toLowerCase() === email) || null,
      adminDeletion: (sources.deletionRequests || []).find(r => r
        && ["PENDING", "APPROVED"].includes(String(r.status ?? "").toUpperCase())
        && String(r.targetEmail ?? "").trim().toLowerCase() === email) || null
    };
  }

  async function refresh() {
    if (disposed || !drawer) return;
    const mine = ++seq;
    if (id == null) { renderNotFound(); return; }
    renderLoading();
    let users;
    let statusSources;
    try {
      [users, statusSources] = await Promise.all([loadUsers(api), loadStatusSources(api)]);
    } catch (error) {
      if (disposed || mine !== seq) return;
      renderError(error?.message || "Could not load user details.");
      return;
    }
    if (disposed || mine !== seq) return;
    const user = findUser(users, id);
    if (!user) { renderNotFound(); return; }
    const detail = deriveAccountStatusDetail(user, statusSources.sources);
    lastSnapshot = { user, detail, extras: extrasFor(user, statusSources.sources) };
    renderReady(lastSnapshot);
  }

  function open() {
    if (disposed || drawer) return;
    body = node("div");
    silentClose = false;
    drawer = ui.drawer({
      title: "User details",
      content: body,
      onClose() {
        const wasSilent = silentClose;
        drawer = null;
        body = null;
        silentClose = false;
        if (!wasSilent && !disposed) onUserClose();
      }
    });
    refresh();
  }

  /** Close without telling the caller the person dismissed it (parking, unmount, route change). */
  function hide() {
    if (!drawer) return;
    silentClose = true;
    drawer.close();
  }

  return {
    open,
    hide,
    refresh,
    isOpen: () => Boolean(drawer),
    dispose() { disposed = true; seq++; hide(); }
  };
}
