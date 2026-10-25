/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    async function renderTopics() {
        document.getElementById("adminContent").innerHTML = `
    <style>
        .cr-card { background:#fff; border:1px solid #e2e8f0; border-radius:16px; padding:20px; box-shadow:0 1px 4px rgba(0,0,0,.04); }
        .cr-title { font-size:.95rem; font-weight:700; color:#111827; margin-bottom:3px; }
        .cr-hint  { font-size:.78rem; color:#6b7280; margin-bottom:14px; font-style:italic; }
        .cr-toolbar { display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin-bottom:14px; }
        .cr-search  { display:flex; align-items:center; gap:8px; padding:9px 13px; border-radius:10px;
                      background:#f8fafc; border:1px solid #e2e8f0; flex:1; min-width:220px; }
        .cr-search input { background:transparent; border:none; outline:none; font-size:.85rem;
                           color:#1e293b; width:100%; }
        .cr-filter-btn { font-size:.78rem; font-weight:700; padding:7px 14px; border-radius:999px;
                         border:1px solid #e2e8f0; background:#f8fafc; color:#475569; cursor:pointer;
                         transition:background .15s, color .15s; }
        .cr-filter-btn:hover  { background:#eff6ff; color:#1d4ed8; border-color:#bfdbfe; }
        .cr-filter-btn.active { background:#0093ad; color:#fff; border-color:#0093ad; }
        .cr-table { width:100%; min-width:640px; border-collapse:collapse; font-size:.83rem; }
        .cr-table th { text-align:left; padding:8px 10px; font-size:.68rem; text-transform:uppercase;
                       letter-spacing:.05em; color:#6b7280; border-bottom:2px solid #f1f5f9; font-weight:700; }
        .cr-table td { padding:10px 10px; border-bottom:1px solid #f3f4f6; color:#374151; vertical-align:middle; }
        .cr-table tr:last-child td { border-bottom:none; }
        .cr-table tr:hover td { background:#f8fafc; }
        #crModal { display:none; position:fixed; inset:0; background:rgba(15,23,42,.55);
                   z-index:2000; align-items:flex-start; justify-content:center; padding:24px; overflow-y:auto; }
        #crModal.open { display:flex; }
        .cr-modal-box { background:#fff; border-radius:20px; width:100%; max-width:880px;
                        box-shadow:0 24px 60px rgba(0,0,0,.2); display:flex; flex-direction:column;
                        max-height:92vh; overflow:hidden; margin:auto; }
        .cr-modal-head { display:flex; align-items:center; justify-content:space-between;
                         padding:18px 24px; border-bottom:1px solid #e2e8f0; flex-shrink:0; }
        .cr-modal-title { font-size:1rem; font-weight:800; color:#111827; }
        .cr-modal-sub   { font-size:.75rem; color:#6b7280; margin-top:2px; }
        .cr-modal-close { width:32px; height:32px; border-radius:8px; border:1px solid #e5e7eb;
                          background:#f8fafc; cursor:pointer; font-size:.9rem; display:flex;
                          align-items:center; justify-content:center; flex-shrink:0; }
        .cr-modal-close:hover { background:#fee2e2; color:#dc2626; border-color:#fca5a5; }
        .cr-modal-body { flex:1; overflow-y:auto; padding:0; display:flex; flex-direction:column; }
        .cr-modal-panels { display:flex; flex:1; min-height:0; }
        .cr-panel-left  { flex:1; min-width:0; padding:20px 24px; overflow-y:auto;
                          border-right:1px solid #f1f5f9; display:flex; flex-direction:column; gap:16px; }
        .cr-panel-right { width:280px; flex-shrink:0; padding:20px 18px; overflow-y:auto;
                          background:#fafafa; display:flex; flex-direction:column; gap:14px; }
        .cr-text-viewer { background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px;
                          padding:14px 16px; font-size:.82rem; line-height:1.75; color:#374151;
                          white-space:pre-wrap; word-break:break-word; max-height:420px; overflow-y:auto;
                          font-family:ui-monospace, "Cascadia Code", "Fira Code", monospace; }
        .cr-text-viewer::-webkit-scrollbar { width:6px; }
        .cr-text-viewer::-webkit-scrollbar-track { background:#f1f5f9; border-radius:99px; }
        .cr-text-viewer::-webkit-scrollbar-thumb { background:#cbd5e1; border-radius:99px; }
        .cr-doc-viewer { background:#fff; border:1px solid #e2e8f0; border-radius:10px; padding:20px 24px;
                         max-height:420px; overflow-y:auto; font-family:system-ui,-apple-system,"Segoe UI",sans-serif;
                         font-size:.88rem; line-height:1.7; color:#1e293b; word-break:break-word; }
        .cr-doc-viewer::-webkit-scrollbar { width:6px; }
        .cr-doc-viewer::-webkit-scrollbar-track { background:#f1f5f9; border-radius:99px; }
        .cr-doc-viewer::-webkit-scrollbar-thumb { background:#cbd5e1; border-radius:99px; }
        .crdoc-heading { font-size:.82rem; font-weight:800; text-transform:uppercase; letter-spacing:.06em;
                         color:#0f172a; border-left:3px solid #0093ad; padding:2px 0 2px 10px; margin:14px 0 6px; }
        .crdoc-heading:first-child { margin-top:0; }
        .crdoc-para { margin:0 0 8px; color:#374151; }
        .crdoc-bullet { display:flex; align-items:baseline; gap:8px; margin:3px 0; padding-left:8px; }
        .crdoc-bullet-dot { color:#0093ad; font-size:.9rem; flex-shrink:0; }
        .crdoc-numbered { margin:3px 0; padding-left:8px; color:#374151; }
        .crdoc-blank { height:10px; }
        .cr-highlight { background:#fef08a; border-radius:2px; }
        .cr-meta-row { display:flex; justify-content:space-between; align-items:flex-start;
                       gap:8px; font-size:.8rem; padding:6px 0; border-bottom:1px solid #f3f4f6; }
        .cr-meta-row:last-child { border-bottom:none; }
        .cr-meta-label { color:#6b7280; font-weight:600; flex-shrink:0; }
        .cr-meta-value { color:#111827; font-weight:500; text-align:right; word-break:break-all; }
        .cr-diagram-wrap { border:1px solid #e2e8f0; border-radius:10px; overflow:hidden; }
        .cr-diagram-wrap img { width:100%; height:auto; display:block; }
        .cr-diagram-label { font-size:.68rem; font-weight:700; text-transform:uppercase;
                            letter-spacing:.05em; color:#6b7280; margin-bottom:6px; }
        .cr-actions { display:flex; flex-direction:column; gap:8px; }
        .cr-actions .btn { font-size:.8rem; padding:9px 12px; justify-content:flex-start; gap:8px; }
        .cr-spinner { display:flex; align-items:center; justify-content:center; padding:40px;
                      flex-direction:column; gap:14px; color:#6b7280; font-size:.85rem; }
        .cr-spin-icon { width:36px; height:36px; border:4px solid #e2e8f0; border-top-color:#0093ad;
                        border-radius:50%; animation:cr-spin .8s linear infinite; }
        @keyframes cr-spin { to { transform:rotate(360deg); } }
        .cr-banner { font-size:.8rem; padding:9px 12px; border-radius:8px; }
        .cr-banner.warn  { background:#fef3c7; border:1px solid #fcd34d; color:#92400e; }
        .cr-banner.error { background:#fef2f2; border:1px solid #fecaca; color:#991b1b; }
        .cr-banner.info  { background:#eff6ff; border:1px solid #bfdbfe; color:#1e40af; }
        .cr-find-bar { display:flex; align-items:center; gap:8px; padding:8px 12px;
                       background:#f1f5f9; border-bottom:1px solid #e2e8f0; flex-shrink:0; }
        .cr-find-bar input { flex:1; padding:6px 10px; border:1px solid #e2e8f0; border-radius:8px;
                             font-size:.82rem; outline:none; }
        .cr-find-bar input:focus { border-color:#0093ad; }
        .cr-find-count { font-size:.72rem; color:#6b7280; white-space:nowrap; min-width:60px; }
        .cr-find-nav { display:flex; gap:4px; }
        .cr-find-nav button { padding:4px 10px; border-radius:6px; border:1px solid #e2e8f0;
                              background:#fff; cursor:pointer; font-size:.8rem; }
        .cr-find-nav button:hover { background:#eff6ff; border-color:#bfdbfe; }
        .cr-view-toggle { display:flex; gap:4px; padding:8px 12px; background:#fff;
                          border-bottom:1px solid #f1f5f9; flex-shrink:0; }
        .cr-view-toggle button { font-size:.76rem; font-weight:700; padding:6px 14px;
                                 border-radius:8px; border:1px solid #e2e8f0; background:#f8fafc;
                                 color:#475569; cursor:pointer; transition:background .15s, color .15s; }
        .cr-view-toggle button.active { background:#0093ad; color:#fff; border-color:#0093ad; }
        .cr-view-toggle button:hover:not(.active) { background:#eff6ff; color:#1d4ed8; }
        .cr-pdf-frame-wrap { flex:1; min-height:480px; display:flex; flex-direction:column; }
        .cr-pdf-frame-wrap iframe { flex:1; width:100%; border:1px solid #e2e8f0; border-radius:10px; background:#525659; }
        @media (max-width:800px) {
            .cr-modal-panels { flex-direction:column; }
            .cr-panel-right  { width:100%; border-top:1px solid #f1f5f9; }
        }
    </style>


    <div class="range-filter" id="topicsSubtabBar" style="margin-bottom:4px;">
      <button class="range-filter-btn topics-subtab-btn active" data-subtab="materials" onclick="setTopicsSubTab('materials')">📄 Uploaded Materials</button>
      <button class="range-filter-btn topics-subtab-btn" data-subtab="categories" onclick="setTopicsSubTab('categories')">🏷 Categories</button>
    </div>

    <div id="topicsMaterialsView">

    <div id="orphanTopicsCard"></div>


    <div class="admin-card">
        <div class="cr-title">📄 Uploaded Material Content Review</div>
        <div class="cr-hint">
            Read the full extracted text of any uploaded handout without downloading or executing the file.
            The raw file bytes never leave the server — only the already-extracted text and any embedded
            diagram image (pre-sanitised to PNG) are shown here. Use this to verify content before
            flagging or deleting.
        </div>

        <div class="cr-toolbar">
            <div class="cr-search">
                <span>🔎</span>
                <input type="text" id="crSearch" placeholder="Search topic, filename, uploader…" oninput="crApplyFilters()" />
            </div>
            <button class="cr-filter-btn active" data-cr-filter="all"    onclick="crSetFilter('all')">All</button>
            <button class="cr-filter-btn"        data-cr-filter="pdf"    onclick="crSetFilter('pdf')">PDF</button>
            <button class="cr-filter-btn"        data-cr-filter="docx"   onclick="crSetFilter('docx')">Word</button>
            <button class="cr-filter-btn"        data-cr-filter="txt"    onclick="crSetFilter('txt')">TXT/CSV</button>
            <button class="cr-filter-btn"        data-cr-filter="diagram" onclick="crSetFilter('diagram')">Has Diagram</button>
        </div>

        <div id="crTableWrap">
            <div class="cr-spinner"><div class="cr-spin-icon"></div>Loading materials…</div>
        </div>
    </div>


    <div class="admin-card">
      <div class="admin-card-title">📋 Content Policy Reminder</div>
      <div class="admin-card-hint">All uploaded material must comply with the platform's academic content policy.</div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;">
        ${[
            { icon: "✅", label: "Allowed", items: ["Textbooks and lecture notes", "Academic handouts and syllabi", "Research papers and study guides", "Subject-specific educational content"] },
            { icon: "🚫", label: "Not Allowed", items: ["Explicit, obscene, or adult content", "Personal information or doxxing material", "Hacking tools or malicious code", "Hate speech or discriminatory content"] }
        ].map(s => `
          <div style="padding:12px;border-radius:10px;border:1px solid ${s.icon === '✅' ? '#bbf7d0' : '#fecaca'};background:${s.icon === '✅' ? '#f0fdf4' : '#fef2f2'};">
            <div style="font-size:.8rem;font-weight:700;color:${s.icon === '✅' ? '#065f46' : '#991b1b'};margin-bottom:8px;">${s.icon} ${s.label}</div>
            ${s.items.map(it => `<div style="font-size:.78rem;color:#374151;padding:2px 0;">• ${it}</div>`).join("")}
          </div>`).join("")}
      </div>
    </div>


    <div id="crModal">
        <div class="cr-modal-box">
            <div class="cr-modal-head">
                <div>
                    <div class="cr-modal-title" id="crModalTitle">Material Review</div>
                    <div class="cr-modal-sub"  id="crModalSub">Loading…</div>
                </div>
                <button class="cr-modal-close" onclick="crCloseModal()">✕</button>
            </div>
            <div class="cr-view-toggle">
                <button id="crViewTextBtn" class="active" onclick="crSetViewMode('text')">📄 Text</button>
                <button id="crViewPdfBtn" onclick="crSetViewMode('pdf')">📑 PDF</button>
            </div>
            <div class="cr-find-bar">
                <input type="text" id="crFindInput" placeholder="Find in text…" oninput="crFind()" />
                <span class="cr-find-count" id="crFindCount"></span>
                <div class="cr-find-nav">
                    <button onclick="crFindStep(-1)">↑</button>
                    <button onclick="crFindStep(+1)">↓</button>
                </div>
            </div>
            <div class="cr-modal-body">
                <div class="cr-modal-panels">
                    <div class="cr-panel-left" id="crPanelLeft">
                        <div class="cr-spinner"><div class="cr-spin-icon"></div>Extracting text…</div>
                    </div>
                    <div class="cr-panel-right" id="crPanelRight">
                        <div class="cr-spinner"><div class="cr-spin-icon"></div></div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    </div>

    <div id="topicsCategoriesView" style="display:none;">
      <div class="admin-card">
        <div class="cr-title">🏷 Material Categories</div>
        <div class="cr-hint">
            These are the fixed subject categories the AI assigns to every uploaded handout
            (see "Material Categorization" on the Overview tab). Add, rename, or retire
            categories here — no code changes needed. Retiring a category hides it from future
            AI classification but leaves already-tagged materials untouched; deleting removes it
            from the classification list entirely.
        </div>
        <div class="ip-form" style="margin-bottom:16px;">
          <div class="ip-form-field">
            <label>New category name *</label>
            <input type="text" id="newCategoryName" placeholder="e.g. Computer Science" style="width:220px;" />
          </div>
          <div class="ip-form-field">
            <label>Description</label>
            <input type="text" id="newCategoryDesc" placeholder="Optional short description" style="width:280px;" />
          </div>
          <div class="ip-form-field" style="align-self:flex-end;">
            <button class="btn primary" style="padding:10px 18px;" id="addCategoryBtn">➕ Add Category</button>
          </div>
        </div>
        <div id="categoriesListWrap"><div class="empty-state">Loading categories…</div></div>
      </div>
    </div>`;

        document.getElementById("addCategoryBtn")?.addEventListener("click", doAddCategory);

        await crLoadMaterials();
        renderOrphanTopics();
    }

    function setTopicsSubTab(tab) {
        topicsSubTab = tab;
        document.querySelectorAll(".topics-subtab-btn").forEach(b => b.classList.toggle("active", b.dataset.subtab === tab));
        const materialsView = document.getElementById("topicsMaterialsView");
        const categoriesView = document.getElementById("topicsCategoriesView");
        if (materialsView) materialsView.style.display = tab === "materials" ? "" : "none";
        if (categoriesView) categoriesView.style.display = tab === "categories" ? "" : "none";
        if (tab === "categories") loadCategoriesSubtab();
    }

    async function loadCategoriesSubtab() {
        const wrap = document.getElementById("categoriesListWrap");
        if (!wrap) return;
        wrap.innerHTML = `<div class="empty-state">Loading categories…</div>`;
        try {
            categoriesData = await apiFetch("/api/admin/categories");
            renderCategoriesList();
        } catch (err) {
            wrap.innerHTML = `<div class="empty-state">Could not load categories: ${escHtmlGlobal(err.message || "Unknown error")}</div>`;
        }
    }

    function renderCategoriesList() {
        const wrap = document.getElementById("categoriesListWrap");
        if (!wrap) return;
        if (!categoriesData || categoriesData.length === 0) {
            wrap.innerHTML = `<div class="empty-state">No categories defined yet.</div>`;
            return;
        }
        wrap.innerHTML = `
            <div class="table-scroll"><table class="admin-table">
                <thead><tr><th>#</th><th>Name</th><th>Description</th><th>Status</th><th>Created</th><th>Actions</th></tr></thead>
                <tbody>
                ${categoriesData.map((c, i) => `
                    <tr class="${c.active === false ? 'row-blocked' : ''}">
                        <td>${i + 1}</td>
                        <td><strong>${escHtmlGlobal(c.name)}</strong></td>
                        <td style="font-size:.8rem;color:#6b7280;max-width:320px;">${escHtmlGlobal(c.description || "—")}</td>
                        <td>${c.active === false ? '<span class="badge-blocked">Inactive</span>' : '<span class="badge-active">Active</span>'}</td>
                        <td style="font-size:.78rem;color:#9ca3af;">${c.createdAt ? new Date(c.createdAt).toLocaleDateString() : "—"}</td>
                        <td style="white-space:nowrap;">
                            <button class="action-btn btn-view" data-cat-action="edit" data-id="${c.id}">✎ Edit</button>
                            ${c.active === false
                    ? `<button class="action-btn btn-unblock" data-cat-action="activate" data-id="${c.id}">✓ Reactivate</button>`
                    : `<button class="action-btn btn-block" data-cat-action="deactivate" data-id="${c.id}">⏸ Retire</button>`}
                            <button class="action-btn btn-delete" data-cat-action="delete" data-id="${c.id}">🗑 Delete</button>
                        </td>
                    </tr>`).join("")}
                </tbody>
            </table></div>`;

        wrap.querySelectorAll("button[data-cat-action]").forEach(btn => {
            const id = Number(btn.dataset.id);
            const cat = categoriesData.find(c => c.id === id);
            if (!cat) return;
            btn.addEventListener("click", () => {
                switch (btn.dataset.catAction) {
                    case "edit":       promptEditCategory(cat); break;
                    case "activate":   toggleCategoryActive(cat, true); break;
                    case "deactivate": toggleCategoryActive(cat, false); break;
                    case "delete":     promptDeleteCategory(cat); break;
                }
            });
        });
    }

    async function doAddCategory() {
        const nameInput = document.getElementById("newCategoryName");
        const descInput = document.getElementById("newCategoryDesc");
        const name = nameInput?.value.trim();
        const description = descInput?.value.trim();
        if (!name) { showInfoModal("Please enter a category name.", "error"); return; }
        const btn = document.getElementById("addCategoryBtn");
        if (btn) { btn.disabled = true; btn.textContent = "Adding..."; }
        try {
            await apiFetch("/api/admin/categories", {
                method: "POST",
                body: JSON.stringify({ name, description })
            });
            if (nameInput) nameInput.value = "";
            if (descInput) descInput.value = "";
            await loadCategoriesSubtab();
        } catch (err) {
            showInfoModal(err.message || "Could not add category — a category with that name may already exist.", "error");
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = "➕ Add Category"; }
        }
    }

    function promptEditCategory(cat) {
        if (!cat) return;
        showModal(`
    <div class="modal-title">✎ Edit category: ${escHtmlGlobal(cat.name)}</div>
    <div class="ip-form-field" style="width:100%;margin-bottom:12px;">
      <label>Name *</label>
      <input type="text" id="editCategoryName" value="${escHtmlGlobal(cat.name)}" style="width:100%;padding:9px 12px;border:1px solid #e2e8f0;border-radius:10px;font-size:.85rem;box-sizing:border-box;" />
    </div>
    <div class="ip-form-field" style="width:100%;margin-bottom:14px;">
      <label>Description</label>
      <textarea id="editCategoryDesc" rows="2" style="width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;resize:none;box-sizing:border-box;">${escHtmlGlobal(cat.description || "")}</textarea>
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" id="confirmEditCategoryBtn">Save Changes</button>
    </div>
    <div id="editCategoryStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        document.getElementById("confirmEditCategoryBtn").onclick = async () => {
            const name = document.getElementById("editCategoryName")?.value.trim();
            const description = document.getElementById("editCategoryDesc")?.value.trim();
            if (!name) { setModalInlineStatus("editCategoryStatus", "Name is required.", "error"); return; }
            const btn = document.getElementById("confirmEditCategoryBtn");
            btn.disabled = true; btn.textContent = "Saving...";
            try {
                await apiFetch(`/api/admin/categories/${cat.id}`, {
                    method: "PUT",
                    body: JSON.stringify({ name, description })
                });
                closeModal();
                await loadCategoriesSubtab();
            } catch (err) {
                btn.disabled = false; btn.textContent = "Save Changes";
                setModalInlineStatus("editCategoryStatus", err.message || "Could not save — name may already be in use.", "error");
            }
        };
    }

    async function toggleCategoryActive(cat, active) {
        if (!cat) return;
        try {
            await apiFetch(`/api/admin/categories/${cat.id}`, {
                method: "PUT",
                body: JSON.stringify({ active })
            });
            await loadCategoriesSubtab();
        } catch (err) {
            showInfoModal(err.message || "Could not update category status.", "error");
        }
    }

    function promptDeleteCategory(cat) {
        if (!cat) return;
        showModal(`
    <div class="modal-title">🗑 Delete category: "${escHtmlGlobal(cat.name)}"</div>
    <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;margin-bottom:14px;color:#7f1d1d;line-height:1.6;">
      ⚠️ This permanently removes the category from the AI's classification list. Materials already
      tagged with it keep their existing label, but the AI can never assign it again unless it's
      re-created. Consider "Retire" instead if you may want it back later.
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="confirmDeleteCategoryBtn">🗑 Delete</button>
    </div>
    <div id="deleteCategoryStatus" style="display:none;margin-top:12px;font-size:0.8rem;padding:8px 12px;border-radius:8px;"></div>`);
        document.getElementById("confirmDeleteCategoryBtn").onclick = async () => {
            const btn = document.getElementById("confirmDeleteCategoryBtn");
            btn.disabled = true; btn.textContent = "Deleting...";
            try {
                await apiFetch(`/api/admin/categories/${cat.id}`, { method: "DELETE" });
                closeModal();
                await loadCategoriesSubtab();
            } catch (err) {
                btn.disabled = false; btn.textContent = "🗑 Delete";
                setModalInlineStatus("deleteCategoryStatus", err.message || "Delete failed.", "error");
            }
        };
    }

    function renderOrphanTopics() {
        const el = document.getElementById("orphanTopicsCard");
        if (!el) return;

        const materialTopics = new Set(crAllMaterials.map(m => (m.topic || "").toLowerCase()));
        const orphans = (adminData.topics || []).filter(t => !materialTopics.has(t.toLowerCase()));

        if (orphans.length === 0) {
            el.innerHTML = "";
            return;
        }

        el.innerHTML = `
    <div class="admin-card">
      <div class="admin-card-title">📂 Topics With No Uploaded Material</div>
      <div class="admin-card-hint">
        These topics have quiz attempt history on record but no material currently uploaded for
        them (e.g. the original handout was removed while past attempts remain). They won't appear
        in the Content Review table below since there's nothing to read, but they can still be
        deleted from here.
      </div>
      <div class="table-scroll"><table class="admin-table">
        <thead><tr><th>#</th><th>Topic Name</th><th>Actions</th></tr></thead>
        <tbody>
          ${orphans.map((t, i) => `
            <tr>
              <td>${i + 1}</td>
              <td><strong>${escHtmlGlobal(t)}</strong></td>
              <td><button class="action-btn btn-delete" data-action="delete-topic" data-topic="${escHtmlGlobal(t)}">🗑 Delete Topic</button></td>
            </tr>`).join("")}
        </tbody>
      </table></div>
    </div>`;

        el.querySelectorAll("button[data-action='delete-topic']").forEach(btn => {
            btn.addEventListener("click", () => promptDeleteTopic(btn.dataset.topic || ""));
        });
    }

    function promptDeleteTopic(topic) {
        showModal(`
    <div class="modal-title">🗑 Delete topic: "${escHtmlGlobal(topic)}"</div>
    <div style="font-size:.82rem;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;padding:12px;margin-bottom:14px;color:#7f1d1d;line-height:1.6;">
      ⚠️ This permanently deletes all quiz questions, attempt history, and uploaded material for this topic. This cannot be undone.
    </div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="confirmTopicDeleteBtn">🗑 Delete</button>
    </div>
    <div id="deleteTopicModalStatus" style="display:none; margin-top:12px; font-size:0.8rem; padding:8px 12px; border-radius:8px;"></div>`);
        document.getElementById("confirmTopicDeleteBtn").onclick = async () => {
            document.getElementById("confirmTopicDeleteBtn").textContent = "Deleting...";
            document.getElementById("confirmTopicDeleteBtn").disabled = true;
            try {
                await apiFetch(`/api/topics/${encodeURIComponent(topic)}`, { method: "DELETE" });
                adminData.topics = adminData.topics.filter(t => t !== topic);
                closeModal();
                renderTopics();
            } catch (err) {
                document.getElementById("confirmTopicDeleteBtn").disabled = false;
                document.getElementById("confirmTopicDeleteBtn").textContent = "🗑 Delete";
                setModalInlineStatus("deleteTopicModalStatus", "Delete failed: " + (err.message || "Unknown error"), "error");
            }
        };
    }
