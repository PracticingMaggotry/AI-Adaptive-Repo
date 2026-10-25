/**
 * Transitional adapter for the existing admin renderer.
 * The renderer is intentionally still hosted by admin.js so this contract-freeze
 * migration does not duplicate or silently change legacy behavior. A stream can
 * replace this adapter's implementation with a self-contained view while keeping
 * the public mount/unmount contract stable.
 */
const legacyRenderer = "renderReports";

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Admin Reports view requires a mount element");
    const target = document.getElementById("adminContent");
    if (!target || target !== el) {
      throw new Error("Transitional admin views must mount into #adminContent until extracted from admin.js");
    }
    const render = window[legacyRenderer];
    if (typeof render !== "function") throw new Error(`Legacy renderer ${legacyRenderer} is not available`);
    // ctx is accepted now so later extraction does not require another contract change.
    void ctx;
    return await render();
  },
  unmount() {
    // The legacy renderers do not currently expose per-view cleanup hooks.
    // New implementations must clean up listeners, observers, timers, and object URLs here.
  }
};
