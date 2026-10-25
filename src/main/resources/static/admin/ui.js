/**
 * Contract-freeze UI primitives. Deliberately framework-free; public signatures and
 * return shapes are frozen (see docs/admin-contract-freeze.md). Styling lives in
 * admin-components.css / admin-tokens.css, which this module loads itself so
 * admin.html does not need to change. All user/server strings are inserted with
 * textContent (never innerHTML).
 */

// ---------- helpers ----------
let stylesReady = null;
function ensureStyles() {
  if (stylesReady) return stylesReady;
  if (typeof document === "undefined") return Promise.resolve();
  const href = new URL("./admin-components.css", import.meta.url).href;
  const existing = [...document.querySelectorAll('link[rel="stylesheet"]')].find(l => l.href === href);
  if (existing) return (stylesReady = Promise.resolve());
  const link = document.createElement("link");
  link.rel = "stylesheet"; link.href = href;
  stylesReady = new Promise(resolve => {
    link.addEventListener("load", resolve, { once: true });
    link.addEventListener("error", resolve, { once: true });
    window.setTimeout(resolve, 1500); // never block UI on a slow stylesheet
  });
  document.head.append(link);
  return stylesReady;
}
if (typeof document !== "undefined") ensureStyles(); // start loading early

const INTERACTIVE_SELECTOR = 'a[href],button,input,select,textarea,label,summary,[role="button"],[role="link"],[role="checkbox"],[role="menuitem"],[contenteditable=""],[contenteditable="true"],[tabindex]:not(tr)';

let idCounter = 0;
const nextId = prefix => `${prefix}-${++idCounter}`;
const safeToken = value => String(value ?? "").toLowerCase().replace(/[^a-z0-9_-]/g, "") || "info";

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}
function asNode(value) {
  if (value instanceof Node) return value;
  const node = document.createElement("div");
  node.textContent = value == null ? "" : String(value);
  return node;
}
function button(label, className, onClick) {
  const b = el("button", `admin-ui-button ${className || ""}`.trim(), label);
  b.type = "button";
  if (onClick) b.addEventListener("click", onClick);
  return b;
}

// Backdrop click should only count when the press AND release both happened on the backdrop
// (so selecting text and releasing outside the panel does not close the dialog).
function onBackdropClick(dialog, handler) {
  let pressedOnBackdrop = false;
  dialog.addEventListener("mousedown", e => { pressedOnBackdrop = e.target === dialog; });
  dialog.addEventListener("click", e => { if (e.target === dialog && pressedOnBackdrop) handler(); pressedOnBackdrop = false; });
}
function restoreFocus(opener) {
  if (opener && opener.isConnected && typeof opener.focus === "function") opener.focus({ preventScroll: true });
}

// ---------- toast region (persistent live regions) ----------
let toastRegion = null, politeHost = null, assertiveHost = null;
function getToastRegion() {
  if (toastRegion && toastRegion.isConnected) return toastRegion;
  toastRegion = el("div", "admin-ui-toasts");
  politeHost = el("div"); politeHost.setAttribute("role", "status");
  assertiveHost = el("div"); assertiveHost.setAttribute("role", "alert");
  toastRegion.append(politeHost, assertiveHost);
  document.body.append(toastRegion);
  return toastRegion;
}
// A modal <dialog> makes everything outside it inert and sits in the top layer, so while one is
// open the toast region lives inside the top-most open dialog (keeps toasts visible and Undo clickable).
function placeToastRegion() {
  const region = getToastRegion();
  const open = [...document.querySelectorAll("dialog[open]")].pop();
  const host = open || document.body;
  if (region.parentNode !== host) host.append(region);
}
function releaseToastRegion(dialog) {
  if (toastRegion && toastRegion.parentNode === dialog) document.body.append(toastRegion);
}

// ---------- modal core (shared by modal/confirm) ----------
function openModal({ title, body, actions = [], size = "md", role, describeBody = false, initialFocus } = {}) {
  return new Promise(resolve => {
    ensureStyles().then(() => {
      const opener = document.activeElement;
      const dialog = el("dialog", `admin-ui-dialog admin-ui-dialog--${safeToken(size)}`);
      if (role) dialog.setAttribute("role", role);
      const shell = el("div", "admin-ui-dialog__shell");
      if (title) {
        const heading = el("h2", "admin-ui-dialog__title", title);
        heading.id = nextId("admin-ui-dialog-title");
        dialog.setAttribute("aria-labelledby", heading.id);
        shell.append(heading);
      } else {
        dialog.setAttribute("aria-label", "Dialog");
      }
      const bodyNode = asNode(body);
      if (describeBody) {
        if (!bodyNode.id) bodyNode.id = nextId("admin-ui-dialog-body");
        dialog.setAttribute("aria-describedby", bodyNode.id);
      }
      const footer = el("div", "admin-ui-dialog__footer");
      shell.append(bodyNode, footer);
      dialog.append(shell);
      document.body.append(dialog);

      let settled = false, busy = false;
      const settle = value => { if (!settled) { settled = true; resolve(value); } };

      const choices = actions.length ? actions : [{ label: "Close", value: null }];
      const buttons = choices.map(action => {
        const b = button(action.label || "Continue", action.primary ? (action.danger ? "admin-ui-button--danger" : "admin-ui-button--primary") : "");
        b.addEventListener("click", async () => {
          if (busy) return;
          busy = true; dialog.setAttribute("aria-busy", "true");
          buttons.forEach(x => x.setAttribute("aria-disabled", "true"));
          try { if (action.onClick) await action.onClick(); }
          catch (error) { settle({ action: "error", error }); dialog.close(); return; }
          settle({ action: action.value ?? action.id ?? action.label ?? null });
          dialog.close();
        });
        footer.append(b);
        return b;
      });

      dialog.addEventListener("cancel", event => { if (busy) { event.preventDefault(); return; } settle(null); }); // Escape
      onBackdropClick(dialog, () => { if (!busy) { settle(null); dialog.close(); } });
      dialog.addEventListener("close", () => {
        settle(null); // closed by any other route: always resolve so callers never hang
        releaseToastRegion(dialog);
        dialog.remove();
        restoreFocus(opener);
      }, { once: true });

      dialog.showModal();
      if (initialFocus && initialFocus.isConnected) initialFocus.focus();
      placeToastRegion();
    });
  });
}

export const ui = {
  modal({ title, body, actions = [], size = "md" } = {}) {
    return openModal({ title, body, actions, size });
  },

  drawer({ title, content, onClose } = {}) {
    ensureStyles();
    const opener = document.activeElement;
    const dialog = el("dialog", "admin-ui-drawer");
    const panel = el("div", "admin-ui-drawer__panel");
    const head = el("div", "admin-ui-drawer__head");
    const h = el("h2", "admin-ui-drawer__title", title || "Details");
    h.id = nextId("admin-ui-drawer-title");
    dialog.setAttribute("aria-labelledby", h.id);
    let closed = false;
    function finish() {
      if (closed) return; closed = true;
      if (dialog.open) dialog.close();
      releaseToastRegion(dialog);
      dialog.remove();
      restoreFocus(opener);
      if (typeof onClose === "function") onClose();
    }
    const closeButton = button("Close", "", finish);
    head.append(h, closeButton);
    panel.append(head, asNode(content));
    dialog.append(panel);
    document.body.append(dialog);
    dialog.addEventListener("close", finish); // Escape (native cancel -> close)
    onBackdropClick(dialog, finish);
    dialog.showModal();
    placeToastRegion();
    return { element: panel, close: finish, destroy: finish };
  },

  toast(msg, { type = "info", undo } = {}) {
    ensureStyles();
    placeToastRegion();
    const kind = safeToken(type);
    const toast = el("div", `admin-ui-toast admin-ui-toast--${kind}`);
    toast.append(el("span", "", String(msg ?? "")));
    let timer = null;
    const remove = () => { window.clearTimeout(timer); toast.remove(); };
    const start = () => { window.clearTimeout(timer); timer = window.setTimeout(remove, 6000); };
    if (typeof undo === "function") {
      const undoButton = button("Undo", "admin-ui-toast__undo");
      undoButton.classList.remove("admin-ui-button");
      let used = false;
      undoButton.addEventListener("click", () => {
        if (used) return; used = true;
        try { undo(); } finally { remove(); }
      });
      toast.append(undoButton);
    }
    // Pause the auto-dismiss timer while the pointer or keyboard focus is on the toast.
    toast.addEventListener("mouseenter", () => window.clearTimeout(timer));
    toast.addEventListener("mouseleave", start);
    toast.addEventListener("focusin", () => window.clearTimeout(timer));
    toast.addEventListener("focusout", start);
    (kind === "error" ? assertiveHost : politeHost).append(toast);
    start();
    return toast;
  },

  async confirm({ tier = "standard", title = "Confirm action", message = "Are you sure?", impact, typeToConfirm, reasonRequired = false } = {}) {
    const wrap = el("div", "admin-ui-confirm");
    wrap.append(el("p", "", message));
    if (impact) {
      const note = el("p", "admin-ui-impact");
      note.append(el("strong", "", "Impact: "), document.createTextNode(String(impact)));
      wrap.append(note);
    }
    let typedInput = null, reasonInput = null;
    if (typeToConfirm) {
      const label = el("label", "admin-ui-field", `Type ${typeToConfirm} to confirm`);
      typedInput = el("input"); typedInput.type = "text"; typedInput.autocomplete = "off"; typedInput.setAttribute("aria-required", "true");
      label.append(typedInput); wrap.append(label);
    }
    if (reasonRequired) {
      const label = el("label", "admin-ui-field", "Reason (required)");
      reasonInput = el("textarea"); reasonInput.rows = 3; reasonInput.required = true; reasonInput.setAttribute("aria-required", "true");
      label.append(reasonInput); wrap.append(label);
    }
    const destructive = tier === "destructive";
    const result = await openModal({
      title, body: wrap, size: "md", role: "alertdialog", describeBody: true,
      initialFocus: typedInput || reasonInput || undefined, // otherwise the browser focuses Cancel (the safe choice)
      actions: [
        { label: "Cancel", value: false },
        { label: destructive ? "Confirm destructive action" : "Confirm", value: true, primary: true, danger: destructive }
      ]
    });
    const actionValue = result && result.action;
    const confirmed = actionValue === true || actionValue === "true";
    const validType = !typeToConfirm || typedInput.value.trim() === String(typeToConfirm);
    const reason = reasonInput ? reasonInput.value.trim() : "";
    const validReason = !reasonRequired || reason.length > 0;
    if (confirmed && (!validType || !validReason)) {
      ui.toast(!validType ? "The confirmation text does not match." : "A reason is required.", { type: "error" });
      return { confirmed: false, reason };
    }
    return { confirmed, reason };
  },

  table({ columns = [], fetch: fetchRows, rowKey = "id", onRowClick, selectable = false, label } = {}) {
    ensureStyles();
    const root = el("div", "admin-ui-table-wrap");
    const table = el("table", "admin-ui-table");
    const thead = document.createElement("thead"), tbody = document.createElement("tbody");
    const headRow = document.createElement("tr");
    const addHeader = text => { const th = el("th", "", text); th.scope = "col"; headRow.append(th); };
    if (selectable) addHeader("Select");
    for (const column of columns) addHeader(column.label || column.title || column.key || "");
    thead.append(headRow); table.append(thead, tbody); root.append(table);
    // Keyboard access: a scrollable region must be focusable and named. Optional `label` (additive) names it;
    // focusability/role are only applied while the content actually overflows.
    const regionName = label ? String(label) : "Data table";
    const syncScrollRegion = () => {
      if (root.scrollWidth > root.clientWidth || root.scrollHeight > root.clientHeight) {
        root.tabIndex = 0; root.setAttribute("role", "region"); root.setAttribute("aria-label", regionName);
      } else { root.removeAttribute("tabindex"); root.removeAttribute("role"); root.removeAttribute("aria-label"); }
    };
    const resizeObserver = typeof ResizeObserver === "function" ? new ResizeObserver(syncScrollRegion) : null;
    if (resizeObserver) { resizeObserver.observe(root); resizeObserver.observe(table); }
    const selected = new Set(); let rows = [], seq = 0, destroyed = false;

    async function reload() {
      if (typeof fetchRows !== "function") throw new TypeError("ui.table requires fetch to be a function");
      if (destroyed) return rows;
      const mine = ++seq;
      root.setAttribute("aria-busy", "true");
      let result;
      try { result = await fetchRows(); }
      finally { if (mine === seq) root.removeAttribute("aria-busy"); }
      if (mine !== seq || destroyed) return rows; // a newer reload (or destroy) superseded this one
      rows = Array.isArray(result) ? result : (result?.items || []);
      selected.clear(); tbody.replaceChildren();
      rows.forEach((row, index) => {
        const tr = el("tr", "admin-ui-table__row");
        const key = String(typeof rowKey === "function" ? rowKey(row) : row?.[rowKey]);
        const checkCell = selectable ? document.createElement("td") : null;
        if (checkCell) tr.append(checkCell);
        for (const column of columns) {
          const td = document.createElement("td");
          const value = typeof column.render === "function" ? column.render(row) : row?.[column.key];
          td.append(asNode(value)); tr.append(td);
        }
        if (checkCell) {
          const cb = document.createElement("input"); cb.type = "checkbox";
          const firstCell = tr.children[1];
          const name = (firstCell && firstCell.textContent.trim()) || `row ${index + 1}`;
          cb.setAttribute("aria-label", `Select ${name}`);
          cb.addEventListener("change", () => cb.checked ? selected.add(key) : selected.delete(key));
          checkCell.append(cb);
        }
        if (typeof onRowClick === "function") {
          tr.tabIndex = 0; tr.classList.add("admin-ui-table__row--clickable");
          tr.addEventListener("click", e => { // clicks on controls inside the row keep their own behaviour and do not activate the row
            const hit = e.target instanceof Element ? e.target.closest(INTERACTIVE_SELECTOR) : null;
            if (hit && hit !== tr && tr.contains(hit)) return;
            onRowClick(row);
          });
          tr.addEventListener("keydown", e => { // keyboard parity; only when the row itself has focus
            if (e.target === tr && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); onRowClick(row); }
          });
        }
        tbody.append(tr);
      });
      syncScrollRegion();
      return rows;
    }
    return {
      element: root, reload,
      destroy() { destroyed = true; resizeObserver?.disconnect(); root.remove(); selected.clear(); },
      getSelected() { return [...selected]; },
      getRows() { return rows.slice(); }
    };
  },

  statusBadge(accountStatus) {
    ensureStyles();
    const status = String(accountStatus || "ACTIVE").toUpperCase();
    const badge = el("span", `account-status account-status--${status.toLowerCase().replace(/[^a-z0-9_-]/g, "")}`, status.replaceAll("_", " "));
    return badge;
  },

  empty({ title = "Nothing here yet", action } = {}) {
    ensureStyles();
    const wrap = el("div", "admin-ui-empty");
    wrap.append(el("h3", "admin-ui-empty__title", title));
    if (action) wrap.append(button(action.label || "Try again", "", action.onClick || (() => {})));
    return wrap;
  }
};

export default ui;
