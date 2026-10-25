/**
 * Tiny DOM builder shared by the Platform report and Reliability views.
 * Import-safe in Node (touches `document` only when called). Text is always inserted as text nodes
 * (append(string)); no HTML strings are ever parsed.
 */
export function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "style") node.style.cssText = value;
    else if (key === "disabled" || key === "hidden") node[key] = Boolean(value);
    else if (["id", "href", "type", "title"].includes(key)) node[key] = value;
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : String(child));
  }
  return node;
}

/** Registers listeners so unmount() can remove them all at once (no duplicates after re-mount). */
export function createLifecycle() {
  const controller = new AbortController();
  let alive = true;
  return {
    get alive() { return alive; },
    get signal() { return controller.signal; },
    on(target, type, handler) { if (target) target.addEventListener(type, handler, { signal: controller.signal }); },
    dispose() { if (!alive) return; alive = false; controller.abort(); }
  };
}
