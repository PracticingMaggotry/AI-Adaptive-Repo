/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    async function renderAssignedHandouts() {
        document.getElementById("adminContent").innerHTML = `
    <div class="admin-card">
      <div class="admin-card-title">📎 Assign a Handout to Study Participants</div>
      <div class="admin-card-hint">Enrolled participants can't upload their own material. Upload it here instead. The file is only stored: nothing goes to Anthropic until the participant opens it on the quiz page and presses the send button.</div>
      <div class="ip-form" style="margin-bottom:14px;">
        <div class="ip-form-field">
          <label>Topic name *</label>
          <input type="text" id="ahTopic" placeholder="e.g. Data Structures" style="width:240px;" />
        </div>
        <div class="ip-form-field">
          <label>Handout file * (PDF, DOCX, TXT)</label>
          <input type="file" id="ahFile" accept=".txt,.pdf,.doc,.docx,.csv" />
        </div>
      </div>
      <div style="margin-bottom:14px;">
        <div style="font-size:.75rem;font-weight:700;color:#374151;margin-bottom:6px;">Assign to *</div>
        <div id="ahParticipants" style="font-size:.85rem;color:#6b7280;">Loading participants…</div>
      </div>
      <button class="btn primary" id="ahAssignBtn">📤 Upload & Assign</button>
      <div id="ahStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>
    </div>

    <div class="admin-card">
      <div class="admin-card-title">📋 Assigned Handouts</div>
      <div class="admin-card-hint">Pending = not yet sent to Claude. Processed = the participant generated their quiz. "Reset" lets them send it again (for example after they delete the topic).</div>
      <div id="ahList"><div class="empty-state">Loading…</div></div>
    </div>`;
        document.getElementById("ahAssignBtn").onclick = ahAssign;
        loadAhParticipants();
        loadAhList();
    }

    async function loadAhParticipants() {
        const el = document.getElementById("ahParticipants");
        if (!el) return;
        try {
            const r = await apiFetch("/api/admin/participants");
            const list = r.participants || [];
            if (!list.length) {
                el.innerHTML = `<div class="empty-state" style="padding:10px;text-align:left;">No participants enrolled yet. Enrol them under Admin Reports → Study Participants first.</div>`;
                return;
            }
            el.innerHTML = `
                <label style="display:flex;gap:8px;align-items:center;font-weight:700;margin-bottom:8px;color:#374151;">
                  <input type="checkbox" id="ahPickAll" /> Select all (${list.length})
                </label>
                <div style="display:flex;flex-wrap:wrap;gap:6px 20px;max-height:180px;overflow-y:auto;">
                  ${list.map(p => `
                    <label style="display:flex;gap:6px;align-items:center;font-size:.82rem;color:#374151;">
                      <input type="checkbox" class="ah-pick" value="${escHtmlGlobal(p.email)}" />
                      ${escHtmlGlobal(p.code)}
                      <span style="color:#9ca3af;font-family:monospace;font-size:.72rem;">${escHtmlGlobal(p.email)}</span>
                    </label>`).join("")}
                </div>`;
            document.getElementById("ahPickAll").onchange = (e) =>
                document.querySelectorAll(".ah-pick").forEach(c => { c.checked = e.target.checked; });
        } catch (err) {
            el.textContent = "Could not load participants: " + (err.message || "Unknown error");
        }
    }

    async function ahAssign() {
        const topic = document.getElementById("ahTopic").value.trim();
        const file = document.getElementById("ahFile").files[0];
        const emails = Array.from(document.querySelectorAll(".ah-pick:checked")).map(c => c.value);
        if (!topic) { setModalInlineStatus("ahStatus", "Please enter a topic name.", "error"); return; }
        if (!file) { setModalInlineStatus("ahStatus", "Please choose a handout file.", "error"); return; }
        if (!emails.length) { setModalInlineStatus("ahStatus", "Pick at least one participant.", "error"); return; }

        const btn = document.getElementById("ahAssignBtn");
        btn.disabled = true;
        btn.textContent = "Uploading…";
        try {
            const fd = new FormData();
            fd.append("topic", topic);
            fd.append("file", file);
            emails.forEach(e => fd.append("emails", e));
            const r = await apiFetch("/api/admin/assigned-handouts", { method: "POST", body: fd });
            let msg = r.message || "Assigned.";
            if (r.skipped && r.skipped.length) msg += " Skipped: " + r.skipped.join("; ");
            setModalInlineStatus("ahStatus", msg, "success");
            document.getElementById("ahTopic").value = "";
            document.getElementById("ahFile").value = "";
            document.querySelectorAll(".ah-pick, #ahPickAll").forEach(c => { c.checked = false; });
            await Promise.all([loadAhList(), refreshActivityLog()]);
        } catch (err) {
            setModalInlineStatus("ahStatus", err.message || "Upload failed.", "error");
        } finally {
            btn.disabled = false;
            btn.textContent = "📤 Upload & Assign";
        }
    }

    async function loadAhList() {
        const el = document.getElementById("ahList");
        if (!el) return;
        try {
            const r = await apiFetch("/api/admin/assigned-handouts");
            const rows = r.handouts || [];
            if (!rows.length) { el.innerHTML = `<div class="empty-state">Nothing assigned yet.</div>`; return; }
            el.innerHTML = `
              <div class="table-scroll"><table class="admin-table">
                <thead><tr><th>#</th><th>Participant</th><th>Topic</th><th>File</th><th>Assigned</th><th>Status</th><th>Actions</th></tr></thead>
                <tbody>
                  ${rows.map((h, i) => `
                    <tr>
                      <td>${i + 1}</td>
                      <td><strong>${escHtmlGlobal(h.participantCode || h.studentEmail)}</strong></td>
                      <td>${escHtmlGlobal(h.topic)}</td>
                      <td style="font-size:.78rem;">${escHtmlGlobal(h.filename || "—")}${h.sizeBytes ? ` <span style="color:#9ca3af;">(${Math.max(1, Math.round(h.sizeBytes / 1024))} KB)</span>` : ""}</td>
                      <td style="font-size:.75rem;color:#6b7280;">${h.assignedAt ? new Date(h.assignedAt).toLocaleString() : "—"}</td>
                      <td>${h.status === "PROCESSED" ? '<span class="badge-active">Processed</span>' : '<span class="badge-flagged">Pending</span>'}</td>
                      <td style="white-space:nowrap;">
                        ${h.status === "PROCESSED" ? `<button class="action-btn btn-view" data-ah="reset" data-id="${h.id}">↺ Reset</button>` : ""}
                        <button class="action-btn btn-delete" data-ah="delete" data-id="${h.id}" data-topic="${escHtmlGlobal(h.topic)}">🗑 Delete</button>
                      </td>
                    </tr>`).join("")}
                </tbody>
              </table></div>`;
            el.querySelectorAll("button[data-ah]").forEach(b => {
                b.onclick = () => b.dataset.ah === "reset"
                    ? ahReset(Number(b.dataset.id))
                    : ahConfirmDelete(Number(b.dataset.id), b.dataset.topic || "");
            });
        } catch (err) {
            el.innerHTML = `<div class="empty-state">Could not load assignments: ${escHtmlGlobal(err.message || "Unknown error")}</div>`;
        }
    }

    async function ahReset(id) {
        try {
            await apiFetch(`/api/admin/assigned-handouts/${id}/reset`, { method: "POST" });
            await loadAhList();
        } catch (err) { showInfoModal(err.message || "Could not reset.", "error"); }
    }

    function ahConfirmDelete(id, topic) {
        showModal(`
    <div class="modal-title">🗑 Remove assigned handout: "${escHtmlGlobal(topic)}"</div>
    <div class="modal-body">This removes the assignment for this participant. If they already generated a quiz from it, that quiz and topic are not affected.</div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="ahConfirmDeleteBtn">🗑 Remove</button>
    </div>`);
        document.getElementById("ahConfirmDeleteBtn").onclick = async () => {
            try {
                await apiFetch(`/api/admin/assigned-handouts/${id}`, { method: "DELETE" });
                closeModal();
                await Promise.all([loadAhList(), refreshActivityLog()]);
            } catch (err) {
                closeModal();
                showInfoModal(err.message || "Could not remove.", "error");
            }
        };
    }
