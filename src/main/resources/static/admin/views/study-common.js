/**
 * Small helpers shared by the three Study views (participants, assigned handouts, data exports).
 *
 * Deliberately framework-free and import-safe in Node: nothing here touches window/document
 * at import time. Study-specific on purpose - do not grow this into a general UI library;
 * shared primitives belong in ../ui.js.
 *
 * Design rules these helpers support:
 *  - Every listener is registered through a lifecycle, so unmount() removes all of them at once
 *    (no duplicate handlers after navigating away and back).
 *  - Read requests (GET) are abortable and abort on unmount. Write requests (POST/DELETE) are
 *    never aborted: once sent they must finish, and the view only skips touching the DOM.
 *  - All text is inserted with textContent / append(string); nothing here uses innerHTML.
 */

/** Tiny DOM builder. Children may be nodes, strings, numbers, arrays, or null/false (skipped). */
export function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key === "style") node.style.cssText = value;
    else if (key === "dataset") Object.assign(node.dataset, value);
    else if (key === "for") node.setAttribute("for", value);
    else if (key === "disabled" || key === "hidden" || key === "checked" || key === "multiple") node[key] = Boolean(value);
    else if (["value", "type", "href", "download", "rows", "min", "max", "step", "accept", "placeholder", "id", "name", "rel", "target", "autocomplete"].includes(key)) node[key] = value;
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : String(child));
  }
  return node;
}

/**
 * Lifecycle for one mount(). dispose() is idempotent: it removes every listener registered
 * through on(), aborts the shared signal, and runs registered cleanups (last registered first).
 */
export function createLifecycle() {
  const controller = new AbortController();
  const cleanups = [];
  let alive = true;
  return {
    get alive() { return alive; },
    get signal() { return controller.signal; },
    on(target, type, handler, options) {
      if (!target) return;
      target.addEventListener(type, handler, { ...(options || {}), signal: controller.signal });
    },
    onCleanup(fn) { cleanups.push(fn); },
    dispose() {
      if (!alive) return;
      alive = false;
      controller.abort();
      while (cleanups.length) {
        try { cleanups.pop()(); } catch (error) { console.warn("[study view] cleanup failed", error); }
      }
    }
  };
}

/** Calls the admin API through ctx.api (the shell wires this to the legacy apiFetch). */
export function apiCall(ctx, path, options = {}) {
  const api = ctx && ctx.api;
  if (api && typeof api.request === "function") return api.request(path, options);
  if (api && typeof api.get === "function") return api.get(path, options);
  if (typeof globalThis.apiFetch === "function") return globalThis.apiFetch(path, options);
  return Promise.reject(new Error("The admin API helper is not available."));
}

export function isAbortError(error) {
  return Boolean(error) && (error.name === "AbortError" || error.code === 20);
}

/** Message for display. Never returns an empty string (apiFetch yields "" for empty error bodies). */
export function errorMessage(error, fallback = "Request failed.") {
  let text = error && error.message ? String(error.message).trim() : "";
  // apiFetch reports "Request failed" when an error response has no message (the 403 bodies are {success:false}).
  if (text === "Request failed") text = "";
  if (text) return text.length > 300 ? text.slice(0, 300) + "…" : text;
  if (error && (error.status === 401 || error.status === 403)) return "Admin access is required (HTTP " + error.status + ").";
  return fallback;
}

const STATUS_STYLES = {
  success: "background:#ecfdf5;color:#065f46;border:1px solid #a7f3d0;",
  error: "background:#fef2f2;color:#991b1b;border:1px solid #fecaca;",
  warning: "background:#fffbeb;color:#92400e;border:1px solid #fde68a;",
  info: "background:#eff6ff;color:#1e3a8a;border:1px solid #bfdbfe;"
};

/**
 * Inline status line. The node should carry role="status" (or alert for errors) so that changes are
 * announced. An empty message hides it.
 */
export function setStatus(node, type, message) {
  if (!node) return;
  node.replaceChildren();
  if (!message) { node.hidden = true; return; }
  node.hidden = false;
  node.dataset.statusType = type;
  node.style.cssText = "margin-top:10px;padding:8px 12px;border-radius:8px;font-size:.82rem;line-height:1.5;overflow-wrap:anywhere;" + (STATUS_STYLES[type] || STATUS_STYLES.info);
  node.setAttribute("role", type === "error" ? "alert" : "status");
  node.append(String(message));
}

export function loadingBlock(text) {
  return h("div", { class: "empty-state", role: "status", "aria-live": "polite" }, text);
}

/** Error state with a Retry button wired through the lifecycle. */
export function errorBlock(life, message, onRetry) {
  const retry = h("button", { type: "button", class: "action-btn btn-view", style: "margin-top:10px;" }, "Retry");
  life.on(retry, "click", onRetry);
  return h("div", { class: "empty-state", role: "alert" }, h("div", {}, message), onRetry ? retry : null);
}

/**
 * Triggers a browser download of generated text. Works after the view was unmounted (needs no DOM
 * of the view). The object URL is released after a short delay; the legacy code never released it.
 */
export function downloadTextFile(filename, text, mime = "text/csv") {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.rel = "noopener";
  link.style.display = "none";
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

/**
 * The legacy Activity Log screen renders from a cached list that only refreshActivityLog() updates.
 * Study actions write audit entries on the server, so refresh the cache when the shell provides the
 * helper. Best effort: absent in admin-dev.html or tests, and failures never affect the study action.
 */
export async function refreshActivityLogQuietly() {
  const refresh = globalThis.refreshActivityLog;
  if (typeof refresh !== "function") return;
  try { await refresh(); } catch (error) { console.warn("[study view] could not refresh the activity log cache", error); }
}

export function requireUi(ctx, viewName) {
  const ui = ctx && ctx.ui;
  if (!ui || typeof ui.confirm !== "function" || typeof ui.toast !== "function" || typeof ui.empty !== "function" || typeof ui.table !== "function") {
    throw new Error(viewName + " needs ctx.ui (admin/ui.js) with confirm, toast, empty and table.");
  }
  return ui;
}
