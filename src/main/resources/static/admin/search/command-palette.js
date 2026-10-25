/**
 * Admin command palette: Ctrl+K (Windows/Linux) / Cmd+K (macOS) navigation search.
 *
 * Isolated from the legacy admin.js: the host passes the route registry, the router's navigate/resolve
 * and a few optional hooks; this module owns its own DOM, listeners and cleanup.
 *
 *   initCommandPalette(options)   singleton: a second call tears the first instance down first
 *   createCommandPalette(options) factory without the singleton (used by tests)
 *
 * options: {
 *   routes, sections, aliases     the registry (admin/routes.js). Read again on every open.
 *   navigate(target)              required. Must be the router's navigate() so hash + history stay canonical.
 *   resolve(target)               router.resolve(); validates parameterised destinations ("user 12").
 *   getCurrentPath()              path of the page being shown, for the "Current page" tag.
 *   actions: [{ id, title, description?, keywords?, run }]
 *   trigger                       optional visible button (#adminSearchTrigger); shown while the palette is alive.
 *   isBlocked()                   optional override of the "another modal is open" check.
 *   mac                           force the shortcut flavour (default: detected).
 *   document, window, onError     injectable for tests.
 * }
 *
 * Scope: pages, registry-derived destinations and host actions only. No records are searched and no
 * network request is made.
 */
import { computeResults, buildEntries, highlightRanges } from "./search-model.js";
import { isMacPlatform, isComposingEvent, isShortcutEvent, isEditableTarget, eventTarget } from "./palette-keys.js";

const REGISTRY_KEY = Symbol.for("admin.commandPalette.active");
const MAX_RESULTS = 30;
let instanceCounter = 0;

function el(doc, tag, className, text) {
  const node = doc.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

/** Another modal surface (native <dialog>, legacy #modal, #crModal, aria-modal) owns the keyboard. */
function defaultIsBlocked(doc) {
  const win = doc.defaultView;
  if (doc.querySelector("dialog[open]")) return true;
  const legacy = doc.getElementById("modal");
  if (legacy && win && win.getComputedStyle(legacy).display !== "none" && legacy.childElementCount > 0) return true;
  if (doc.querySelector("#crModal.open")) return true;
  return [...doc.querySelectorAll('[aria-modal="true"]')].some((node) => !node.closest("[data-cmdk-root]") && node.getClientRects().length > 0);
}

export function createCommandPalette(options = {}) {
  const doc = options.document || globalThis.document;
  const win = options.window || doc.defaultView;
  if (!doc || !win) throw new TypeError("createCommandPalette needs a document");
  if (typeof options.navigate !== "function") throw new TypeError("createCommandPalette requires options.navigate");

  const mac = options.mac ?? isMacPlatform(win.navigator);
  const reportError = options.onError || ((error) => console.error("[admin search]", error));
  const isBlocked = options.isBlocked || (() => defaultIsBlocked(doc));
  const trigger = options.trigger || null;
  const id = `cmdk-${++instanceCounter}`;
  const controller = new AbortController();
  const { signal } = controller;

  let entries = [];
  let results = [];
  let activeIndex = -1;
  let isOpen = false;
  let destroyed = false;
  let returnFocusTo = null;
  const triggerWasHidden = trigger ? trigger.hidden : false;
  const triggerKeyshortcuts = trigger ? trigger.getAttribute("aria-keyshortcuts") : null;

  // ── DOM (built once, hidden until opened; every string goes in via textContent) ────────────
  const root = el(doc, "div", "cmdk-root");
  root.hidden = true;
  root.setAttribute("data-cmdk-root", "");
  const backdrop = el(doc, "div", "cmdk-backdrop");
  backdrop.setAttribute("data-cmdk-backdrop", "");
  const panel = el(doc, "div", "cmdk-panel");
  panel.setAttribute("role", "dialog");
  panel.setAttribute("aria-modal", "true");
  panel.setAttribute("aria-labelledby", `${id}-title`);

  const header = el(doc, "div", "cmdk-header");
  const title = el(doc, "h2", "cmdk-title", "Search admin pages");
  title.id = `${id}-title`;
  const closeBtn = el(doc, "button", "cmdk-close", "Close");
  closeBtn.type = "button";
  closeBtn.setAttribute("data-cmdk-close", "");
  const closeHint = el(doc, "kbd", "cmdk-kbd", "Esc");
  closeHint.setAttribute("aria-hidden", "true");
  closeBtn.append(" ", closeHint);
  header.append(title, closeBtn);

  const input = el(doc, "input", "cmdk-input");
  input.type = "text";
  input.setAttribute("role", "combobox");
  input.setAttribute("aria-label", "Search pages and actions");
  input.setAttribute("aria-autocomplete", "list");
  input.setAttribute("aria-haspopup", "listbox");
  input.setAttribute("aria-expanded", "false");
  input.setAttribute("aria-controls", `${id}-list`);
  input.setAttribute("autocomplete", "off");
  input.setAttribute("autocapitalize", "off");
  input.setAttribute("spellcheck", "false");
  input.setAttribute("enterkeyhint", "go");
  input.placeholder = "Search pages and actions…";

  const list = el(doc, "ul", "cmdk-list");
  list.id = `${id}-list`;
  list.setAttribute("role", "listbox");
  list.setAttribute("aria-label", "Pages and actions");

  const empty = el(doc, "div", "cmdk-empty");
  empty.hidden = true;
  const emptyTitle = el(doc, "p", "cmdk-empty-title");
  const emptyHint = el(doc, "p", "cmdk-empty-hint");
  empty.append(emptyTitle, emptyHint);

  const scope = el(doc, "p", "cmdk-scope", "Searches admin pages and actions only. It does not search users, materials, questions or other records.");
  const status = el(doc, "div", "cmdk-sr");
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");

  panel.append(header, input, list, empty, scope, status);
  root.append(backdrop, panel);
  (doc.body || doc.documentElement).append(root);

  // ── trigger button ──────────────────────────────────────────────────────────────────────
  if (trigger) {
    trigger.hidden = false;
    trigger.setAttribute("aria-keyshortcuts", mac ? "Meta+K" : "Control+K");
    trigger.setAttribute("aria-haspopup", "dialog");
    const hint = trigger.querySelector("[data-cmdk-hint]");
    if (hint) hint.textContent = mac ? "⌘K" : "Ctrl K";
    trigger.addEventListener("click", () => open({ source: trigger }), { signal });
  }

  // ── rendering ───────────────────────────────────────────────────────────────────────────
  function appendHighlighted(parent, text, tokens) {
    const ranges = highlightRanges(text, tokens);
    if (ranges.length === 0) { parent.append(doc.createTextNode(text)); return; }
    let cursor = 0;
    for (const [from, to] of ranges) {
      if (from > cursor) parent.append(doc.createTextNode(text.slice(cursor, from)));
      parent.append(el(doc, "mark", "cmdk-mark", text.slice(from, to)));
      cursor = to;
    }
    if (cursor < text.length) parent.append(doc.createTextNode(text.slice(cursor)));
  }

  function optionNode(result, index, currentPath) {
    const { entry, tokens } = result;
    const li = el(doc, "li", "cmdk-option");
    li.id = `${id}-opt-${index}`;
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", "false");
    li.setAttribute("data-index", String(index));
    li.setAttribute("data-kind", entry.kind);
    if (entry.path) li.setAttribute("data-path", entry.path);

    const main = el(doc, "span", "cmdk-opt-main");
    const name = el(doc, "span", "cmdk-opt-title");
    appendHighlighted(name, entry.title, tokens);
    const meta = el(doc, "span", "cmdk-opt-meta");
    meta.append(el(doc, "span", "cmdk-opt-section", entry.section || (entry.kind === "action" ? "Actions" : "")));
    if (entry.hash) {
      const hash = el(doc, "span", "cmdk-opt-hash", entry.hash);
      hash.setAttribute("aria-hidden", "true");
      meta.append(hash);
    }
    main.append(name, meta);
    if (entry.description) main.append(el(doc, "span", "cmdk-opt-desc", entry.description));

    const tags = el(doc, "span", "cmdk-tags");
    const tagTexts = [...(entry.path && entry.path === currentPath ? ["Current page"] : []), ...entry.tags];
    for (const text of tagTexts) tags.append(el(doc, "span", "cmdk-tag", text));
    li.append(main, tags);
    return li;
  }

  function setActive(index, { scroll = true } = {}) {
    const previous = list.children[activeIndex];
    if (previous) previous.setAttribute("aria-selected", "false");
    activeIndex = index;
    const next = list.children[index];
    if (next) {
      next.setAttribute("aria-selected", "true");
      input.setAttribute("aria-activedescendant", next.id);
      if (scroll && typeof next.scrollIntoView === "function") next.scrollIntoView({ block: "nearest" });
    } else {
      input.removeAttribute("aria-activedescendant");
    }
  }

  function render() {
    const query = input.value;
    const currentPath = typeof options.getCurrentPath === "function" ? options.getCurrentPath() : null;
    results = computeResults(entries, query, { routes: options.routes, sections: options.sections, resolve: options.resolve, limit: MAX_RESULTS });
    list.replaceChildren(...results.map((result, i) => optionNode(result, i, currentPath)));
    activeIndex = -1;
    const has = results.length > 0;
    input.setAttribute("aria-expanded", String(has));
    list.hidden = !has;
    empty.hidden = has;
    if (has) {
      setActive(0, { scroll: false });
      list.scrollTop = 0;
      status.textContent = query.trim()
        ? `${results.length} ${results.length === 1 ? "result" : "results"}. Use arrow keys to choose, Enter to open.`
        : `${results.length} pages and actions. Type to filter.`;
    } else {
      setActive(-1);
      if (entries.length === 0) {
        emptyTitle.textContent = "No pages are available to search.";
        emptyHint.textContent = "";
      } else {
        emptyTitle.textContent = `No pages or actions match “${query.trim()}”.`;
        emptyHint.textContent = "Try a page name, or clear the search to list every page.";
      }
      status.textContent = entries.length === 0 ? emptyTitle.textContent : `No results for ${query.trim()}.`;
    }
  }

  // ── open / close / activate ─────────────────────────────────────────────────────────────
  function focusTarget(node) {
    if (node && node.isConnected && !node.hidden && !node.disabled && typeof node.focus === "function") {
      node.focus({ preventScroll: true });
      return doc.activeElement === node;
    }
    return false;
  }

  function open({ source } = {}) {
    if (destroyed || isOpen) return false;
    let blocked = false;
    try { blocked = !!isBlocked(); } catch (error) { reportError(error); }
    if (blocked) return false;
    const active = doc.activeElement;
    const usable = active && active !== doc.body && active !== doc.documentElement && !root.contains(active);   // never our own (stale) input
    returnFocusTo = source || (usable ? active : trigger);
    entries = buildEntries({ routes: options.routes, sections: options.sections, aliases: options.aliases, actions: options.actions });
    isOpen = true;
    root.hidden = false;
    input.value = "";
    render();
    input.focus({ preventScroll: true });
    return true;
  }

  function close({ restoreFocus = true } = {}) {
    if (!isOpen) return false;
    isOpen = false;
    if (root.contains(doc.activeElement)) doc.activeElement.blur();   // no stale focus on a hidden control (browsers fix it up late)
    root.hidden = true;
    input.value = "";
    const target = returnFocusTo;
    returnFocusTo = null;
    if (restoreFocus) {
      if (!focusTarget(target)) {
        if (!focusTarget(trigger)) focusTarget(doc.getElementById("adminPageTitle"));
      }
    }
    return true;
  }

  function activate(index) {
    const result = results[index];
    if (!result) return;
    const entry = result.entry;
    close({ restoreFocus: true });      // focus is never lost, even when the target is the page already shown
    try {
      const out = entry.kind === "action" ? entry.run() : options.navigate(entry.path);
      if (out && typeof out.then === "function") out.catch(reportError);
    } catch (error) { reportError(error); }
  }

  // ── keyboard ────────────────────────────────────────────────────────────────────────────
  function focusables() {
    return [...panel.querySelectorAll("button, input, [href], [tabindex]:not([tabindex='-1'])")].filter((node) => !node.disabled && node.getClientRects().length > 0);
  }

  // Capture phase: while open, the palette owns the keyboard before the legacy Escape handlers
  // (nav drawer, review modal) on document can see the event.
  function onKeyCapture(event) {
    if (!isOpen) return;
    const fromPanel = panel.contains(eventTarget(event));
    if (isShortcutEvent(event, { mac })) {           // same shortcut again toggles it closed
      event.preventDefault(); event.stopPropagation();
      if (!event.repeat) close();
      return;
    }
    if (event.key === "Escape") {
      if (isComposingEvent(event)) return;           // Escape cancels the IME candidate first
      event.preventDefault(); event.stopPropagation();
      close();
      return;
    }
    if (event.key === "Tab") {
      const nodes = focusables();
      if (nodes.length === 0) { event.preventDefault(); return; }
      const first = nodes[0];
      const last = nodes[nodes.length - 1];
      const current = doc.activeElement;
      if (!fromPanel || !nodes.includes(current)) { event.preventDefault(); first.focus(); }
      else if (event.shiftKey && current === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && current === last) { event.preventDefault(); first.focus(); }
      return;
    }
    if (eventTarget(event) !== input || isComposingEvent(event)) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (results.length === 0) return;
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive((activeIndex + step + results.length) % results.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      if (activeIndex >= 0) activate(activeIndex);
    }
  }

  // Bubble phase: an embedded editor or component that already handled/cancelled the key wins.
  function onShortcut(event) {
    if (isOpen || event.defaultPrevented || event.repeat) return;
    if (!isShortcutEvent(event, { mac }) || isComposingEvent(event)) return;
    if (isEditableTarget(eventTarget(event))) return;
    if (open()) event.preventDefault();
  }

  doc.addEventListener("keydown", onKeyCapture, { capture: true, signal });
  doc.addEventListener("keydown", onShortcut, { signal });
  doc.addEventListener("focusin", (event) => {
    if (isOpen && !panel.contains(eventTarget(event))) input.focus({ preventScroll: true });
  }, { signal });
  win.addEventListener("hashchange", () => close(), { signal });        // Back/Forward (or a link) while open
  win.addEventListener("pagehide", (event) => { close({ restoreFocus: false }); if (!event.persisted) destroy(); }, { signal });

  input.addEventListener("input", render, { signal });
  backdrop.addEventListener("click", () => close(), { signal });
  closeBtn.addEventListener("click", () => close(), { signal });
  list.addEventListener("mousedown", (event) => event.preventDefault(), { signal });   // keep focus in the input
  list.addEventListener("mousemove", (event) => {
    const option = event.target.closest && event.target.closest("[role=option]");
    if (option) { const i = Number(option.dataset.index); if (i !== activeIndex) setActive(i, { scroll: false }); }
  }, { signal });
  list.addEventListener("click", (event) => {
    const option = event.target.closest && event.target.closest("[role=option]");
    if (option) activate(Number(option.dataset.index));
  }, { signal });

  function destroy() {
    if (destroyed) return;
    close();
    destroyed = true;
    controller.abort();
    root.remove();
    if (trigger) {
      trigger.hidden = triggerWasHidden;
      if (triggerKeyshortcuts === null) trigger.removeAttribute("aria-keyshortcuts"); else trigger.setAttribute("aria-keyshortcuts", triggerKeyshortcuts);
    }
    if (globalThis[REGISTRY_KEY] === api) globalThis[REGISTRY_KEY] = null;
  }

  const api = {
    open, close, destroy,
    toggle() { return isOpen ? close() : open(); },
    isOpen: () => isOpen,
    isDestroyed: () => destroyed,
    root, input, panel,
    shortcutLabel: mac ? "⌘K" : "Ctrl+K"
  };
  return api;
}

/** Singleton entry point for the admin shell: repeated initialization replaces the previous instance. */
export function initCommandPalette(options = {}) {
  const previous = globalThis[REGISTRY_KEY];
  if (previous && !previous.isDestroyed()) previous.destroy();
  const palette = createCommandPalette(options);
  globalThis[REGISTRY_KEY] = palette;
  return palette;
}

export default { initCommandPalette, createCommandPalette };
