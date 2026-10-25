/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    async function fetchBannedEmails() {
        try {
            const resp = await apiFetch("/api/admin/banned-emails");
            return resp.bannedEmails || [];
        } catch (err) {
            console.error("Could not load banned emails:", err);
            return [];
        }
    }

    function renderBannedEmails() {
        const banned = adminData.bannedEmails || [];

        document.getElementById("adminContent").innerHTML = `
    <div class="admin-card">
      <div class="admin-card-title">🚫 Ban an Email</div>
      <div class="admin-card-hint">Blocks an email from logging in or registering. Use for confirmed hacking attempts, illegal uploads, or serious policy violations — harsher than suspension. Takes effect on the account's next request.</div>
      <div class="ip-form">
        <div class="ip-form-field">
          <label>Email *</label>
          <input type="text" id="newBanEmail" placeholder="user@example.com" style="width:240px;" />
        </div>
        <div class="ip-form-field">
          <label>Reason *</label>
          <select id="newBanReason" style="width:240px;">
            <option value="">-- Select reason --</option>
            <option value="Hacking attempt detected">Hacking attempt detected</option>
            <option value="Uploaded explicit/obscene content">Uploaded explicit/obscene content</option>
            <option value="Uploaded illegal material">Uploaded illegal material</option>
            <option value="Non-academic / inappropriate content">Non-academic / inappropriate content</option>
            <option value="Spam or bot activity">Spam or bot activity</option>
            <option value="Multiple policy violations">Multiple policy violations</option>
            <option value="Other">Other</option>
          </select>
        </div>
        <div class="ip-form-field">
          <label>Notes</label>
          <input type="text" id="newBanNotes" placeholder="Additional details..." style="width:220px;" />
        </div>
        <div class="ip-form-field" style="align-self:flex-end;">
          <button class="btn primary" style="padding:10px 18px;" id="addBanBtn">🚫 Ban Email</button>
        </div>
      </div>
    </div>

    <div class="admin-card">
      <div class="admin-card-title">📋 Banned Emails</div>
      <div class="admin-card-hint">${banned.length} email${banned.length !== 1 ? 's' : ''} currently banned.</div>
      <div class="table-scroll"><table class="admin-table">
        <thead><tr><th>#</th><th>Email</th><th>Reason</th><th>Banned By</th><th>Banned At</th><th>Actions</th></tr></thead>
        <tbody>
          ${banned.length === 0 ? `<tr><td colspan="6" class="empty-state">No emails currently banned.</td></tr>` :
            banned.map((b, i) => `
            <tr class="row-blocked">
              <td>${i + 1}</td>
              <td style="font-family:monospace;font-weight:700;color:#dc2626;">${escHtmlGlobal(b.email)}</td>
              <td style="font-size:.8rem;">${escHtmlGlobal(b.reason)}</td>
              <td style="font-size:.78rem;color:#6b7280;">${escHtmlGlobal(b.bannedBy || "—")}</td>
              <td style="font-size:.78rem;color:#6b7280;">${b.bannedAt ? new Date(b.bannedAt).toLocaleDateString() : "—"}</td>
              <td><button class="action-btn btn-unblock" data-action="unban-email-direct" data-email="${escHtmlGlobal(b.email)}">✓ Unban</button></td>
            </tr>`).join("")}
        </tbody>
      </table></div>
    </div>

    <div class="admin-card">
      <div class="admin-card-title">ℹ️ How Email Bans Work</div>
      <div class="admin-card-hint">Where enforcement actually happens.</div>
      <div style="font-size:.82rem;color:#374151;line-height:1.7;background:#f0f9ff;border:1px solid #bae6fd;border-radius:10px;padding:14px;">
        <strong>Server-enforced:</strong> a banned email is rejected at registration and at login, and a signed-in user whose email gets banned (or whose account gets suspended) is signed out on their next page load or API call. Bans follow the email rather than the IP address, so they can't be dodged by switching networks and don't hit unrelated students sharing the same campus or home connection. Admin accounts can't be banned, and you can't ban your own email. Unbanning removes the row and takes effect immediately.
      </div>
    </div>`;

        document.getElementById("addBanBtn")?.addEventListener("click", doAddEmailBan);
        document.querySelector("#adminContent .admin-table tbody")?.addEventListener("click", (e) => {
            const btn = e.target.closest("button[data-action='unban-email-direct']");
            if (!btn) return;
            promptUnbanEmail(btn.dataset.email || "");
        });
    }

    async function doAddEmailBan() {
        const email = document.getElementById("newBanEmail")?.value.trim();
        const reason = document.getElementById("newBanReason")?.value;
        const notes = document.getElementById("newBanNotes")?.value.trim();
        if (!email) { showInfoModal("Please enter an email address.", "error"); return; }
        if (!reason) { showInfoModal("Please select a reason.", "error"); return; }
        const btn = document.getElementById("addBanBtn");
        if (btn) { btn.disabled = true; btn.textContent = "Banning..."; }
        try {
            await apiFetch("/api/admin/banned-emails", {
                method: "POST",
                body: JSON.stringify({ email, reason: reason + (notes ? ` — ${notes}` : "") })
            });
            adminData.bannedEmails = await fetchBannedEmails();
            await refreshActivityLog();
            renderBannedEmails();
        } catch (err) {
            if (btn) { btn.disabled = false; btn.textContent = "🚫 Ban Email"; }
            showInfoModal(err.message || "Could not ban this email.", "error");
        }
    }
