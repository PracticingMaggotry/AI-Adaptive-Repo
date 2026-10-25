/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    function setUserFlagFilter(value) {
        userFlagFilter = value;
        renderUsers(document.getElementById("userSearchInput")?.value || "");
    }

    function renderUsers(filter = "") {
        const banned = (adminData.bannedEmails || []).map(b => (b.email || "").toLowerCase());
        const searchHadFocus = document.activeElement && document.activeElement.id === "userSearchInput";

        let users = adminData.users;
        if (filter) {
            const q = filter.toLowerCase();
            users = users.filter(u => (u.fullName || "").toLowerCase().includes(q) || (u.email || "").toLowerCase().includes(q));
        }
        if (userFlagFilter === "flagged") users = users.filter(u => u.flagged);
        else if (userFlagFilter === "notflagged") users = users.filter(u => !u.flagged);

        refreshFlaggedBadge();

        document.getElementById("adminContent").innerHTML = `
    <div class="admin-card">
      <div class="report-header">
        <div>
          <div class="admin-card-title">👥 Registered Users</div>
          <div class="admin-card-hint">Flag suspicious users, suspend (reversible), ban an email (harsher), or request a deletion that every admin must approve. Account creation is open to all.</div>
        </div>
        <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;">
          <select id="userFlagFilterSelect" style="padding:9px 12px;border:1px solid #e2e8f0;border-radius:10px;font-size:0.85rem;background:#f8fafc;" onchange="setUserFlagFilter(this.value)">
            <option value="all" ${userFlagFilter === "all" ? "selected" : ""}>All Users</option>
            <option value="flagged" ${userFlagFilter === "flagged" ? "selected" : ""}>Flagged Only</option>
            <option value="notflagged" ${userFlagFilter === "notflagged" ? "selected" : ""}>Not Flagged</option>
          </select>
          <div class="admin-search">
            <span>🔎</span>
            <input type="text" id="userSearchInput" placeholder="Search name or email..." oninput="renderUsers(this.value)" value="${escHtmlGlobal(filter)}" />
          </div>
          <button class="btn ghost" onclick="openDailyLimitsModal()">⏱ Daily Limits</button>
        </div>
      </div>
      <div class="table-scroll"><table class="admin-table">
        <thead><tr>
          <th>#</th><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Actions</th>
        </tr></thead>
        <tbody>
          ${users.length === 0 ? `<tr><td colspan="6" class="empty-state">No users found.</td></tr>` :
            users.map((u, i) => {
                const isFlagged = !!u.flagged;
                const isBanned = banned.includes((u.email || "").toLowerCase());
                const isArchived = !!u.archived;
                const rowCls = (isArchived || isBanned) ? "row-blocked" : isFlagged ? "row-flagged" : "";
                const email = escHtmlGlobal(u.email);
                const name = escHtmlGlobal(u.fullName);
                return `<tr class="${rowCls}">
              <td>${i + 1}</td>
              <td><strong>${name}</strong></td>
              <td style="font-family:monospace;font-size:.8rem;">${email}</td>
              <td><span class="role-badge ${u.isAdmin ? 'role-admin' : 'role-student'}">${u.isAdmin ? "Admin" : "Student"}</span></td>
              <td>
                ${isArchived ? '<span class="badge-blocked">Suspended</span>' : ''}
                ${isBanned ? '<span class="badge-blocked">Email Banned</span>' : ''}
                ${!isArchived && !isBanned && isFlagged ? '<span class="badge-flagged">Flagged</span>' : ''}
                ${!isArchived && !isBanned && !isFlagged ? '<span class="badge-active">Active</span>' : ''}
              </td>
              <td style="white-space:nowrap;">
                <button class="action-btn btn-view" data-action="view-topics" data-email="${email}" data-name="${name}">📂 Topics</button>
                <button class="action-btn btn-view" data-action="view-usage" data-id="${u.id}" data-email="${email}" data-name="${name}">⏱ Usage</button>
                ${isFlagged
                    ? `<button class="action-btn btn-unblock" data-action="unflag" data-email="${email}">✓ Unflag</button>`
                    : `<button class="action-btn btn-flag" data-action="flag" data-email="${email}" data-name="${name}">⚑ Flag</button>`}
                ${!u.isAdmin ? (isArchived
                    ? `<button class="action-btn btn-unblock" data-action="unarchive" data-email="${email}" data-name="${name}">✓ Restore</button>`
                    : `<button class="action-btn btn-block" data-action="archive" data-email="${email}" data-name="${name}">⏸ Suspend</button>`) : ""}
                ${!u.isAdmin ? (isBanned
                    ? `<button class="action-btn btn-unblock" data-action="unban-email" data-email="${email}">✓ Unban Email</button>`
                    : `<button class="action-btn btn-block" style="background:#fee2e2;color:#991b1b;border-color:#fca5a5;" data-action="ban-email" data-email="${email}" data-name="${name}">🚫 Ban Email</button>`) : ""}
                ${!u.isAdmin ? `<button class="action-btn btn-view" style="background:#ede9fe;color:#5b21b6;border-color:#ddd6fe;" data-action="promote" data-email="${email}" data-name="${name}">⬆ Promote to Admin</button>` : ""}
                ${!u.isAdmin ? `<button class="action-btn btn-delete" data-action="request-delete" data-email="${email}" data-name="${name}">🗑 Request Deletion</button>` : ""}
              </td>
            </tr>`;
            }).join("")}
        </tbody>
      </table></div>
    </div>

    <div class="admin-card">
      <div class="admin-card-title">🗑 Account Deletion</div>
      <div class="admin-card-hint">Deleting an account is irreversible, so it needs every admin's approval. Track and vote on requests in the Deletion Requests tab; completed deletions also appear in the Activity Log.</div>
      <button class="btn primary" onclick="switchTab('deletionrequests')">Open Deletion Requests →</button>
    </div>`;

        document.querySelector("#adminContent .admin-table tbody")?.addEventListener("click", (e) => {
            const btn = e.target.closest("button[data-action]");
            if (!btn) return;
            const email = btn.dataset.email || "";
            const name  = btn.dataset.name  || "";
            switch (btn.dataset.action) {
                case "view-topics":    viewUserTopics(email, name); break;
                case "view-usage":     viewUserUsage(btn.dataset.id, email, name); break;
                case "flag":           flagUser(email, name); break;
                case "unflag":         unflagUser(email); break;
                case "archive":        promptArchiveUser(email, name); break;
                case "unarchive":      promptUnarchiveUser(email, name); break;
                case "ban-email":      promptBanEmail(email, name); break;
                case "unban-email":    promptUnbanEmail(email); break;
                case "promote":        promptPromoteUser(email, name); break;
                case "request-delete": promptRequestDeletion(email, name); break;
            }
        });

        if (searchHadFocus) {
            const el = document.getElementById("userSearchInput");
            if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length); }
        }
    }

    async function viewUserTopics(email, name) {
        showModal(`
    <div class="modal-title">📂 Topics uploaded by ${escHtmlGlobal(name)}</div>
    <div class="modal-body">
      <div class="cr-spinner"><div class="cr-spin-icon"></div>Loading this user's uploads…</div>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);

        let materials = [];
        try {
            if (!crAllMaterials.length) {
                const resp = await apiFetch("/api/admin/materials");
                crAllMaterials = resp.materials || [];
            }
            materials = crAllMaterials.filter(m => (m.uploadedBy || "").toLowerCase() === (email || "").toLowerCase());
        } catch (err) {
            showModal(`
    <div class="modal-title">📂 Topics uploaded by ${escHtmlGlobal(name)}</div>
    <div class="modal-body">
      <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;color:#7f1d1d;">
        Could not load this user's uploads: ${escHtmlGlobal(err.message || "Unknown error")}
      </div>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);
            return;
        }

        renderUserTopicsModal(email, name, materials);
    }

    function renderUserTopicsModal(email, name, materials) {
        const count = materials.length;

        const rows = materials.map(m => {
            const fn = (m.originalFilename || "").toLowerCase();
            const ct = (m.contentType || "").toLowerCase();
            const typeIcon = fn.endsWith(".pdf") || ct.includes("pdf") ? "📕"
                : fn.endsWith(".docx") || fn.endsWith(".doc") ? "📘"
                    : "📄";
            const diagBadge = m.hasDiagram
                ? `<span style="font-size:.62rem;font-weight:700;padding:1px 6px;border-radius:99px;background:#ede9fe;color:#5b21b6;margin-left:6px;">🖼 diagram</span>`
                : "";
            const sizeStr = m.sizeBytes
                ? (m.sizeBytes < 1024*1024 ? Math.round(m.sizeBytes/1024)+"KB" : (m.sizeBytes/(1024*1024)).toFixed(1)+"MB")
                : "—";
            const dateStr = m.uploadedAt ? m.uploadedAt.substring(0,10) : "—";
            const categoryStr = [m.primaryCategory, m.subCategory].filter(Boolean).join(" · ");

            return `
      <div style="border:1px solid #e5e7eb;border-radius:10px;padding:10px 12px;margin-bottom:8px;display:flex;align-items:center;gap:10px;">
        <div style="flex:1;min-width:0;">
          <div style="font-weight:700;font-size:.86rem;color:#1f2937;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
            ${escHtmlGlobal(m.topic || "Untitled topic")}${diagBadge}
          </div>
          <div style="font-size:.74rem;color:#6b7280;margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
            ${typeIcon} ${escHtmlGlobal(m.originalFilename || "—")} &nbsp;·&nbsp; ${dateStr} &nbsp;·&nbsp; ${sizeStr}${categoryStr ? " &nbsp;·&nbsp; " + escHtmlGlobal(categoryStr) : ""}
          </div>
        </div>
        <div style="white-space:nowrap;flex-shrink:0;">
          <button class="action-btn btn-view" data-mat-read="${m.id}">📖 Read</button>
          <button class="action-btn btn-delete" data-mat-delete-topic="${escHtmlGlobal(m.topic||'')}">🗑 Delete</button>
        </div>
      </div>`;
        }).join("");

        showModal(`
    <div class="modal-title">📂 Topics uploaded by ${escHtmlGlobal(name)}</div>
    <div class="modal-body">
      <p style="margin:0 0 12px;font-size:.8rem;color:#6b7280;">
        ${count === 0 ? "This user hasn't uploaded any materials yet." : `${count} material${count !== 1 ? "s" : ""} uploaded by this user.`}
      </p>
      <div style="max-height:360px;overflow-y:auto;padding-right:2px;">
        ${count === 0
            ? `<div class="empty-state" style="padding:24px 0;">No uploads found for ${escHtmlGlobal(email)}.</div>`
            : rows}
      </div>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
      <button class="btn primary" onclick="closeModal();switchTab('topics')">Go to Topics & Content</button>
    </div>`);

        document.querySelectorAll("[data-mat-read]").forEach(btn => {
            btn.addEventListener("click", () => crOpenReview(Number(btn.dataset.matRead)));
        });
        document.querySelectorAll("[data-mat-delete-topic]").forEach(btn => {
            btn.addEventListener("click", () => promptDeleteTopic(btn.dataset.matDeleteTopic));
        });
    }

    function flagUser(email, name) {
        showModal(`
    <div class="modal-title">⚑ Flag user: ${escHtmlGlobal(name)}</div>
    <div class="modal-body">Flag this user for suspicious activity, inappropriate uploads, or policy violations. They will remain active but marked for review by every admin.</div>
    <div style="margin-bottom:14px;">
      <label style="font-size:.78rem;font-weight:700;color:#374151;display:block;margin-bottom:6px;">Reason for flagging</label>
      <textarea id="flagReason" rows="3" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;resize:none;box-sizing:border-box;" placeholder="e.g. Uploaded explicit content, suspected hacking attempt..."></textarea>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#d97706;border-color:#d97706;" id="confirmFlagBtn">⚑ Flag User</button>
    </div>`);
        document.getElementById("confirmFlagBtn").onclick = () => confirmFlag(email, name);
    }

    async function confirmFlag(email, name) {
        const reason = document.getElementById("flagReason")?.value || "No reason given";
        const btn = document.getElementById("confirmFlagBtn");
        btn.disabled = true;
        btn.textContent = "Flagging...";
        try {
            await apiFetch("/api/admin/flag-user", { method: "POST", body: JSON.stringify({ email, reason }) });
            await Promise.all([refreshUsers(), refreshActivityLog()]);
            closeModal();
            renderUsers();
        } catch (err) {
            btn.disabled = false;
            btn.textContent = "⚑ Flag User";
            showInfoModal("Could not flag user: " + (err.message || "Unknown error"), "error");
        }
    }

    async function unflagUser(email) {
        try {
            await apiFetch("/api/admin/unflag-user", { method: "POST", body: JSON.stringify({ email }) });
            await Promise.all([refreshUsers(), refreshActivityLog()]);
            renderUsers();
        } catch (err) {
            showInfoModal("Could not unflag user: " + (err.message || "Unknown error"), "error");
        }
    }

    async function viewUserUsage(id, email, name) {
        showModal(`
    <div class="modal-title">⏱ Daily usage — ${escHtmlGlobal(name)}</div>
    <div class="modal-body">
      <div class="cr-spinner"><div class="cr-spin-icon"></div>Loading today's usage…</div>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);

        try {
            const resp = await apiFetch(`/api/admin/users/${id}/daily-usage`);
            renderUserUsageModal(id, name, resp.usage || []);
        } catch (err) {
            showModal(`
    <div class="modal-title">⏱ Daily usage — ${escHtmlGlobal(name)}</div>
    <div class="modal-body">
      <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;color:#7f1d1d;">
        Could not load usage: ${escHtmlGlobal(err.message || "Unknown error")}
      </div>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);
        }
    }

    function renderUserUsageModal(id, name, usage) {
        window.__adminUsageCache = Array.isArray(usage) ? usage : [];
        const rows = usage.map(u => {
            const label = ACTION_TYPE_LABELS[u.actionType] || u.actionType;
            const hasOverride = u.personalLimit !== null && u.personalLimit !== undefined;
            const effectiveLimit = u.limit ?? (hasOverride ? u.personalLimit : u.globalLimit);
            return `
      <div class="user-usage-row" data-usage-row="${escHtmlGlobal(u.actionType)}"
           style="border:1px solid #e5e7eb;border-radius:10px;padding:10px 12px;margin-bottom:8px;">
        <div style="display:flex;align-items:center;gap:10px;margin-bottom:8px;">
          <div style="flex:1;min-width:0;">
            <div style="font-weight:700;font-size:.84rem;color:#1f2937;">${escHtmlGlobal(label)}</div>
            <div class="usage-limit-summary" style="font-size:.72rem;color:#6b7280;">
              Platform default: ${u.globalLimit} / day${hasOverride ? ` · <span style="color:#7c3aed;font-weight:600;">personal cap active (${effectiveLimit} / day)</span>` : ""}
            </div>
          </div>
          <div style="text-align:right;">
            <div style="font-size:.7rem;color:#9ca3af;">used today</div>
            <input type="number" min="0" step="1" inputmode="numeric"
                   data-usage-input="${escHtmlGlobal(u.actionType)}" value="${Math.max(0, Number(u.used) || 0)}"
                   style="width:70px;padding:6px 7px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;text-align:center;" />
          </div>
          <button type="button" class="action-btn btn-view" data-usage-save="${escHtmlGlobal(u.actionType)}">💾 Save</button>
          <button type="button" class="action-btn btn-unblock" data-usage-reset="${escHtmlGlobal(u.actionType)}">↺ Reset</button>
        </div>
        <div style="display:flex;align-items:center;gap:10px;padding-top:8px;border-top:1px dashed #e5e7eb;">
          <div style="flex:1;min-width:0;">
            <div style="font-size:.72rem;color:#6b7280;">Personal daily cap for this user (overrides the platform default above)</div>
          </div>
          <div style="text-align:right;">
            <div style="font-size:.7rem;color:#9ca3af;">personal cap</div>
            <input type="number" min="0" step="1" inputmode="numeric"
                   data-limit-input="${escHtmlGlobal(u.actionType)}"
                   placeholder="${u.globalLimit}" value="${hasOverride ? u.personalLimit : ""}"
                   style="width:70px;padding:6px 7px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;text-align:center;" />
          </div>
          <button type="button" class="action-btn btn-view" data-personal-save="${escHtmlGlobal(u.actionType)}">⬆️ Set cap</button>
          <button type="button" class="action-btn btn-unblock" data-personal-clear="${escHtmlGlobal(u.actionType)}" ${hasOverride ? "" : "disabled"}>✕ Use default</button>
        </div>
      </div>`;
        }).join("");

        showModal(`
    <div class="modal-title">⏱ Daily usage — ${escHtmlGlobal(name)}</div>
    <div class="modal-body">
      <p style="margin:0 0 12px;font-size:.8rem;color:#6b7280;">Today's usage against each daily limit. Edit "used" to manually correct or reset today's count. Set a personal cap to give this user a different daily limit. Changes are saved immediately.</p>
      <div id="usageModalStatus" style="display:none;margin-bottom:10px;padding:8px 10px;border-radius:8px;font-size:.78rem;"></div>
      ${usage.length === 0 ? `<div class="empty-state" style="padding:24px 0;">No daily limits configured.</div>` : rows}
    </div>
    <div class="modal-actions">
      <button type="button" class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);

        document.querySelectorAll("[data-usage-save]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const actionType = btn.dataset.usageSave;
                const row = btn.closest("[data-usage-row]");
                const input = row?.querySelector("[data-usage-input]");
                if (!input) return;
                const used = parseNonNegativeInteger(input.value);
                if (used === null) {
                    setModalInlineStatus("usageModalStatus", "Used today must be a whole number of 0 or greater.", "error");
                    input.focus();
                    return;
                }
                await saveUserUsage(id, name, actionType, used, btn, row);
            });
        });

        document.querySelectorAll("[data-usage-reset]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const actionType = btn.dataset.usageReset;
                const row = btn.closest("[data-usage-row]");
                const saveBtn = row?.querySelector("[data-usage-save]");
                await saveUserUsage(id, name, actionType, 0, saveBtn || btn, row);
            });
        });

        document.querySelectorAll("[data-personal-save]").forEach(btn => {
            btn.addEventListener("click", async () => {
                const actionType = btn.dataset.personalSave;
                const row = btn.closest("[data-usage-row]");
                const input = row?.querySelector("[data-limit-input]");
                if (!input) return;
                const limit = parseNonNegativeInteger(input.value);
                if (limit === null) {
                    setModalInlineStatus("usageModalStatus", "Personal cap must be a whole number of 0 or greater.", "error");
                    input.focus();
                    return;
                }
                await savePersonalLimit(id, name, actionType, limit, btn, row);
            });
        });

        document.querySelectorAll("[data-personal-clear]").forEach(btn => {
            btn.addEventListener("click", async () => {
                if (btn.disabled) return;
                const row = btn.closest("[data-usage-row]");
                await clearPersonalLimit(id, name, btn.dataset.personalClear, btn, row);
            });
        });
    }

    function parseNonNegativeInteger(value) {
        const text = String(value ?? "").trim();
        if (!/^\d+$/.test(text)) return null;
        const n = Number(text);
        return Number.isSafeInteger(n) && n >= 0 ? n : null;
    }

    async function saveUserUsage(id, name, actionType, used, triggerBtn, row) {
        const originalText = triggerBtn?.textContent;
        try {
            if (triggerBtn) {
                triggerBtn.disabled = true;
                triggerBtn.textContent = "Saving…";
            }
            if (row) row.querySelectorAll("button, input").forEach(el => { if (el !== triggerBtn) el.disabled = true; });

            const result = await apiFetch(`/api/admin/users/${id}/daily-usage/${encodeURIComponent(actionType)}`, {
                method: "PUT",
                body: JSON.stringify({ used })
            });

            const input = row?.querySelector("[data-usage-input]");
            if (input) input.value = String(result.used ?? used);
            setModalInlineStatus("usageModalStatus", `${ACTION_TYPE_LABELS[actionType] || actionType}: usage set to ${result.used ?? used}.`, "success");
            await refreshActivityLog();
        } catch (err) {
            setModalInlineStatus("usageModalStatus", "Could not save usage: " + (err.message || "Unknown error"), "error");
        } finally {
            if (row) row.querySelectorAll("button, input").forEach(el => { el.disabled = false; });
            if (triggerBtn) {
                triggerBtn.disabled = false;
                triggerBtn.textContent = originalText || "💾 Save";
            }
        }
    }

    async function savePersonalLimit(id, name, actionType, limit, triggerBtn, row) {
        const originalText = triggerBtn?.textContent;
        try {
            if (triggerBtn) {
                triggerBtn.disabled = true;
                triggerBtn.textContent = "Saving…";
            }
            if (row) row.querySelectorAll("button, input").forEach(el => { if (el !== triggerBtn) el.disabled = true; });

            const result = await apiFetch(`/api/admin/users/${id}/daily-limit/${encodeURIComponent(actionType)}`, {
                method: "PUT",
                body: JSON.stringify({ limit })
            });

            const input = row?.querySelector("[data-limit-input]");
            if (input) input.value = String(result.personalLimit ?? limit);
            const clearBtn = row?.querySelector("[data-personal-clear]");
            if (clearBtn) clearBtn.disabled = false;
            const summary = row?.querySelector(".usage-limit-summary");
            const usageItem = Array.isArray(window.__adminUsageCache) ? window.__adminUsageCache.find(x => x.actionType === actionType) : null;
            const globalLimit = usageItem?.globalLimit ?? "platform default";
            if (summary) summary.innerHTML = `Platform default: ${globalLimit} / day · <span style="color:#7c3aed;font-weight:600;">personal cap active (${result.personalLimit ?? limit} / day)</span>`;
            setModalInlineStatus("usageModalStatus", `${ACTION_TYPE_LABELS[actionType] || actionType}: personal cap set to ${result.personalLimit ?? limit} / day.`, "success");
            await refreshActivityLog();
        } catch (err) {
            setModalInlineStatus("usageModalStatus", "Could not save personal cap: " + (err.message || "Unknown error"), "error");
        } finally {
            if (row) row.querySelectorAll("button, input").forEach(el => { el.disabled = false; });
            if (triggerBtn) {
                triggerBtn.disabled = false;
                triggerBtn.textContent = originalText || "⬆️ Set cap";
            }
        }
    }

    async function clearPersonalLimit(id, name, actionType, triggerBtn, row) {
        const originalText = triggerBtn?.textContent;
        try {
            if (triggerBtn) {
                triggerBtn.disabled = true;
                triggerBtn.textContent = "Clearing…";
            }
            if (row) row.querySelectorAll("button, input").forEach(el => { if (el !== triggerBtn) el.disabled = true; });

            await apiFetch(`/api/admin/users/${id}/daily-limit/${encodeURIComponent(actionType)}`, {
                method: "DELETE"
            });

            const input = row?.querySelector("[data-limit-input]");
            if (input) input.value = "";
            const clearBtn = row?.querySelector("[data-personal-clear]");
            if (clearBtn) clearBtn.disabled = true;
            const usageItem = Array.isArray(window.__adminUsageCache) ? window.__adminUsageCache.find(x => x.actionType === actionType) : null;
            const summary = row?.querySelector(".usage-limit-summary");
            if (summary) summary.textContent = `Platform default: ${usageItem?.globalLimit ?? "default"} / day`;
            setModalInlineStatus("usageModalStatus", `${ACTION_TYPE_LABELS[actionType] || actionType}: reverted to the platform default.`, "success");
            await refreshActivityLog();
        } catch (err) {
            setModalInlineStatus("usageModalStatus", "Could not clear personal cap: " + (err.message || "Unknown error"), "error");
        } finally {
            if (row) row.querySelectorAll("button, input").forEach(el => { el.disabled = false; });
            const clearBtn = row?.querySelector("[data-personal-clear]");
            if (clearBtn && !row?.querySelector("[data-limit-input]")?.value) clearBtn.disabled = true;
            if (triggerBtn) {
                triggerBtn.disabled = false;
                triggerBtn.textContent = originalText || "✕ Use default";
            }
        }
    }

    async function openDailyLimitsModal() {
        showModal(`
    <div class="modal-title">⏱ Daily Limits</div>
    <div class="modal-body">
      <div class="cr-spinner"><div class="cr-spin-icon"></div>Loading current limits…</div>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);

        try {
            const resp = await apiFetch("/api/admin/daily-limits");
            renderDailyLimitsModal(resp.limits || {});
        } catch (err) {
            showModal(`
    <div class="modal-title">⏱ Daily Limits</div>
    <div class="modal-body">
      <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;color:#7f1d1d;">
        Could not load limits: ${escHtmlGlobal(err.message || "Unknown error")}
      </div>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);
        }
    }

    function renderDailyLimitsModal(limits) {
        const rows = Object.entries(limits).map(([actionType, limit]) => {
            const label = ACTION_TYPE_LABELS[actionType] || actionType;
            return `
      <div style="display:flex;align-items:center;gap:10px;border:1px solid #e5e7eb;border-radius:10px;padding:9px 12px;margin-bottom:8px;">
        <div style="flex:1;min-width:0;">
          <div style="font-weight:700;font-size:.84rem;color:#1f2937;">${escHtmlGlobal(label)}</div>
          <div style="font-size:.72rem;color:#6b7280;">Applies to every student, per calendar day.</div>
        </div>
        <input type="number" min="0" data-limit-input="${escHtmlGlobal(actionType)}" value="${limit}"
               style="width:80px;padding:7px 8px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;text-align:center;" />
        <button class="action-btn btn-view" data-limit-save="${escHtmlGlobal(actionType)}">💾 Save</button>
      </div>`;
        }).join("");

        showModal(`
    <div class="modal-title">⏱ Daily Limits</div>
    <div class="modal-body">
      <p style="margin:0 0 12px;font-size:.8rem;color:#6b7280;">These caps apply platform-wide and take effect immediately — no redeploy needed.</p>
      <div id="limitsModalStatus"></div>
      ${Object.keys(limits).length === 0 ? `<div class="empty-state" style="padding:24px 0;">No configurable limits found.</div>` : rows}
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Close</button>
    </div>`);

        document.querySelectorAll("[data-limit-save]").forEach(btn => {
            btn.addEventListener("click", () => {
                const actionType = btn.dataset.limitSave;
                const input = document.querySelector(`[data-limit-input="${CSS.escape(actionType)}"]`);
                saveDailyLimit(actionType, Number(input.value));
            });
        });
    }

    async function saveDailyLimit(actionType, limit) {
        try {
            await apiFetch(`/api/admin/daily-limits/${encodeURIComponent(actionType)}`, {
                method: "PUT",
                body: JSON.stringify({ limit })
            });
            setModalInlineStatus("limitsModalStatus", "Saved. New requests will use this limit right away.", "success");
            await refreshActivityLog();
        } catch (err) {
            setModalInlineStatus("limitsModalStatus", "Could not save: " + (err.message || "Unknown error"), "error");
        }
    }

    function setModalInlineStatus(elId, message, type) {
        const el = document.getElementById(elId);
        if (!el) return;
        const styles = {
            error:   { background: "#fef2f2", border: "1px solid #fecaca", color: "#991b1b" },
            info:    { background: "#eff6ff", border: "1px solid #bfdbfe", color: "#1e40af" },
            success: { background: "#f0fdf4", border: "1px solid #bbf7d0", color: "#065f46" }
        };
        const s = styles[type] || styles.info;
        el.style.display = "block";
        el.style.background = s.background;
        el.style.border = s.border;
        el.style.color = s.color;
        el.textContent = message;
    }

    function promptArchiveUser(email, name) {
        showModal(`
    <div class="modal-title">⏸ Suspend: ${escHtmlGlobal(name)}</div>
    <div class="modal-body">This signs them out on their next request and blocks login until restored. It is reversible and their data is untouched.</div>
    <div class="ip-form-field" style="width:100%;margin-bottom:14px;">
      <label>Reason *</label>
      <textarea id="archiveReason" rows="2" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;resize:none;box-sizing:border-box;" placeholder="e.g. Uploaded inappropriate content, suspected account compromise..."></textarea>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#d97706;border-color:#d97706;" id="confirmArchiveBtn">⏸ Suspend</button>
    </div>
    <div id="archiveModalStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        document.getElementById("confirmArchiveBtn").onclick = async () => {
            const reason = document.getElementById("archiveReason")?.value.trim();
            if (!reason) { setModalInlineStatus("archiveModalStatus", "Please give a reason.", "error"); return; }
            const btn = document.getElementById("confirmArchiveBtn");
            btn.disabled = true; btn.textContent = "Suspending...";
            try {
                await apiFetch("/api/admin/archive-user", { method: "POST", body: JSON.stringify({ email, reason }) });
                await Promise.all([refreshUsers(), refreshActivityLog()]);
                closeModal(); renderCurrentTab();
            } catch (err) {
                btn.disabled = false; btn.textContent = "⏸ Suspend";
                setModalInlineStatus("archiveModalStatus", err.message || "Failed.", "error");
            }
        };
    }

    function promptUnarchiveUser(email, name) {
        showModal(`
    <div class="modal-title">✓ Restore: ${escHtmlGlobal(name)}</div>
    <div class="modal-body">Lifts the suspension — they can log in again immediately.</div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#059669;border-color:#059669;" id="confirmUnarchiveBtn">✓ Restore</button>
    </div>
    <div id="unarchiveModalStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        document.getElementById("confirmUnarchiveBtn").onclick = async () => {
            try {
                await apiFetch("/api/admin/unarchive-user", { method: "POST", body: JSON.stringify({ email }) });
                await Promise.all([refreshUsers(), refreshActivityLog()]);
                closeModal(); renderCurrentTab();
            } catch (err) {
                setModalInlineStatus("unarchiveModalStatus", err.message || "Failed.", "error");
            }
        };
    }

    function promptBanEmail(email, name) {
        showModal(`
    <div class="modal-title">🚫 Ban email: ${escHtmlGlobal(name)}</div>
    <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;margin-bottom:14px;color:#7f1d1d;line-height:1.6;">
      ⚠️ For hacking attempts, illegal content, or serious abuse — harsher than suspension. This email can't log in or register again until unbanned, and any active session ends on its next request. Existing data is not deleted.
    </div>
    <div class="ip-form-field" style="width:100%;margin-bottom:14px;">
      <label>Reason *</label>
      <textarea id="banReason" rows="2" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;resize:none;box-sizing:border-box;" placeholder="e.g. Hacking attempt detected..."></textarea>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="confirmBanBtn">🚫 Ban Email</button>
    </div>
    <div id="banModalStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        document.getElementById("confirmBanBtn").onclick = async () => {
            const reason = document.getElementById("banReason")?.value.trim();
            if (!reason) { setModalInlineStatus("banModalStatus", "Please give a reason.", "error"); return; }
            const btn = document.getElementById("confirmBanBtn");
            btn.disabled = true; btn.textContent = "Banning...";
            try {
                await apiFetch("/api/admin/banned-emails", { method: "POST", body: JSON.stringify({ email, reason }) });
                adminData.bannedEmails = await fetchBannedEmails();
                await refreshActivityLog();
                closeModal(); renderCurrentTab();
            } catch (err) {
                btn.disabled = false; btn.textContent = "🚫 Ban Email";
                setModalInlineStatus("banModalStatus", err.message || "Failed.", "error");
            }
        };
    }

    function promptUnbanEmail(email) {
        showModal(`
    <div class="modal-title">✓ Unban email</div>
    <div class="modal-body"><span style="font-family:monospace;">${escHtmlGlobal(email)}</span> will be able to log in and register again immediately.</div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#059669;border-color:#059669;" id="confirmUnbanBtn">✓ Unban</button>
    </div>
    <div id="unbanModalStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        document.getElementById("confirmUnbanBtn").onclick = async () => {
            try {
                await apiFetch(`/api/admin/banned-emails?email=${encodeURIComponent(email)}`, { method: "DELETE" });
                adminData.bannedEmails = await fetchBannedEmails();
                await refreshActivityLog();
                closeModal(); renderCurrentTab();
            } catch (err) {
                setModalInlineStatus("unbanModalStatus", err.message || "Failed.", "error");
            }
        };
    }

    function promptRequestDeletion(email, name) {
        showModal(`
    <div class="modal-title">🗑 Request deletion: ${escHtmlGlobal(name)}</div>
    <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;margin-bottom:14px;color:#7f1d1d;line-height:1.6;">
      ⚠️ This does NOT delete anything yet. Every other admin must review and approve before this account can be deleted — track it in the Deletion Requests tab. Consider suspending the account in the meantime.
    </div>
    <div class="ip-form-field" style="width:100%;margin-bottom:14px;">
      <label>Reason *</label>
      <textarea id="deleteReqReason" rows="2" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;resize:none;box-sizing:border-box;" placeholder="e.g. Confirmed malicious activity, repeated policy violations..."></textarea>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="confirmDeleteReqBtn">Submit Request</button>
    </div>
    <div id="deleteReqModalStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        document.getElementById("confirmDeleteReqBtn").onclick = async () => {
            const reason = document.getElementById("deleteReqReason")?.value.trim();
            if (!reason) { setModalInlineStatus("deleteReqModalStatus", "Please give a reason.", "error"); return; }
            const btn = document.getElementById("confirmDeleteReqBtn");
            btn.disabled = true; btn.textContent = "Submitting...";
            try {
                const result = await apiFetch("/api/admin/deletion-requests", { method: "POST", body: JSON.stringify({ email, reason }) });
                await Promise.all([refreshDeletionBadge(), refreshActivityLog()]);
                closeModal();
                showInfoModal(result.message || "Deletion request submitted. Track it under the Deletion Requests tab.", "success");
            } catch (err) {
                btn.disabled = false; btn.textContent = "Submit Request";
                setModalInlineStatus("deleteReqModalStatus", err.message || "Failed.", "error");
            }
        };
    }

    function promptPromoteUser(email, name) {
        const safeName = escHtmlGlobal(name);
        showModal(`
    <div class="modal-title">⬆ Promote to Admin: ${safeName}</div>
    <div style="font-size:.82rem;background:#f5f3ff;border:1px solid #ddd6fe;border-radius:10px;padding:12px 14px;margin-bottom:14px;color:#5b21b6;line-height:1.6;">
      ⚠️ This grants <strong>${safeName}</strong> full administrator access — user management, topic deletion,
      email bans, and platform-wide data. This action cannot be undone from this panel (admins can't be demoted here).
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#7c3aed;border-color:#7c3aed;" id="confirmPromoteBtn">⬆ Confirm Promotion</button>
    </div>
    <div id="promoteUserModalStatus" style="display:none; margin-top:12px; font-size:0.8rem; padding:8px 12px; border-radius:8px;"></div>`);
        document.getElementById("confirmPromoteBtn").onclick = () => confirmPromoteUser(email, name);
    }

    async function confirmPromoteUser(email, name) {
        const btn = document.getElementById("confirmPromoteBtn");
        btn.disabled = true;
        btn.textContent = "Promoting...";
        try {
            await apiFetch("/api/admin/promote", {
                method: "POST",
                body: JSON.stringify({ email })
            });
            await Promise.all([refreshUsers(), refreshActivityLog()]);
            closeModal();
            renderUsers();
        } catch (err) {
            btn.disabled = false;
            btn.textContent = "⬆ Confirm Promotion";
            setModalInlineStatus("promoteUserModalStatus", "Promotion failed: " + (err.message || "Unknown error"), "error");
        }
    }

    function refreshFlaggedBadge() {
        const badge = document.getElementById("flaggedBadge");
        if (!badge) return;
        const count = (adminData.users || []).filter(u => u.flagged).length;
        if (count > 0) { badge.textContent = count; badge.style.display = "inline-block"; }
        else badge.style.display = "none";
    }
