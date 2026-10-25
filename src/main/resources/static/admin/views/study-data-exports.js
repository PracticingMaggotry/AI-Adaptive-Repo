/**
 * Study > Data exports  (route: /study/data-exports)
 *
 * Extracted from the export toolbar of the legacy Admin Reports screen (admin/legacy/reports.js,
 * renderReports). The exports are plain same-origin downloads served by ResearchExportController; this
 * view only links to them. Nothing about the files changes: same URLs, same query strings (the review
 * sample keeps the legacy perHandout=10), same labels, same CSV/TXT content and column names, same
 * server-side admin-session check.
 *
 *   GET /api/admin/export/attempts.csv
 *   GET /api/admin/export/attempt_questions.csv
 *   GET /api/admin/export/question_exposures.csv
 *   GET /api/admin/export/reliability.csv
 *   GET /api/admin/export/sus.csv
 *   GET /api/admin/export/review_questions.csv?perHandout=10
 *   GET /api/admin/export/review_sources.txt?perHandout=10
 *
 * Not moved here on purpose: the identity-key CSV stays on Study > Participants (the key is meant to be
 * stored apart from the datasets), and the print / copy-report actions stay with the Platform report.
 *
 * Enrolment context: the only request this view makes is GET /api/admin/participants, used to show how many
 * participants the exports will cover. It is informational and non-blocking: if it fails, an inline error
 * with Retry is shown and every download link keeps working.
 *
 * Contract: default export { mount(el, ctx), unmount(ctx) }. The only listener is the Retry button of the
 * enrolment check; the download links are plain anchors, so nothing can be duplicated on re-mount.
 * The root keeps the legacy id #printExclude so the shell's focus target and print stylesheet still apply.
 */
import {
  h, createLifecycle, apiCall, errorMessage, isAbortError, setStatus, loadingBlock, errorBlock, requireUi
} from "./study-common.js";

const BASE = "/api/admin/export/";
const PARTICIPANTS = "/api/admin/participants";
const GREEN = "background:#059669;text-decoration:none;";
const PURPLE = "background:#7c3aed;text-decoration:none;";

/** Single source of truth for the links (label, href and colour are the legacy ones). */
export const EXPORTS = Object.freeze({
  datasets: Object.freeze([
    { label: "⬇ Attempts CSV", href: BASE + "attempts.csv", style: GREEN, note: "One row per submitted quiz." },
    { label: "⬇ Per-question CSV", href: BASE + "attempt_questions.csv", style: GREEN, note: "One row per question answered in each attempt." },
    { label: "⬇ Exposures CSV", href: BASE + "question_exposures.csv", style: GREEN, note: "Every question served (append-only log)." },
    { label: "⬇ SUS CSV", href: BASE + "sus.csv", style: GREEN, note: "System Usability Scale responses, one row per participant code." }
  ]),
  system: Object.freeze([
    { label: "⬇ Reliability CSV", href: BASE + "reliability.csv", style: GREEN, note: "System-level API, limit and yield events (no student identifiers)." }
  ]),
  review: Object.freeze([
    { label: "⬇ Review sample CSV", href: BASE + "review_questions.csv?perHandout=10", style: PURPLE, note: "Question-quality review sheet: a reproducible sample of up to 10 questions per handout, with blank rater columns." },
    { label: "⬇ Review sources TXT", href: BASE + "review_sources.txt?perHandout=10", style: PURPLE, note: "The handout text each sampled question is judged against." }
  ])
});

let active = null;                        // { life, root }

function teardown() {
  if (!active) return;
  const { life, root, onDispose } = active;
  active = null;
  life.dispose();
  if (onDispose) onDispose();
  root.remove();
}

function exportRow(item) {
  return h("div", { style: "display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:10px;" },
    h("a", { class: "print-btn", style: item.style, href: item.href }, item.label),
    h("span", { style: "font-size:.8rem;color:#6b7280;flex:1 1 240px;" }, item.note));
}

function exportCard(title, hint, items) {
  return h("section", { class: "admin-card", style: "margin-bottom:14px;" },
    h("div", { class: "admin-card-title" }, title),
    hint ? h("div", { class: "admin-card-hint" }, hint) : null,
    items.map(exportRow));
}

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Study data exports view requires a mount element");
    teardown();
    const ui = requireUi(ctx, "Study data exports");
    const life = createLifecycle();

    const coverage = h("div", { id: "exportCoverage", style: "font-size:.85rem;color:#374151;", "aria-live": "polite" });
    const coverageCard = h("section", { class: "admin-card", style: "margin-bottom:14px;" },
      h("div", { class: "admin-card-title" }, "👥 Who the exports cover"),
      h("div", { class: "admin-card-hint" }, "Participant exports include only enrolled students, identified by code."),
      coverage);

    const root = h("div", { id: "printExclude" },
      coverageCard,
      exportCard("📊 Study datasets", "Downloads start immediately and use your admin session. If one does not start, reload the page and try again.", EXPORTS.datasets),
      exportCard("🧪 Question-quality review sample", "A fixed-seed sample, so the same data always gives the same review set.", EXPORTS.review),
      exportCard("⚙️ System", null, EXPORTS.system));
    el.replaceChildren(root);

    const state = { seq: 0, abort: null };
    active = { life, root, onDispose() { if (state.abort) state.abort.abort(); } };

    async function loadCoverage() {
      const seq = ++state.seq;
      if (state.abort) state.abort.abort();
      const controller = new AbortController();
      state.abort = controller;
      coverage.replaceChildren(loadingBlock("Checking enrolment…"));
      try {
        const response = await apiCall(ctx, PARTICIPANTS, { signal: controller.signal });
        if (!life.alive || seq !== state.seq) return;
        if (!response || !Array.isArray(response.participants)) throw new Error("Unexpected response from the server.");
        const rows = response.participants;
        if (!rows.length) {
          coverage.replaceChildren(ui.empty({ title: "No participants are enrolled yet, so there is no participant data to export." }),
            h("div", { style: "text-align:center;" }, h("a", { href: "#/study/participants" }, "Go to Study → Participants")));
          return;
        }
        const consented = rows.filter(p => p.consented).length;
        const line = h("div", { role: "status" });
        setStatus(line, "info", `${rows.length} participant(s) enrolled · ${consented} consented to the current consent version.`);
        line.style.marginTop = "0";
        coverage.replaceChildren(line);
      } catch (error) {
        if (isAbortError(error) || !life.alive || seq !== state.seq) return;
        coverage.replaceChildren(errorBlock(life, "Could not check enrolment: " + errorMessage(error) + " The downloads below still work.", loadCoverage));
      }
    }

    loadCoverage();
  },

  unmount() {
    teardown();
  }
};
