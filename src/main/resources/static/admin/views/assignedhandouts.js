/**
 * Study > Assigned handouts  (route: /study/assigned-handouts)
 *
 * Self-contained replacement for the transitional adapter that used to delegate to the global
 * renderAssignedHandouts() in admin/legacy/assignedhandouts.js. The module path is unchanged, so the
 * existing route record (`load: () => import("./views/assignedhandouts.js")`) keeps working with no
 * route-registry edit. Behavior, endpoints and request shapes are the legacy ones:
 *
 *   GET    /api/admin/participants                 participant picker: { participants: [{ code, email }] }
 *   GET    /api/admin/assigned-handouts            list: { handouts: [{ id, participantCode, studentEmail,
 *                                                   topic, filename, sizeBytes, assignedAt, status }] }
 *   POST   /api/admin/assigned-handouts            multipart FormData: topic, file, emails (repeated)
 *                                                   -> { message, skipped? }
 *   POST   /api/admin/assigned-handouts/{id}/reset status PROCESSED -> pending again
 *   DELETE /api/admin/assigned-handouts/{id}       removes the assignment
 *
 * Contract: default export { mount(el, ctx), unmount(ctx) }. The legacy admin.js globals are not needed
 * (apiFetch comes from ctx.api, dialogs from ctx.ui). The only optional global is refreshActivityLog(),
 * which the legacy screen also called so the Activity Log tab shows the new entries.
 *
 * Lifecycle: every listener is registered through one lifecycle and removed on unmount; GET requests
 * abort on unmount; an upload that is already in flight is NOT aborted (the server completes it) and the
 * outcome is reported with a toast if the admin has left the page.
 */
import {
  h, createLifecycle, apiCall, errorMessage, isAbortError, setStatus, loadingBlock, errorBlock,
  refreshActivityLogQuietly, requireUi
} from "./study-common.js";

const HANDOUTS = "/api/admin/assigned-handouts";
const PARTICIPANTS = "/api/admin/participants";

let active = null;                        // { life, root }

function teardown() {
  if (!active) return;
  const { life, root, onDispose } = active;
  active = null;
  life.dispose();
  if (onDispose) onDispose();
  root.remove();
}

const inputStyle = "padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;box-sizing:border-box;";

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Assigned handouts view requires a mount element");
    teardown();
    const ui = requireUi(ctx, "Assigned handouts");
    const life = createLifecycle();

    // ── assign card ──────────────────────────────────────────────────────────────────────────
    const topicInput = h("input", { id: "ahTopic", type: "text", placeholder: "e.g. Data Structures", style: "width:240px;" });
    const fileInput = h("input", { id: "ahFile", type: "file", accept: ".txt,.pdf,.doc,.docx,.csv" });
    const pickerBox = h("div", { id: "ahParticipants", style: "font-size:.85rem;color:#6b7280;" });
    const assignButton = h("button", { type: "button", class: "btn primary", id: "ahAssignBtn" }, "📤 Upload & Assign");
    const assignStatus = h("div", { id: "ahStatus", hidden: true });

    const assignCard = h("section", { class: "admin-card" },
      h("div", { class: "admin-card-title" }, "📎 Assign a Handout to Study Participants"),
      h("div", { class: "admin-card-hint" }, "Enrolled participants can't upload their own material. Upload it here instead. When you assign it, the handout is sent to Anthropic once to build one baseline quiz, which is copied to every participant you pick (usually 1-2 minutes). Assign each handout to everyone who needs it in a single go: assigning it again later builds a new, different quiz."),
      h("div", { class: "ip-form", style: "margin-bottom:14px;" },
        h("div", { class: "ip-form-field" }, h("label", { for: "ahTopic" }, "Topic name *"), topicInput),
        h("div", { class: "ip-form-field" }, h("label", { for: "ahFile" }, "Handout file * (PDF, DOCX, TXT)"), fileInput)),
      h("div", { style: "margin-bottom:14px;" },
        h("div", { style: "font-size:.75rem;font-weight:700;color:#374151;margin-bottom:6px;" }, "Assign to *"),
        pickerBox),
      assignButton, assignStatus);

    // ── list card ────────────────────────────────────────────────────────────────────────────
    const summary = h("div", { style: "font-size:.82rem;color:#374151;margin-bottom:8px;", "aria-live": "polite" });
    const refreshButton = h("button", { type: "button", class: "action-btn btn-view" }, "↻ Refresh");
    const listBox = h("div", { id: "ahList" });
    const listCard = h("section", { class: "admin-card" },
      h("div", { class: "admin-card-title" }, "📋 Assigned Handouts"),
      h("div", { class: "admin-card-hint" }, "Preparing = the baseline quiz is being built. Processed = the participant has their quiz. Pending = preparation failed or was skipped, so the participant sends it themselves from the quiz page. \"Reset\" puts a row back to Pending (for example after they delete the topic, or if a Preparing row is stuck)."),
      h("div", { style: "display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;" }, summary, refreshButton),
      listBox);

    const root = h("div", {}, assignCard, listCard);
    el.replaceChildren(root);

    const state = { listSeq: 0, listAbort: null, pickerAbort: null, table: null, busy: new Set(), uploading: false };
    active = {
      life, root,
      onDispose() {
        if (state.listAbort) state.listAbort.abort();
        if (state.pickerAbort) state.pickerAbort.abort();
        if (state.table) state.table.destroy();
      }
    };

    // ── participant picker ───────────────────────────────────────────────────────────────────
    const pickBoxes = () => [...root.querySelectorAll(".ah-pick")];
    const pickAll = () => root.querySelector("#ahPickAll");

    function syncPickAll() {
      const all = pickAll(); if (!all) return;
      const boxes = pickBoxes();
      const checked = boxes.filter(b => b.checked).length;
      all.checked = boxes.length > 0 && checked === boxes.length;
      all.indeterminate = checked > 0 && checked < boxes.length;
    }

    life.on(pickerBox, "change", (event) => {
      const target = event.target;
      if (!(target instanceof HTMLInputElement)) return;
      if (target.id === "ahPickAll") pickBoxes().forEach(box => { box.checked = target.checked; });
      else syncPickAll();
    });

    async function loadPicker() {
      if (state.pickerAbort) state.pickerAbort.abort();
      const controller = new AbortController();
      state.pickerAbort = controller;
      pickerBox.replaceChildren(loadingBlock("Loading participants…"));
      try {
        const response = await apiCall(ctx, PARTICIPANTS, { signal: controller.signal });
        if (!life.alive || controller !== state.pickerAbort) return;
        if (!response || !Array.isArray(response.participants)) throw new Error("Unexpected response from the server.");
        const list = response.participants;
        if (!list.length) {
          pickerBox.replaceChildren(h("div", { class: "empty-state", style: "padding:10px;text-align:left;" },
            "No participants enrolled yet. Enrol them under ", h("a", { href: "#/study/participants" }, "Study → Participants"), " first."));
          return;
        }
        pickerBox.replaceChildren(
          h("label", { style: "display:flex;gap:8px;align-items:center;font-weight:700;margin-bottom:8px;color:#374151;" },
            h("input", { type: "checkbox", id: "ahPickAll" }), ` Select all (${list.length})`),
          h("div", { style: "display:flex;flex-wrap:wrap;gap:6px 20px;max-height:180px;overflow-y:auto;" },
            list.map(p => h("label", { style: "display:flex;gap:6px;align-items:center;font-size:.82rem;color:#374151;" },
              h("input", { type: "checkbox", class: "ah-pick", value: p.email }),
              String(p.code ?? ""),
              h("span", { style: "color:#9ca3af;font-family:monospace;font-size:.72rem;" }, String(p.email ?? ""))))));
      } catch (error) {
        if (isAbortError(error) || !life.alive || controller !== state.pickerAbort) return;
        pickerBox.replaceChildren(errorBlock(life, "Could not load participants: " + errorMessage(error, "Unknown error"), loadPicker));
      }
    }

    // ── assignment list ──────────────────────────────────────────────────────────────────────
    async function loadList() {
      const seq = ++state.listSeq;
      if (state.listAbort) state.listAbort.abort();
      const controller = new AbortController();
      state.listAbort = controller;
      if (state.table) { state.table.destroy(); state.table = null; }
      summary.textContent = "";
      listBox.replaceChildren(loadingBlock("Loading…"));
      try {
        const response = await apiCall(ctx, HANDOUTS, { signal: controller.signal });
        if (!life.alive || seq !== state.listSeq) return;
        if (!response || !Array.isArray(response.handouts)) throw new Error("Unexpected response from the server.");
        const rows = response.handouts;
        if (!rows.length) {
          summary.textContent = "0 assigned";
          listBox.replaceChildren(ui.empty({ title: "Nothing assigned yet." }));
          return;
        }
        const processed = rows.filter(r => r.status === "PROCESSED").length;
        const preparing = rows.filter(r => r.status === "PREPARING").length;
        summary.textContent = `${rows.length} assigned · ${preparing} preparing · ${rows.length - processed - preparing} pending · ${processed} processed`;
        const table = ui.table({
          columns: [
            { key: "n", label: "#", render: row => String(rows.indexOf(row) + 1) },
            { key: "participant", label: "Participant", render: row => h("strong", {}, String(row.participantCode || row.studentEmail || "")) },
            { key: "topic", label: "Topic" },
            { key: "file", label: "File", render: row => h("span", { style: "font-size:.78rem;" },
                String(row.filename || "—"),
                row.sizeBytes ? h("span", { style: "color:#9ca3af;" }, ` (${Math.max(1, Math.round(row.sizeBytes / 1024))} KB)`) : null) },
            { key: "assignedAt", label: "Assigned", render: row => h("span", { style: "font-size:.75rem;color:#6b7280;" }, row.assignedAt ? new Date(row.assignedAt).toLocaleString() : "—") },
            { key: "status", label: "Status", render: row => row.status === "PROCESSED"
                ? h("span", { class: "badge-active" }, "Processed")
                : row.status === "PREPARING"
                  ? h("span", { class: "badge-flagged" }, "⏳ Preparing…")
                  : h("span", { class: "badge-flagged" }, "Pending") },
            { key: "actions", label: "Actions", render: row => h("span", { style: "white-space:nowrap;" },
                (row.status === "PROCESSED" || row.status === "PREPARING") ? h("button", { type: "button", class: "action-btn btn-view", dataset: { ah: "reset", id: row.id } }, "↺ Reset") : null,
                h("button", { type: "button", class: "action-btn btn-delete", dataset: { ah: "delete", id: row.id, topic: row.topic ?? "" } }, "🗑 Delete")) }
          ],
          fetch: async () => rows,
          rowKey: "id",
          label: "Assigned handouts"
        });
        await table.reload();
        if (!life.alive || seq !== state.listSeq) { table.destroy(); return; }
        state.table = table;
        listBox.replaceChildren(table.element);
      } catch (error) {
        if (isAbortError(error) || !life.alive || seq !== state.listSeq) return;
        summary.textContent = "";
        listBox.replaceChildren(errorBlock(life, "Could not load assignments: " + errorMessage(error, "Unknown error"), loadList));
      }
    }

    // One delegated listener: rows are re-rendered on every reload, so per-row listeners would leak.
    life.on(listBox, "click", (event) => {
      const button = event.target instanceof Element ? event.target.closest("button[data-ah]") : null;
      if (!button || !listBox.contains(button)) return;
      const id = Number(button.dataset.id);
      if (!Number.isFinite(id)) return;
      if (button.dataset.ah === "reset") resetHandout(id, button);
      else removeHandout(id, button.dataset.topic || "", button);
    });

    async function resetHandout(id, button) {
      const key = "reset:" + id;
      if (state.busy.has(key)) return;
      state.busy.add(key); button.disabled = true;
      try {
        await apiCall(ctx, `${HANDOUTS}/${id}/reset`, { method: "POST" });
        if (life.alive) loadList();
      } catch (error) {
        ui.toast(errorMessage(error, "Could not reset."), { type: "error" });
        if (life.alive) button.disabled = false;
      } finally { state.busy.delete(key); }
    }

    async function removeHandout(id, topic, button) {
      const key = "delete:" + id;
      if (state.busy.has(key)) return;
      state.busy.add(key);
      try {
        const { confirmed } = await ui.confirm({
          tier: "destructive",
          title: `Remove assigned handout: "${topic}"`,
          message: "This removes the assignment for this participant. If they already generated a quiz from it, that quiz and topic are not affected."
        });
        if (!confirmed || !life.alive) return;
        button.disabled = true;
        try {
          await apiCall(ctx, `${HANDOUTS}/${id}`, { method: "DELETE" });
          if (life.alive) loadList();
          refreshActivityLogQuietly();
        } catch (error) {
          ui.toast(errorMessage(error, "Could not remove."), { type: "error" });
          if (life.alive) button.disabled = false;
        }
      } finally { state.busy.delete(key); }
    }

    // ── upload & assign ──────────────────────────────────────────────────────────────────────
    life.on(assignButton, "click", async () => {
      if (state.uploading) return;
      const topic = topicInput.value.trim();
      const file = fileInput.files && fileInput.files[0];
      const emails = pickBoxes().filter(box => box.checked).map(box => box.value);
      if (!topic) { setStatus(assignStatus, "error", "Please enter a topic name."); topicInput.focus(); return; }
      if (!file) { setStatus(assignStatus, "error", "Please choose a handout file."); fileInput.focus(); return; }
      if (!emails.length) { setStatus(assignStatus, "error", "Pick at least one participant."); return; }

      state.uploading = true;
      assignButton.disabled = true;
      assignButton.textContent = "Uploading…";
      setStatus(assignStatus, "info", "Uploading…");
      try {
        const form = new FormData();
        form.append("topic", topic);
        form.append("file", file);
        emails.forEach(email => form.append("emails", email));
        const response = await apiCall(ctx, HANDOUTS, { method: "POST", body: form });
        let message = (response && response.message) || "Assigned.";
        if (response && Array.isArray(response.skipped) && response.skipped.length) message += " Skipped: " + response.skipped.join("; ");
        if (life.alive) {
          setStatus(assignStatus, "success", message);
          topicInput.value = "";
          fileInput.value = "";
          pickBoxes().forEach(box => { box.checked = false; });
          const all = pickAll(); if (all) { all.checked = false; all.indeterminate = false; }
          loadList();
        } else ui.toast(message, { type: "success" });
        refreshActivityLogQuietly();
      } catch (error) {
        if (life.alive) setStatus(assignStatus, "error", errorMessage(error, "Upload failed."));
        else ui.toast(errorMessage(error, "Upload failed."), { type: "error" });
      } finally {
        state.uploading = false;
        if (life.alive) { assignButton.disabled = false; assignButton.textContent = "📤 Upload & Assign"; }
      }
    });

    life.on(refreshButton, "click", loadList);

    loadPicker();
    loadList();
  },

  unmount() {
    teardown();
  }
};
