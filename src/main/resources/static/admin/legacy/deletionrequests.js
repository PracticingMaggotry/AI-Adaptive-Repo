/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    // The sidebar badge combines BOTH deletion workflows (admin-initiated dual control + self-requested), so its
    // logic lives in admin/people/deletion-badge.js. This global stays because admin.js (boot) and the legacy vote /
    // cancel / execute / request-deletion flows call it by name. It never throws: a badge problem must not make a
    // completed workflow action look like it failed. On failure the module keeps the last complete count (or "?").
    async function refreshDeletionBadge() {
        try {
            let badge = window.adminDeletionBadge;
            if (!badge || typeof badge.refresh !== "function") {
                // First use before any ES-module view has run (admin.js calls this during boot).
                badge = (await import("../people/deletion-badge.js")).getDeletionBadge();
            }
            await badge.refresh();
        } catch (err) {
            console.warn("Could not refresh the deletion requests badge:", err);
        }
    }

    async function renderDeletionRequests() {
        document.getElementById("adminContent").innerHTML = `
    <div class="admin-card">
      <div class="report-header">
        <div>
          <div class="admin-card-title">🗑 Account Deletion Requests</div>
          <div class="admin-card-hint">Every current admin must approve before an account can be permanently deleted. One admin's approval never deletes anything by itself, and the final deletion is a separate, deliberate click.</div>
        </div>
        <div class="range-filter" id="deletionStatusFilter"></div>
      </div>
      <div id="deletionList"><div class="empty-state">Loading...</div></div>
    </div>`;
        renderDeletionStatusFilter();
        await loadDeletionRequests();
    }

    function renderDeletionStatusFilter() {
        const el = document.getElementById("deletionStatusFilter");
        if (!el) return;
        const options = [
            { key: "PENDING", label: "Pending" },
            { key: "APPROVED", label: "Approved — ready" },
            { key: "REJECTED", label: "Rejected" },
            { key: "EXECUTED", label: "Deleted" },
            { key: "CANCELLED", label: "Cancelled" },
            { key: "ALL", label: "All" }
        ];
        el.innerHTML = options.map(o => `
            <button class="range-filter-btn ${deletionStatusFilter === o.key ? 'active' : ''}"
                    onclick="setDeletionStatusFilter('${o.key}')">${o.label}</button>`).join("");
    }

    async function setDeletionStatusFilter(status) {
        deletionStatusFilter = status;
        renderDeletionStatusFilter();
        await loadDeletionRequests();
    }

    async function loadDeletionRequests() {
        const listEl = document.getElementById("deletionList");
        if (!listEl) return;
        // Latest call wins: a slow answer for an earlier filter must never replace the list for the current one.
        const mine = loadDeletionRequests.seq = (loadDeletionRequests.seq || 0) + 1;
        listEl.innerHTML = `<div class="empty-state">Loading...</div>`;
        try {
            const resp = await apiFetch(`/api/admin/deletion-requests?status=${deletionStatusFilter}`);
            if (mine !== loadDeletionRequests.seq) return;
            // A 200 that is not the documented { requests: [...] } envelope (for example an HTML login page after the
            // session expired) is a failure, not an empty queue.
            if (!resp || typeof resp !== "object" || resp.success === false || !Array.isArray(resp.requests)) {
                throw new Error((resp && typeof resp.message === "string" && resp.message) || "The server returned an unexpected response.");
            }
            const requests = resp.requests;
            if (requests.length === 0) {
                listEl.innerHTML = `<div class="empty-state">No ${deletionStatusFilter === 'ALL' ? '' : deletionStatusFilter.toLowerCase() + ' '}requests.</div>`;
                return;
            }
            const statusBadge = {
                PENDING: 'badge-flagged', APPROVED: 'badge-active', REJECTED: 'badge-blocked',
                EXECUTED: 'badge-blocked', CANCELLED: 'role-badge role-student'
            };
            listEl.innerHTML = requests.map(r => `
            <div class="admin-card" style="margin-bottom:12px;border:1px solid ${r.status === 'PENDING' ? '#fcd34d' : r.status === 'APPROVED' ? '#6ee7b7' : '#e2e8f0'};">
              <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap;">
                <div style="flex:1;min-width:240px;">
                  <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:6px;">
                    <span class="${statusBadge[r.status] || 'role-badge role-student'}">${escHtmlGlobal(r.status)}</span>
                    <span style="font-size:.78rem;font-weight:700;color:#111827;">${escHtmlGlobal(r.targetName)} (${escHtmlGlobal(r.targetEmail)})</span>
                    <span style="font-size:.72rem;color:#6b7280;">${r.approveCount} of ${r.requiredCount} admins approved</span>
                  </div>
                  <div style="font-size:.82rem;color:#374151;margin-bottom:6px;"><strong>Reason:</strong> ${escHtmlGlobal(r.reason)}</div>
                  <div style="font-size:.7rem;color:#9ca3af;">Requested by ${escHtmlGlobal(r.requestedBy)} · ${r.requestedAt ? new Date(r.requestedAt).toLocaleString() : "—"}</div>
                  ${r.votes.length ? `<div style="margin-top:8px;font-size:.75rem;color:#374151;">
                    ${r.votes.map(v => `<div>${v.decision === 'APPROVE' ? '✅' : '❌'} ${escHtmlGlobal(v.adminEmail)}${v.note ? ` — ${escHtmlGlobal(v.note)}` : ''}</div>`).join("")}
                  </div>` : ""}
                  ${r.status === 'EXECUTED' ? `<div style="font-size:.72rem;color:#991b1b;margin-top:6px;">Deleted by ${escHtmlGlobal(r.executedBy)} on ${r.executedAt ? new Date(r.executedAt).toLocaleString() : "—"}</div>` : ""}
                </div>
                <div style="display:flex;flex-direction:column;gap:6px;flex-shrink:0;">
                  ${r.status === 'PENDING' && !r.callerVoted ? `
                    <button class="action-btn btn-unblock" onclick="voteDeletion(${r.id}, 'APPROVE')">✓ Approve</button>
                    <button class="action-btn btn-delete" onclick="promptRejectDeletion(${r.id})">✕ Reject</button>` : ""}
                  ${r.status === 'PENDING' && r.callerVoted ? `<span style="font-size:.72rem;color:#6b7280;">You've voted</span>` : ""}
                  ${r.status === 'APPROVED' ? `<button class="action-btn btn-delete" onclick="confirmExecuteDeletion(${r.id})">🗑 Execute Deletion</button>` : ""}
                  ${(r.status === 'PENDING' || r.status === 'APPROVED') ? `<button class="action-btn btn-view" onclick="cancelDeletion(${r.id})">Cancel Request</button>` : ""}
                </div>
              </div>
            </div>`).join("");
        } catch (err) {
            if (mine !== loadDeletionRequests.seq) return;
            listEl.innerHTML = `<div class="empty-state" role="alert">Could not load requests: ${escHtmlGlobal(err.message || "Unknown error")}</div>`;
        }
    }

    // One action per request at a time: a double click on Approve / Cancel / Execute must not send the request twice
    // (the second POST would only come back as a misleading "already voted" / "no longer pending" error).
    function claimDeletionAction(id) {
        const busy = claimDeletionAction.busy || (claimDeletionAction.busy = new Set());
        const key = String(id);
        if (busy.has(key)) return false;
        busy.add(key);
        return true;
    }
    function releaseDeletionAction(id) { if (claimDeletionAction.busy) claimDeletionAction.busy.delete(String(id)); }

    // The server answered 404 / 400: the request changed or vanished (another admin voted, cancelled or executed it,
    // or the admin roster changed). The list and the sidebar badge on screen are out of date, so re-read both.
    // Network failures, 401/403 and 5xx change nothing on the server, so they stay a plain error.
    async function resyncAfterStaleDeletionAction(err) {
        if (!err || (err.status !== 404 && err.status !== 400)) return;
        await loadDeletionRequests();
        await refreshDeletionBadge();
    }

    // The legacy #modal host has no dialog semantics of its own. This gives the deletion confirmations what the
    // shared <dialog>-based confirm already has: role/aria-modal, focus moved in, Tab kept inside, Escape closes,
    // and focus returned to the control that opened it. It is local to this tab (shared.js is not touched).
    function enhanceDeletionModal(initialFocusSelector) {
        const host = document.getElementById("modal");
        const box = host && host.querySelector(".modal-box");
        if (!box) return;
        if (!host.__deletionModalOpener) {
            const a = document.activeElement;
            host.__deletionModalOpener = a && a !== document.body && !host.contains(a) ? a : null;
        }
        const opener = host.__deletionModalOpener;
        box.setAttribute("role", "dialog");
        box.setAttribute("aria-modal", "true");
        box.tabIndex = -1;
        const title = box.querySelector(".modal-title");
        if (title) { title.id = "deletionModalTitle"; box.setAttribute("aria-labelledby", "deletionModalTitle"); }
        const focusables = () => [...box.querySelectorAll('button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), a[href]')];
        const initial = (initialFocusSelector && box.querySelector(initialFocusSelector)) || focusables()[0] || box;
        initial.focus();
        const onKeydown = (e) => {
            if (e.key === "Escape") {
                const working = box.querySelector("#confirmExecuteBtn");
                if (working && working.disabled) return;      // a deletion is being sent: do not dismiss under it
                e.preventDefault(); e.stopPropagation();
                closeModal();
            } else if (e.key === "Tab") {
                const items = focusables();
                if (items.length === 0) { e.preventDefault(); return; }
                const first = items[0], last = items[items.length - 1];
                if (!box.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
                else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
                else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
            }
        };
        host.addEventListener("keydown", onKeydown);
        const watcher = new MutationObserver(() => {
            const closed = host.style.display === "none";
            if (!closed && host.contains(box)) return;
            watcher.disconnect();
            host.removeEventListener("keydown", onKeydown);
            if (!closed) return;                               // replaced by another modal: that one owns focus now
            delete host.__deletionModalOpener;
            const target = opener && opener.isConnected ? opener : document.getElementById("deletionList");
            if (target) { if (!target.matches("a[href], button, input, select, textarea, [tabindex]")) target.tabIndex = -1; target.focus({ preventScroll: true }); }
        });
        watcher.observe(host, { attributes: true, attributeFilter: ["style"], childList: true });
    }

    async function voteDeletion(id, decision, note) {
        if (!claimDeletionAction(id)) return;
        try {
            let posted = false;
            try {
                await apiFetch(`/api/admin/deletion-requests/${id}/vote`, {
                    method: "POST",
                    body: JSON.stringify({ decision, note: note || "" })
                });
                posted = true;
            } catch (err) {
                showInfoModal(err.message || "Failed.", "error");
                enhanceDeletionModal("button");
                await resyncAfterStaleDeletionAction(err);
            }
            if (posted) {
                await refreshActivityLog();
                await loadDeletionRequests();
                await refreshDeletionBadge();
            }
        } finally { releaseDeletionAction(id); }
    }

    function promptRejectDeletion(id) {
        showModal(`
    <div class="modal-title">✕ Reject deletion request</div>
    <div class="modal-body">A single rejection vetoes the request and the account will not be deleted.</div>
    <div class="ip-form-field" style="width:100%;margin-bottom:14px;">
      <label>Why are you rejecting it? (optional)</label>
      <textarea id="rejectDeletionNote" rows="2" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;resize:none;box-sizing:border-box;"></textarea>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="confirmRejectDeletionBtn">Reject Request</button>
    </div>`);
        enhanceDeletionModal("#rejectDeletionNote");
        document.getElementById("confirmRejectDeletionBtn").onclick = async () => {
            const note = document.getElementById("rejectDeletionNote")?.value.trim() || "";
            closeModal();
            await voteDeletion(id, "REJECT", note);
        };
    }

    function confirmExecuteDeletion(id) {
        showModal(`
    <div class="modal-title">🗑 Confirm permanent deletion</div>
    <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;margin-bottom:14px;color:#7f1d1d;line-height:1.6;">
      ⚠️ This is the final, irreversible step. Every admin has already approved — this click actually deletes the account. This cannot be undone.
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="confirmExecuteBtn">🗑 Yes, Delete Permanently</button>
    </div>
    <div id="executeModalStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        enhanceDeletionModal(".btn.ghost");   // the safe choice (Cancel) gets the initial focus
        document.getElementById("confirmExecuteBtn").onclick = async () => {
            const btn = document.getElementById("confirmExecuteBtn");
            if (!claimDeletionAction(id)) return;
            btn.disabled = true; btn.textContent = "Deleting...";
            try {
                await apiFetch(`/api/admin/deletion-requests/${id}/execute`, { method: "POST" });
                closeModal();
                await Promise.all([refreshUsers(), refreshActivityLog()]);
                await Promise.all([loadDeletionRequests(), refreshDeletionBadge()]);
            } catch (err) {
                btn.disabled = false; btn.textContent = "🗑 Yes, Delete Permanently";
                setModalInlineStatus("executeModalStatus", err.message || "Failed.", "error");
                await resyncAfterStaleDeletionAction(err);
            } finally { releaseDeletionAction(id); }
        };
    }

    async function cancelDeletion(id) {
        if (!claimDeletionAction(id)) return;
        try {
            let posted = false;
            try {
                await apiFetch(`/api/admin/deletion-requests/${id}/cancel`, { method: "POST" });
                posted = true;
            } catch (err) {
                showInfoModal(err.message || "Failed.", "error");
                enhanceDeletionModal("button");
                await resyncAfterStaleDeletionAction(err);
            }
            if (posted) {
                await refreshActivityLog();
                await loadDeletionRequests();
                await refreshDeletionBadge();
            }
        } finally { releaseDeletionAction(id); }
    }
