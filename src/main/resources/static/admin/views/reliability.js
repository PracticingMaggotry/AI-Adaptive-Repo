/**
 * System > Reliability  (route: /system/reliability)
 *
 * Honest, export-only page. The only verified reliability feature is the server-side CSV export
 *   GET /api/admin/export/reliability.csv
 * (system-level API, limit and yield events, no student identifiers). There is NO reliability
 * dashboard, chart, uptime figure or live metric, and this view does not render or imply one.
 * It makes no API requests; the link is a plain same-origin download using the admin session.
 *
 * The legacy Reports screen (Study controls, report, other exports) is deliberately not shown here.
 * Contract: default export { mount(el), unmount() }. No listeners are registered.
 */
import { h } from "./view-dom.js";

export const RELIABILITY_CSV = "/api/admin/export/reliability.csv";
let root = null;

export default {
  mount(el) {
    if (!el) throw new TypeError("Reliability view requires a mount element");
    if (root) root.remove();
    root = h("section", { class: "admin-card", "data-view": "reliability" },
      h("div", { class: "shell-placeholder-status" }, "Export only"),
      h("h2", { class: "admin-card-title" }, "Reliability"),
      h("p", { class: "admin-card-hint" },
        "Reliability is export-only for now. The Reliability CSV below is the only reliability feature: it lists system-level API, limit and yield events, with no student identifiers."),
      h("p", { class: "admin-card-hint" },
        "There is no reliability dashboard, chart or live status on this page, and nothing here is calculated from the data. Open the CSV in a spreadsheet to analyse it."),
      h("p", {}, h("a", { class: "print-btn", style: "background:#059669;text-decoration:none;", href: RELIABILITY_CSV }, "⬇ Reliability CSV")),
      h("p", { class: "admin-card-hint" }, "The other study datasets are under ", h("a", { href: "#/study/data-exports" }, "Study → Data exports"), "."));
    el.replaceChildren(root);
  },
  unmount() { if (root) root.remove(); root = null; }
};
