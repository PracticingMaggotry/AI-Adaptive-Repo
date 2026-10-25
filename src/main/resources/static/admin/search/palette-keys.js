/**
 * Keyboard helpers for the admin command palette. Duck-typed (no DOM globals needed), so they can be
 * unit-tested in Node with plain objects.
 */

/** True for macOS/iOS style platforms, where the shortcut is Cmd+K instead of Ctrl+K. */
export function isMacPlatform(nav = globalThis.navigator) {
  if (!nav) return false;
  const platform = (nav.userAgentData && nav.userAgentData.platform) || nav.platform || "";
  return /^(mac|iphone|ipad|ipod)/i.test(platform) || (!platform && /Mac OS X|iPhone|iPad/i.test(nav.userAgent || ""));
}

/** IME composition (or the legacy 229 keyCode some browsers report around compositionend). */
export function isComposingEvent(event) {
  return !!(event && (event.isComposing || event.keyCode === 229));
}

/**
 * Ctrl+K (Windows/Linux) or Cmd+K (macOS) with no other modifier. Only the platform's own modifier
 * counts: Ctrl+K on macOS is "kill line" in text fields and Meta+K means nothing on Windows.
 * Latin keyboards use `key`; for non-Latin layouts (key is not an ASCII letter) the physical key code
 * is used so Ctrl+K still works, without hijacking e.g. Ctrl+T on Dvorak.
 */
export function isShortcutEvent(event, { mac = false } = {}) {
  if (!event || event.altKey || event.shiftKey) return false;
  const modifier = mac ? (event.metaKey && !event.ctrlKey) : (event.ctrlKey && !event.metaKey);
  if (!modifier) return false;
  const key = typeof event.key === "string" ? event.key : "";
  if (/^[A-Za-z]$/.test(key)) return key.toLowerCase() === "k";
  return event.code === "KeyK";
}

const NON_TEXT_INPUT = new Set(["button", "checkbox", "radio", "submit", "reset", "image", "file", "range", "color", "hidden"]);
const EDITOR_SELECTOR = [
  '[contenteditable]:not([contenteditable="false"])',
  '[role="textbox"]',
  ".monaco-editor", ".cm-editor", ".CodeMirror", ".ProseMirror", ".ql-editor", ".tox-edit-area"
].join(",");

/** Text-entry controls and embedded editors, where Ctrl/Cmd+K belongs to the control. */
export function isEditableTarget(el) {
  if (!el || el.nodeType !== 1) return false;
  const tag = String(el.tagName || "").toUpperCase();
  if (tag === "TEXTAREA") return !el.disabled && !el.readOnly;
  if (tag === "INPUT") return !NON_TEXT_INPUT.has(String(el.type || "text").toLowerCase()) && !el.disabled && !el.readOnly;
  if (el.isContentEditable) return true;
  return typeof el.closest === "function" && !!el.closest(EDITOR_SELECTOR);
}

/** Real target of a key event, looking through open shadow roots. */
export function eventTarget(event) {
  const path = typeof event.composedPath === "function" ? event.composedPath() : null;
  return (path && path[0]) || event.target;
}
