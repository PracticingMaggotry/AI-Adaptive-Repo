/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    function renderReports() {
        const s = adminData.stats;
        const blocked = adminData.bannedEmails || [];
        const suspended = (adminData.users || []).filter(u => u.archived).map(u => u.email);
        const flagged = adminData.flaggedUsers || [];
        const deletedEntries = (adminData.activityLog || []).filter(e => e.type === "delete");
        const mc = adminData.materialCategories || { categoryBreakdown: [], subCategoryBreakdown: [] };
        const now = new Date().toLocaleString();

        document.getElementById("adminContent").innerHTML = `
    <div id="printExclude" style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;justify-content:space-between;margin-bottom:4px;">
      <div style="display:flex;gap:10px;flex-wrap:wrap;">
        <button class="print-btn" onclick="window.print()">🖨️ Print / Save as PDF</button>
        <button class="print-btn" style="background:#7c3aed;" onclick="copyReportText()">📋 Copy Report Text</button>
        <a class="print-btn" style="background:#059669;text-decoration:none;" href="/api/admin/export/attempts.csv">⬇ Attempts CSV</a>
        <a class="print-btn" style="background:#059669;text-decoration:none;" href="/api/admin/export/attempt_questions.csv">⬇ Per-question CSV</a>
        <a class="print-btn" style="background:#059669;text-decoration:none;" href="/api/admin/export/question_exposures.csv">⬇ Exposures CSV</a>
        <a class="print-btn" style="background:#059669;text-decoration:none;" href="/api/admin/export/reliability.csv">⬇ Reliability CSV</a>
        <a class="print-btn" style="background:#7c3aed;text-decoration:none;" href="/api/admin/export/review_questions.csv?perHandout=10">⬇ Review sample CSV</a>
        <a class="print-btn" style="background:#7c3aed;text-decoration:none;" href="/api/admin/export/review_sources.txt?perHandout=10">⬇ Review sources TXT</a>
        <a class="print-btn" style="background:#059669;text-decoration:none;" href="/api/admin/export/sus.csv">⬇ SUS CSV</a>
      </div>
      <div class="range-filter" id="reportsRangeFilter"></div>
    </div>

    <div id="participantsCard" class="admin-card" style="margin-bottom:14px;">
      <div class="admin-card-title">🔑 Study Participants</div>
      <div class="admin-card-hint">Exports include only enrolled students, identified by code. Enrol after the students have registered.</div>
      <textarea id="enrollEmails" rows="3" placeholder="One email per line" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;box-sizing:border-box;"></textarea>
      <div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:8px;">
        <button class="print-btn" onclick="enrollParticipants()">➕ Enrol</button>
        <button class="print-btn" onclick="createStudyAccounts()">🆕 Create accounts</button>
        <a class="print-btn" style="background:#059669;text-decoration:none;" href="/api/admin/participants/mapping.csv">⬇ Identity key CSV</a>
        <button class="print-btn" style="background:#ef4444;" onclick="wipeParticipantKey()">🗑 Delete identity key</button>
      </div>
      <div id="enrollResult" style="font-size:.8rem;margin-top:8px;color:#374151;"></div>
      <div id="participantList" style="font-size:.8rem;margin-top:8px;"></div>
    </div>

    <div id="printTarget">
      <div class="admin-card">
        <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;margin-bottom:12px;">
          <div>
            <div style="font-size:1.2rem;font-weight:900;color:#111827;">📋 Platform Administration Report</div>
            <div style="font-size:.78rem;color:#6b7280;margin-top:2px;">Generated: ${now} · Administrator: ${document.getElementById("topbarName")?.textContent || "Admin"}</div>
            <div style="font-size:.78rem;color:#6b7280;margin-top:2px;">Material categorization range: <strong>${rangeLabel(currentCategoryRange)}</strong></div>
          </div>
          <div style="text-align:right;">
            <div style="font-size:.72rem;color:#9ca3af;font-weight:600;text-transform:uppercase;letter-spacing:.04em;">Adaptive Learning System</div>
            <div style="font-size:.72rem;color:#9ca3af;">Confidential — Admin Use Only</div>
          </div>
        </div>
        <hr style="border:none;border-top:2px solid #192a3e;margin-bottom:14px;">

        <div class="admin-card-title">1. Platform Statistics</div>
        <div class="table-scroll"><table class="admin-table" style="margin-bottom:16px;">
          <thead><tr><th>Metric</th><th>Value</th></tr></thead>
          <tbody>
            <tr><td>Total Registered Users</td><td><strong>${s.totalUsers ?? 0}</strong></td></tr>
            <tr><td>Total Topics</td><td><strong>${s.totalTopics ?? 0}</strong></td></tr>
            <tr><td>Total Questions</td><td><strong>${s.totalQuestions ?? 0}</strong></td></tr>
            <tr><td>Total Quiz Attempts</td><td><strong>${s.totalAttempts ?? 0}</strong></td></tr>
            <tr><td>Total Uploaded Materials</td><td><strong>${s.totalMaterials ?? 0}</strong></td></tr>
          </tbody>
        </table></div>

        <div class="admin-card-title">2. Material Categorization Breakdown <span style="font-size:.72rem;font-weight:600;color:#6b7280;">(${rangeLabel(currentCategoryRange)})</span></div>
        <div style="font-size:.78rem;color:#6b7280;margin-bottom:8px;font-style:italic;">
          Categories are assigned automatically by AI when material is uploaded, based on a fixed set of
          curricular subject categories. Top category: <strong>${mc.topCategory || "—"}</strong> · Top sub-category: <strong>${mc.topSubCategory || "—"}</strong>
        </div>
        <div class="table-scroll"><table class="admin-table" style="margin-bottom:12px;">
          <thead><tr><th>#</th><th>Category</th><th>Materials</th><th>Share</th></tr></thead>
          <tbody>
            ${(mc.categoryBreakdown || []).filter(c => c.count > 0).length === 0
            ? `<tr><td colspan="4" class="empty-state">No categorized materials yet.</td></tr>`
            : (mc.categoryBreakdown || []).filter(c => c.count > 0).map((c, i) => {
                const pct = mc.totalMaterials ? Math.round((c.count / mc.totalMaterials) * 100) : 0;
                return `<tr>
                <td>${i + 1}</td>
                <td>${escHtmlGlobal(c.category)}</td>
                <td><strong>${c.count}</strong></td>
                <td>${pct}%</td>
              </tr>`;
            }).join("")}
          </tbody>
        </table></div>
        <div class="table-scroll"><table class="admin-table" style="margin-bottom:16px;">
          <thead><tr><th>#</th><th>Sub-Category</th><th>Materials</th><th>Share</th></tr></thead>
          <tbody>
            ${(mc.subCategoryBreakdown || []).length === 0
            ? `<tr><td colspan="4" class="empty-state">No sub-categories detected yet.</td></tr>`
            : (mc.subCategoryBreakdown || []).map((c, i) => {
                const pct = mc.totalMaterials ? Math.round((c.count / mc.totalMaterials) * 100) : 0;
                return `<tr>
                <td>${i + 1}</td>
                <td>${escHtmlGlobal(c.subCategory)}</td>
                <td><strong>${c.count}</strong></td>
                <td>${pct}%</td>
              </tr>`;
            }).join("")}
          </tbody>
        </table></div>

        <div class="admin-card-title">3. Security & Moderation Summary</div>
        <div class="table-scroll"><table class="admin-table" style="margin-bottom:16px;">
          <thead>
            <tr>
              <th>Security Item</th>
              <th>Count</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Banned Emails</td>
              <td><strong>${blocked.length}</strong></td>
              <td>${blocked.map(b => escHtmlGlobal(b.email)).join(", ") || "None"}</td>
            </tr>

            <tr>
              <td>Suspended Accounts</td>
              <td><strong>${suspended.length}</strong></td>
              <td>${suspended.map(e => escHtmlGlobal(e)).join(", ") || "None"}</td>
            </tr>

            <tr>
              <td>Flagged Users</td>
              <td><strong>${flagged.length}</strong></td>
              <td>${flagged.join(", ") || "None"}</td>
            </tr>

            <tr>
              <td>Deleted Accounts</td>
              <td><strong>${deletedEntries.length}</strong></td>
              <td>${deletedEntries.length ? "See deletion log below." : "None"}</td>
            </tr>
          </tbody>
        </table></div>

        ${blocked.length > 0 ? `
        <div class="admin-card-title">4. Banned Email Log</div>
        <div class="table-scroll"><table class="admin-table" style="margin-bottom:16px;">
          <thead>
            <tr>
              <th>Email</th>
              <th>Banned By</th>
              <th>Reason</th>
              <th>Date Banned</th>
            </tr>
          </thead>
          <tbody>
            ${blocked.map(b => `
              <tr>
                <td style="font-family:monospace;">${escHtmlGlobal(b.email)}</td>
                <td>${escHtmlGlobal(b.bannedBy || "—")}</td>
                <td>${escHtmlGlobal(b.reason)}</td>
                <td>${b.bannedAt ? new Date(b.bannedAt).toLocaleDateString() : "—"}</td>
              </tr>
            `).join("")}
          </tbody>
        </table></div>
        ` : ""}

        ${deletedEntries.length > 0 ? `
        <div class="admin-card-title">5. Account Deletion Log</div>
        <div class="table-scroll"><table class="admin-table" style="margin-bottom:16px;">
          <thead>
            <tr>
              <th>Action</th>
              <th>Reason</th>
              <th>Performed By</th>
              <th>Date</th>
            </tr>
          </thead>
          <tbody>
            ${deletedEntries.map(e => `
              <tr>
                <td>${escHtmlGlobal(e.message)}</td>
                <td>${escHtmlGlobal(e.detail || "—")}</td>
                <td>${escHtmlGlobal(e.performedBy || "—")}</td>
                <td>${e.time ? new Date(e.time).toLocaleDateString() : "—"}</td>
              </tr>
            `).join("")}
          </tbody>
        </table></div>
        ` : ""}

        <div class="admin-card-title">6. Registered Users</div>
        <div class="table-scroll"><table class="admin-table" style="margin-bottom:16px;">
          <thead><tr><th>#</th><th>Name</th><th>Email</th><th>Role</th><th>Status</th></tr></thead>
          <tbody>
            ${adminData.users.map((u, i) => {
            const isBlocked = blocked.some(b => (b.email || "").toLowerCase() === (u.email || "").toLowerCase());
            const isSuspended = !!u.archived;
            const isFlagged = flagged.includes(u.email);
            return `<tr>
                <td>${i+1}</td>
                <td>${escHtmlGlobal(u.fullName)}</td>
                <td style="font-family:monospace;font-size:.8rem;">${escHtmlGlobal(u.email)}</td>
                <td>${u.isAdmin ? "Admin" : "Student"}</td>
                <td>${isBlocked ? "Email Banned" : isSuspended ? "Suspended" : isFlagged ? "Flagged" : "Active"}</td>
              </tr>`;
        }).join("")}
          </tbody>
        </table></div>

        <div class="admin-card-title">7. Active Topics</div>
        <div style="font-size:.85rem;color:#374151;line-height:1.8;">
          ${adminData.topics.length === 0 ? "No topics found." : adminData.topics.map((t, i) => `${i+1}. ${escHtmlGlobal(t)}`).join(" &nbsp;|&nbsp; ")}
        </div>

        <hr style="border:none;border-top:1px solid #e2e8f0;margin:16px 0;">
        <div style="font-size:.72rem;color:#9ca3af;text-align:center;">
          Adaptive Learning System — Admin Report — ${now} — Confidential
        </div>
      </div>
    </div>`;

        renderRangeFilterInto("reportsRangeFilter");
        loadParticipants();
    }

    async function loadParticipants() {
        const el = document.getElementById("participantList");
        if (!el) return;
        try {
            const r = await apiFetch("/api/admin/participants");
            const list = r.participants || [];
            el.textContent = list.length ? list.length + " enrolled: " + list.map(p => p.code + (p.consented ? " ✓" : " ✗")).join(", ") : "No participants enrolled yet.";
        } catch (e) { el.textContent = "Could not load participants."; }
    }

    async function createStudyAccounts() {
        const n = Number(prompt("How many study accounts?", "10"));
        if (!n) return;
        try {
            const r = await apiFetch("/api/admin/participants/create-accounts", { method: "POST", body: JSON.stringify({ count: n }) });
            const csv = "participant_code,email,password\r\n" + r.accounts.map(a => [a.code, a.email, a.password].join(",")).join("\r\n");
            const link = document.createElement("a");
            link.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
            link.download = "study_accounts.csv";
            link.click();
            loadParticipants();
        } catch (e) { showInfoModal(e.message || "Failed.", "error"); }
    }

    async function enrollParticipants() {
        const emails = document.getElementById("enrollEmails").value.split(/[\s,;]+/).filter(Boolean);
        const out = document.getElementById("enrollResult");
        try {
            const r = await apiFetch("/api/admin/participants", { method: "POST", body: JSON.stringify({ emails }) });
            out.textContent = "Enrolled " + r.assigned + "." + (r.skipped.length ? " Skipped: " + r.skipped.join("; ") : "");
            document.getElementById("enrollEmails").value = "";
            loadParticipants();
        } catch (e) { out.textContent = e.message || "Failed."; }
    }

    async function wipeParticipantKey() {
        if (!confirm("Permanently delete the code-to-identity key? Download the Identity key CSV first if you need it.")) return;
        try {
            await apiFetch("/api/admin/participants?confirm=DELETE", { method: "DELETE" });
            loadParticipants();
        } catch (e) { showInfoModal(e.message || "Failed.", "error"); }
    }

    function rangeLabel(range) {
        return range === "week" ? "This Week (last 7 days)" : range === "month" ? "This Month (last 30 days)" : "All Time (universal timeline)";
    }

    function copyReportText() {
        const el = document.getElementById("printTarget");
        const text = el ? el.innerText : "";
        navigator.clipboard.writeText(text).then(() => showInfoModal("Report text copied to clipboard.", "success")).catch(() => showInfoModal("Copy failed — use Ctrl+A on the report to select manually.", "error"));
    }

    async function crLoadMaterials() {
        try {
            const resp = await apiFetch("/api/admin/materials");
            crAllMaterials = resp.materials || [];
            crApplyFilters();
        } catch (err) {
            document.getElementById("crTableWrap").innerHTML =
                `<div class="cr-banner error">Could not load materials: ${escHtmlGlobal(err.message)}</div>`;
        }
    }

    function crSetFilter(filter) {
        crActiveFilter = filter;
        document.querySelectorAll("[data-cr-filter]").forEach(btn => {
            btn.classList.toggle("active", btn.dataset.crFilter === filter);
        });
        crApplyFilters();
    }

    function crApplyFilters() {
        const q = (document.getElementById("crSearch")?.value || "").toLowerCase();
        let filtered = crAllMaterials.filter(m => {
            if (q) {
                const haystack = [m.topic, m.originalFilename, m.uploadedBy,
                    m.primaryCategory, m.subCategory, m.previewSnippet].join(" ").toLowerCase();
                if (!haystack.includes(q)) return false;
            }
            const fn = (m.originalFilename || "").toLowerCase();
            const ct = (m.contentType || "").toLowerCase();
            if (crActiveFilter === "pdf")     return fn.endsWith(".pdf") || ct.includes("pdf");
            if (crActiveFilter === "docx")    return fn.endsWith(".docx") || fn.endsWith(".doc") || ct.includes("word");
            if (crActiveFilter === "txt")     return fn.endsWith(".txt") || fn.endsWith(".csv") || ct.includes("text");
            if (crActiveFilter === "diagram") return m.hasDiagram;
            return true; // "all"
        });

        const wrap = document.getElementById("crTableWrap");
        if (!wrap) return;

        if (filtered.length === 0) {
            wrap.innerHTML = `<div class="empty-state">No materials match the current filter.</div>`;
            return;
        }

        wrap.innerHTML = `
            <div class="table-scroll"><table class="cr-table">
                <thead><tr>
                    <th>#</th><th>Topic</th><th>Filename</th><th>Uploader</th>
                    <th>Category</th><th>Uploaded</th><th>Size</th><th>Actions</th>
                </tr></thead>
                <tbody>
                ${filtered.map((m, i) => {
            const fn = (m.originalFilename || "").toLowerCase();
            const ct = (m.contentType || "").toLowerCase();
            const typeIcon = fn.endsWith(".pdf") || ct.includes("pdf") ? "📕"
                : fn.endsWith(".docx") || fn.endsWith(".doc") ? "📘"
                    : "📄";
            const diagBadge = m.hasDiagram
                ? `<span style="font-size:.65rem;font-weight:700;padding:1px 6px;border-radius:99px;background:#ede9fe;color:#5b21b6;margin-left:4px;">🖼 diagram</span>`
                : "";
            const sizeStr = m.sizeBytes
                ? (m.sizeBytes < 1024*1024 ? Math.round(m.sizeBytes/1024)+"KB" : (m.sizeBytes/(1024*1024)).toFixed(1)+"MB")
                : "—";
            const dateStr = m.uploadedAt ? m.uploadedAt.substring(0,10) : "—";
            return `<tr>
                        <td>${i+1}</td>
                        <td><strong>${escHtmlGlobal(m.topic || "—")}</strong></td>
                        <td style="font-size:.78rem;">${typeIcon} ${escHtmlGlobal(m.originalFilename || "—")}${diagBadge}</td>
                        <td style="font-size:.78rem;color:#6b7280;">${escHtmlGlobal(m.uploadedBy || "—")}</td>
                        <td style="font-size:.75rem;">${escHtmlGlobal(m.primaryCategory || "—")}
                            ${m.subCategory ? `<div style="color:#9ca3af;">${escHtmlGlobal(m.subCategory)}</div>` : ""}
                        </td>
                        <td style="font-size:.75rem;color:#6b7280;">${dateStr}</td>
                        <td style="font-size:.75rem;color:#6b7280;">${sizeStr}</td>
                        <td style="white-space:nowrap;">
                            <button class="action-btn btn-view" onclick="crOpenReview(${m.id})">📖 Read</button>
                            <button class="action-btn btn-flag" data-action="flag-owner" data-email="${escHtmlGlobal(m.uploadedBy||'')}">⚑ Flag Owner</button>
                            <button class="action-btn btn-delete" data-action="delete-topic" data-topic="${escHtmlGlobal(m.topic||'')}">🗑 Delete Topic</button>
                        </td>
                    </tr>`;
        }).join("")}
                </tbody>
            </table></div>
            <div style="font-size:.72rem;color:#9ca3af;margin-top:8px;">
                Showing ${filtered.length} of ${crAllMaterials.length} material${crAllMaterials.length !== 1 ? "s" : ""}.
            </div>`;

        wrap.querySelectorAll("button[data-action='flag-owner']").forEach(btn => {
            btn.addEventListener("click", () => flagUser(btn.dataset.email || "", btn.dataset.email || ""));
        });
        wrap.querySelectorAll("button[data-action='delete-topic']").forEach(btn => {
            btn.addEventListener("click", () => promptDeleteTopic(btn.dataset.topic || ""));
        });
    }

    async function crOpenReview(materialId) {
        crCurrentMaterialId = materialId;
        crFindMatches = [];
        crFindIdx = 0;
        crViewMode = 'text';
        crLastReviewData = null;
        if (crPdfObjectUrl) { URL.revokeObjectURL(crPdfObjectUrl); crPdfObjectUrl = null; }

        const modal = document.getElementById("crModal");
        document.getElementById("crModalTitle").textContent = "Loading…";
        document.getElementById("crModalSub").textContent = "Extracting text server-side…";
        document.getElementById("crPanelLeft").innerHTML =
            `<div class="cr-spinner"><div class="cr-spin-icon"></div>Extracting text from stored file…</div>`;
        document.getElementById("crPanelRight").innerHTML =
            `<div class="cr-spinner"><div class="cr-spin-icon"></div></div>`;
        document.getElementById("crFindInput").value = "";
        document.getElementById("crFindCount").textContent = "";
        document.getElementById("crViewTextBtn").classList.add("active");
        document.getElementById("crViewPdfBtn").classList.remove("active");
        document.querySelector(".cr-find-bar").style.display = "flex";
        modal.classList.add("open");
        document.body.style.overflow = "hidden";

        try {
            const data = await apiFetch(`/api/admin/materials/${materialId}/content`);
            crLastReviewData = data;
            crRenderReviewModal(data);
        } catch (err) {
            document.getElementById("crPanelLeft").innerHTML =
                `<div class="cr-banner error">Could not load content: ${escHtmlGlobal(err.message)}</div>`;
            document.getElementById("crPanelRight").innerHTML = "";
        }
    }

    function buildFormattedHtml(blocks) {
        if (!blocks || blocks.length === 0) return "";

        const collapsed = [];
        let lastWasBlank = false;
        for (const b of blocks) {
            if (b.kind === "BLANK") {
                if (!lastWasBlank) collapsed.push(b);
                lastWasBlank = true;
            } else {
                collapsed.push(b);
                lastWasBlank = false;
            }
        }

        let html = "";
        for (const b of collapsed) {
            const t = escHtmlGlobal(b.text || "");
            switch (b.kind) {
                case "HEADING":
                    html += `<div class="crdoc-heading">${t}</div>`;
                    break;
                case "BULLET":
                    html += `<div class="crdoc-bullet"><span class="crdoc-bullet-dot">•</span><span>${t}</span></div>`;
                    break;
                case "NUMBERED":
                    html += `<div class="crdoc-numbered">${t}</div>`;
                    break;
                case "BLANK":
                    html += `<div class="crdoc-blank"></div>`;
                    break;
                default: // PARAGRAPH
                    html += `<div class="crdoc-para">${t}</div>`;
            }
        }
        return html;
    }

    function crSetViewMode(mode) {
        if (mode === crViewMode) return;
        crViewMode = mode;
        document.getElementById('crViewTextBtn').classList.toggle('active', mode === 'text');
        document.getElementById('crViewPdfBtn').classList.toggle('active', mode === 'pdf');
        document.querySelector('.cr-find-bar').style.display = mode === 'text' ? 'flex' : 'none';

        if (mode === 'pdf') {
            crLoadPdfView();
        } else if (crLastReviewData) {
            crRenderReviewModal(crLastReviewData,  true);
        }
    }

    async function crLoadPdfView() {
        const left = document.getElementById('crPanelLeft');
        left.innerHTML = `<div class="cr-spinner"><div class="cr-spin-icon"></div>Rendering PDF…</div>`;

        try {
            const resp = await fetch(`${API_BASE}/api/admin/materials/${crCurrentMaterialId}/content.pdf`, {
                credentials: "include"
            });
            if (!resp.ok) throw new Error(`Server returned ${resp.status}`);
            const blob = await resp.blob();

            if (crPdfObjectUrl) URL.revokeObjectURL(crPdfObjectUrl);
            crPdfObjectUrl = URL.createObjectURL(blob);

            left.innerHTML = `
                <div class="cr-pdf-frame-wrap">
                    <div class="cr-diagram-label" style="margin-bottom:6px;">
                        Re-typeset PDF — generated server-side from extracted text only
                    </div>
                    <iframe src="${crPdfObjectUrl}" title="Material content as PDF"></iframe>
                </div>`;
        } catch (err) {
            left.innerHTML = `<div class="cr-banner error">Could not render PDF: ${escHtmlGlobal(err.message)}</div>`;
        }
    }

    function crRenderReviewModal(data, skipRightPanel) {
        const fn = (data.originalFilename || "").toLowerCase();
        const ct = (data.contentType || "").toLowerCase();
        const typeIcon = fn.endsWith(".pdf") || ct.includes("pdf") ? "📕"
            : fn.endsWith(".docx") || fn.endsWith(".doc") ? "📘" : "📄";

        document.getElementById("crModalTitle").textContent =
            `${typeIcon} ${data.originalFilename || "Unknown file"}`;
        document.getElementById("crModalSub").textContent =
            `Topic: ${data.topic || "—"} · Uploaded by: ${data.uploadedBy || "—"} · ${data.uploadedAt ? data.uploadedAt.substring(0,10) : ""}`;

        const fullText = data.fullText || "";
        const blocks   = Array.isArray(data.formattedBlocks) ? data.formattedBlocks : [];
        const left = document.getElementById("crPanelLeft");

        let warningBanner = "";
        if (!data.fileStillExists) {
            warningBanner = `<div class="cr-banner warn" style="margin-bottom:12px;">
                ⚠️ The original file no longer exists on disk. The text below is from the preview stored at upload time and may be truncated.
            </div>`;
        }

        if (!fullText || fullText.trim().length === 0) {
            left.innerHTML = warningBanner + `<div class="cr-banner warn">
                No readable text was extracted from this file. It may be a scanned image-only PDF with no embedded text layer, or an unsupported format.
            </div>`;
        } else {
            const charCount = fullText.length;
            const wordCount = fullText.trim().split(/\s+/).filter(Boolean).length;

            let contentHtml;
            if (blocks.length > 0) {
                contentHtml = buildFormattedHtml(blocks);
            } else {
                contentHtml = `<div class="cr-text-viewer" id="crTextViewer">${escHtmlGlobal(fullText)}</div>`;
            }

            left.innerHTML = `${warningBanner}
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;flex-wrap:wrap;gap:6px;">
                    <div style="font-size:.72rem;font-weight:700;text-transform:uppercase;letter-spacing:.05em;color:#6b7280;">
                        Document Content
                    </div>
                    <div style="font-size:.7rem;color:#9ca3af;">
                        ${wordCount.toLocaleString()} words · ${charCount.toLocaleString()} chars
                    </div>
                </div>
                <div class="cr-doc-viewer" id="crTextViewer">${contentHtml}</div>`;
        }

        renderMathOnly(left);

        if (skipRightPanel) return;

        const sizeStr = data.sizeBytes
            ? (data.sizeBytes < 1024*1024 ? Math.round(data.sizeBytes/1024)+" KB" : (data.sizeBytes/(1024*1024)).toFixed(1)+" MB")
            : "—";

        const metaRows = [
            { label: "Topic",     value: data.topic },
            { label: "File",      value: data.originalFilename },
            { label: "Type",      value: data.contentType || "Unknown" },
            { label: "Size",      value: sizeStr },
            { label: "Uploader",  value: data.uploadedBy },
            { label: "Uploaded",  value: data.uploadedAt ? data.uploadedAt.substring(0,10) : "—" },
            { label: "Category",  value: data.primaryCategory || "—" },
            { label: "Sub-topic", value: data.subCategory || "—" },
        ].map(r => `
            <div class="cr-meta-row">
                <span class="cr-meta-label">${r.label}</span>
                <span class="cr-meta-value">${escHtmlGlobal(r.value || "—")}</span>
            </div>`).join("");

        const summaryHtml = data.topicSummary
            ? `<div style="margin-top:2px;">
                <div class="cr-diagram-label">AI Summary</div>
                <div style="font-size:.8rem;color:#374151;line-height:1.6;background:#f0f9ff;border:1px solid #bae6fd;border-radius:8px;padding:10px 12px;">
                    ${escHtmlGlobal(data.topicSummary)}
                </div>
               </div>`
            : "";

        const diagramHtml = data.diagramImageBase64
            ? `<div>
                <div class="cr-diagram-label">Embedded Diagram (PNG, extracted at upload)</div>
                <div class="cr-diagram-wrap">
                    <img src="data:image/png;base64,${data.diagramImageBase64}" alt="Embedded diagram from uploaded material" />
                </div>
                <div style="font-size:.65rem;color:#9ca3af;margin-top:4px;line-height:1.4;">
                    This image was extracted from the PDF and sanitised to PNG at upload time.
                    It is served here as base64 — it is not a link to the raw file.
                </div>
               </div>`
            : "";

        const right = document.getElementById("crPanelRight");
        right.innerHTML = `
            <div>
                <div class="cr-diagram-label">Metadata</div>
                ${metaRows}
            </div>
            ${summaryHtml}
            ${diagramHtml}
            <div>
                <div class="cr-diagram-label">Moderation Actions</div>
                <div class="cr-actions">
                    <button class="btn primary cr-actions" id="crFlagUploaderBtn" style="width:100%;display:flex;align-items:center;gap:7px;justify-content:flex-start;font-size:.8rem;padding:9px 12px;">
                        ⚑ Flag this uploader for review
                    </button>
                    <button class="btn" id="crBlockUploaderIpBtn" style="width:100%;display:flex;align-items:center;gap:7px;justify-content:flex-start;font-size:.8rem;padding:9px 12px;background:#fff7ed;border-color:#fed7aa;color:#c2410c;">
                        ⏸ Suspend this uploader
                    </button>
                    <button class="btn" id="crDeleteTopicBtn" style="width:100%;display:flex;align-items:center;gap:7px;justify-content:flex-start;font-size:.8rem;padding:9px 12px;background:#fee2e2;border-color:#fca5a5;color:#dc2626;">
                        🗑 Delete this topic & material
                    </button>
                </div>
                <div style="font-size:.68rem;color:#9ca3af;margin-top:10px;line-height:1.5;">
                    Deleting a topic removes it for every student platform-wide (admin moderation action).
                    Flagging only marks the account for review; suspending blocks login until restored.
                </div>
            </div>`;

        const uploaderEmail = data.uploadedBy || "";
        const topicName = data.topic || "";
        document.getElementById("crFlagUploaderBtn").onclick = () => { crCloseModal(); flagUser(uploaderEmail, uploaderEmail); };
        document.getElementById("crBlockUploaderIpBtn").onclick = () => { crCloseModal(); promptArchiveUser(uploaderEmail, uploaderEmail); };
        document.getElementById("crDeleteTopicBtn").onclick = () => { crCloseModal(); promptDeleteTopic(topicName); };
    }

    function crCloseModal() {
        document.getElementById("crModal").classList.remove("open");
        document.body.style.overflow = "";
        crFindMatches = [];
        crFindIdx = 0;
        if (crPdfObjectUrl) { URL.revokeObjectURL(crPdfObjectUrl); crPdfObjectUrl = null; }
        crLastReviewData = null;
    }

    function crFind() {
        const viewer = document.getElementById("crTextViewer");
        const countEl = document.getElementById("crFindCount");
        if (!viewer) return;

        const term = (document.getElementById("crFindInput")?.value || "").trim();

        if (!viewer.dataset.rawHtml) {
            viewer.dataset.rawHtml = viewer.innerHTML;
        }

        if (!term) {
            viewer.innerHTML = viewer.dataset.rawHtml;
            crFindMatches = [];
            if (countEl) countEl.textContent = "";
            return;
        }

        viewer.innerHTML = viewer.dataset.rawHtml;

        const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
        const regex = new RegExp(escaped, "gi");
        let count = 0;

        function highlightTextNode(node) {
            const text = node.nodeValue;
            if (!regex.test(text)) { regex.lastIndex = 0; return; }
            regex.lastIndex = 0;
            const frag = document.createDocumentFragment();
            let lastIdx = 0, match;
            while ((match = regex.exec(text)) !== null) {
                if (match.index > lastIdx) frag.appendChild(document.createTextNode(text.slice(lastIdx, match.index)));
                count++;
                const mark = document.createElement("mark");
                mark.className = "cr-highlight";
                mark.dataset.crMatch = count;
                mark.textContent = match[0];
                frag.appendChild(mark);
                lastIdx = regex.lastIndex;
            }
            if (lastIdx < text.length) frag.appendChild(document.createTextNode(text.slice(lastIdx)));
            node.parentNode.replaceChild(frag, node);
        }

        const walker = document.createTreeWalker(viewer, NodeFilter.SHOW_TEXT, null);
        const textNodes = [];
        let n;
        while ((n = walker.nextNode())) textNodes.push(n);
        textNodes.forEach(highlightTextNode);

        crFindMatches = Array.from(viewer.querySelectorAll("[data-cr-match]"));
        crFindIdx = 0;
        if (countEl) countEl.textContent = count > 0 ? `${count} match${count !== 1 ? "es" : ""}` : "No matches";
        if (crFindMatches.length > 0) crScrollToMatch(0);
    }

    function crFindStep(dir) {
        if (!crFindMatches.length) return;
        crFindIdx = (crFindIdx + dir + crFindMatches.length) % crFindMatches.length;
        crScrollToMatch(crFindIdx);
        const countEl = document.getElementById("crFindCount");
        if (countEl) {
            const total = crFindMatches.length;
            countEl.textContent = `${crFindIdx + 1} / ${total} match${total !== 1 ? "es" : ""}`;
        }
    }

    function crScrollToMatch(idx) {
        if (!crFindMatches[idx]) return;
        crFindMatches.forEach((el, i) => {
            el.style.background = i === idx ? "#f59e0b" : "#fef08a";
            el.style.outline = i === idx ? "2px solid #d97706" : "";
        });
        crFindMatches[idx].scrollIntoView({ behavior: "smooth", block: "center" });
    }
