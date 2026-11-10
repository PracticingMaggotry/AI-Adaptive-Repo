/* Do not add top-level let/const declarations here; shared state lives in ../admin.js. */

    function renderAiTesting() {
        // The five adaptive demand levels the ability model assigns per question (AiPolicy.DEMAND_NAMES on the server).
        const AIT_LEVELS = ["Remember", "Understand", "Apply", "Analyze", "Evaluate"];
        const AIT_DEFAULT_LEVEL = 3;
        const aitTopicLevels = {};   // topic -> level it was last generated at
        const aitLevelLabel = n => `L${n} ${AIT_LEVELS[n - 1] || ""}`.trim();
        const aitLevelOptions = selected => AIT_LEVELS.map((name, i) =>
            `<option value="${i + 1}"${i + 1 === selected ? " selected" : ""}>L${i + 1} · ${name}</option>`).join("");

        document.getElementById("adminContent").innerHTML = `
    <style>

        .ait-card { background:#fff; border:1px solid #e2e8f0; border-radius:16px; padding:20px; box-shadow:0 1px 4px rgba(0,0,0,0.04); }
        .ait-title { font-size:0.95rem; font-weight:700; color:#111827; margin-bottom:3px; }
        .ait-hint  { font-size:0.78rem; color:#6b7280; margin-bottom:16px; font-style:italic; }


        .ait-upload-zone {
            border:2px dashed #cbd5e1; border-radius:10px; padding:22px 16px; text-align:center;
            cursor:pointer; background:#f8fafc; transition:border-color 0.2s, background 0.2s;
        }
        .ait-upload-zone:hover, .ait-upload-zone.dragover { border-color:#3b82f6; background:#eff6ff; }
        .ait-upload-zone-icon { font-size:1.5rem; margin-bottom:6px; }
        .ait-upload-zone-text { font-size:0.85rem; font-weight:600; color:#374151; }
        .ait-upload-zone-sub  { font-size:0.72rem; color:#9ca3af; margin-top:3px; }

        .ait-field label { display:block; font-size:0.78rem; font-weight:700; color:#374151; margin-bottom:5px; }
        .ait-field input[type=text] {
            width:100%; padding:9px 12px; border:1px solid #e2e8f0; border-radius:10px;
            font-size:0.85rem; color:#111827; outline:none; box-sizing:border-box; transition:border-color 0.15s;
        }
        .ait-field input:focus { border-color:#0093ad; }
        .ait-field select {
            width:100%; padding:9px 12px; border:1px solid #e2e8f0; border-radius:10px;
            font-size:0.85rem; color:#111827; outline:none; background:#f8fafc; box-sizing:border-box;
        }
        .ait-row-level {
            padding:6px 8px; border:1px solid #e2e8f0; border-radius:8px;
            font-size:0.78rem; color:#111827; background:#f8fafc;
        }
        .ait-field-note { font-size:0.72rem; color:#9ca3af; margin-top:4px; line-height:1.4; }


        .ait-status { font-size:0.8rem; padding:9px 12px; border-radius:8px; margin-top:10px; display:none; }
        .ait-status.info    { background:#eff6ff; border:1px solid #bfdbfe; color:#1e40af; display:block; }
        .ait-status.success { background:#f0fdf4; border:1px solid #bbf7d0; color:#065f46; display:block; }
        .ait-status.error   { background:#fef2f2; border:1px solid #fecaca; color:#991b1b; display:block; }


        .ait-form-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
        @media (max-width:700px) { .ait-form-grid { grid-template-columns:1fr; } }


    </style>


    <div class="ait-card">
        <div class="ait-title">🧪 AI Content Testing Sandbox</div>
        <div class="ait-hint">Upload a handout and test what Claude generates for it — quiz questions (all types) at the adaptive difficulty you pick. Nothing here is saved to any student's account.</div>

        <div class="ait-form-grid" style="margin-bottom:14px;">
            <div class="ait-field">
                <label>Topic name (for generation context)</label>
                <input type="text" id="aitTopic" placeholder="e.g. Data Structures" />
            </div>
            <div class="ait-field">
                <label>Adaptive difficulty (demand level)</label>
                <select id="aitLevel">${aitLevelOptions(AIT_DEFAULT_LEVEL)}</select>
                <div class="ait-field-note">The same five-step ladder the adaptive model assigns to each student question. Re-test any topic below at a different level without uploading again.</div>
            </div>
        </div>

        <div class="ait-field" style="margin-bottom:12px;">
            <label>Upload handout (PDF, TXT, DOCX)</label>
            <div class="ait-upload-zone" id="aitZone">
                <div class="ait-upload-zone-icon">📄</div>
                <div class="ait-upload-zone-text">Click or drag & drop</div>
                <div class="ait-upload-zone-sub">PDF, TXT, DOCX · Max 10MB</div>
            </div>
            <input type="file" id="aitFileInput" accept=".txt,.pdf,.doc,.docx,.csv" style="display:none" />
            <div id="aitUploadProgress"></div>
        </div>

        <button class="btn primary" id="aitRunAll" disabled style="width:100%;">🚀 Run All AI Tests</button>
        <div class="ait-status" id="aitGlobalStatus"></div>
    </div>


    <div class="ait-card" id="aitCleanupCard">
        <div class="report-header" style="margin-bottom:0;">
            <div>
                <div class="ait-title">🗑 Test Topics on This Admin Account</div>
                <div class="ait-hint" style="margin-bottom:0;">Every test run saves real Material + Question rows under your admin login. Delete them here when you're done.</div>
            </div>
            <button class="action-btn btn-view" id="aitRefreshTopicsBtn" style="white-space:nowrap;">🔄 Refresh</button>
        </div>
        <div id="aitTopicsList" style="margin-top:14px;">
            <div class="empty-state">Loading...</div>
        </div>
    </div>`;

        let aitFile = null;

        const zone      = document.getElementById("aitZone");
        const fileInput = document.getElementById("aitFileInput");
        const runBtn    = document.getElementById("aitRunAll");
        const globalSt  = document.getElementById("aitGlobalStatus");

        // File chip + progress bar + live Claude stage messages — shared with Quiz Hub / Learning Hub (see upload-progress.js)
        const aitBar = UploadProgress.mount(document.getElementById("aitUploadProgress"), {
            onRemove: () => {
                aitFile = null; fileInput.value = "";
                runBtn.disabled = true;
            }
        });

        zone.onclick = () => fileInput.click();
        zone.addEventListener("dragover", e => { e.preventDefault(); zone.classList.add("dragover"); });
        zone.addEventListener("dragleave", () => zone.classList.remove("dragover"));
        zone.addEventListener("drop", e => {
            e.preventDefault(); zone.classList.remove("dragover");
            if (e.dataTransfer.files[0]) setAitFile(e.dataTransfer.files[0]);
        });
        fileInput.addEventListener("change", () => { if (fileInput.files[0]) setAitFile(fileInput.files[0]); });

        function setAitFile(f) {
            if (aitUploadInProgress) return;   // don't swap the file (and wipe the progress bar) mid-run
            aitFile = f;
            aitBar.setFile(f);
            runBtn.disabled = false;
            setAitStatus(globalSt, "", "");
        }

        function setAitStatus(el, msg, type) {
            el.textContent = msg;
            el.className = "ait-status" + (type ? " " + type : "");
        }

        document.getElementById("aitRefreshTopicsBtn").onclick = loadAitTopicsList;
        loadAitTopicsList();

        async function loadAitTopicsList() {
            const listEl = document.getElementById("aitTopicsList");
            listEl.innerHTML = `<div class="empty-state">Loading...</div>`;
            try {
                const data = await apiFetch("/api/materials");
                const materials = data.materials || [];
                if (materials.length === 0) {
                    listEl.innerHTML = `<div class="empty-state">No test materials on this admin account yet. Run a test above to create one.</div>`;
                    return;
                }
                listEl.innerHTML = materials.map(m => `
                    <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 12px; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px; margin-bottom:8px;">
                        <div style="min-width:0;">
                            <div style="font-size:0.85rem; font-weight:700; color:#111827;">${escHtml(m.topic)}</div>
                            <div style="font-size:0.72rem; color:#9ca3af; margin-top:2px;">${escHtml(m.filename || "")} · uploaded ${escHtml(m.uploadedAt || "recently")}</div>
                        </div>
                        <div style="display:flex; align-items:center; gap:8px; flex-shrink:0; flex-wrap:wrap; justify-content:flex-end;">
                            <select class="ait-row-level" aria-label="Adaptive difficulty for ${escHtml(m.topic)}">${aitLevelOptions(aitTopicLevels[m.topic] || AIT_DEFAULT_LEVEL)}</select>
                            <button class="action-btn btn-view" data-topic="${escHtml(m.topic)}" onclick="regenerateAitTopic(this)">↻ Regenerate</button>
                            <a class="action-btn" style="text-decoration:none; display:inline-flex; align-items:center; justify-content:center; background:#eff6ff; color:#1d4ed8; border:1px solid #bfdbfe;" href="/quizpage.html?topic=${encodeURIComponent(m.topic || "")}&difficulty=Test&adminPreview=1" target="_blank" rel="noopener">▶ Open Test Quiz</a>
                            <button class="action-btn btn-delete" data-topic="${escHtml(m.topic)}" onclick="deleteAitTopic(this.dataset.topic)">🗑 Delete</button>
                        </div>
                    </div>`).join("");
            } catch (err) {
                listEl.innerHTML = `<div class="empty-state">Could not load test topics: ${escHtml(err.message || "Unknown error")}</div>`;
            }
        }

        // Re-runs the all-types quiz for an existing test topic at the level picked in its row. No upload.
        window.regenerateAitTopic = async function(btn) {
            if (aitUploadInProgress) return;
            const topic = btn.dataset.topic;
            const select = btn.parentElement.querySelector(".ait-row-level");
            const level = Number(select.value);
            const label = btn.textContent;
            btn.disabled = true; select.disabled = true;
            btn.textContent = "⏳ Generating…";
            setAitStatus(globalSt, `Generating the all-types quiz for "${topic}" at ${aitLevelLabel(level)}…`, "info");
            try {
                const res = await apiFetch(
                    `/api/quiz/test-types?topic=${encodeURIComponent(topic)}&level=${level}`, { method: "POST" });
                const count = res?.questions?.length || 0;
                if (!res?.success || count === 0) throw new Error(res?.message || "No questions were generated.");
                aitTopicLevels[topic] = level;
                // An incomplete set is shown as an error, never as a plain success.
                setAitStatus(globalSt, res.message || "Done.", res.complete === false ? "error" : "success");
            } catch (err) {
                setAitStatus(globalSt, "Could not regenerate: " + (err.message || "Unknown error"), "error");
            } finally {
                btn.disabled = false; select.disabled = false;
                btn.textContent = label;
            }
        };

        window.deleteAitTopic = function(topic) {
            showModal(`
    <div class="modal-title">🗑 Delete test data: "${escHtml(topic)}"</div>
    <div class="modal-body">This removes the test material, generated questions, and cached lesson for this topic — only from your admin account. No student data is touched.</div>
    <div class="modal-actions">
      <button class="btn ghost" onclick="closeModal()">Cancel</button>
      <button class="btn primary" style="background:#ef4444;border-color:#ef4444;" id="confirmAitDeleteBtn">🗑 Delete</button>
    </div>
    <div id="aitDeleteModalStatus" style="display:none; margin-top:12px; font-size:0.8rem; padding:8px 12px; border-radius:8px;"></div>`);
            document.getElementById("confirmAitDeleteBtn").onclick = async () => {
                const btn = document.getElementById("confirmAitDeleteBtn");
                btn.disabled = true;
                btn.textContent = "Deleting...";
                try {
                    await apiFetch(`/api/admin/ai-test-data?name=${encodeURIComponent(topic)}`, { method: "DELETE" });
                    await loadAitTopicsList();
                    closeModal();
                } catch (err) {
                    btn.disabled = false;
                    btn.textContent = "🗑 Delete";
                    setModalInlineStatus("aitDeleteModalStatus", "Could not delete test data: " + (err.message || "Unknown error"), "error");
                }
            };
        };

        runBtn.onclick = async () => {
            const topic      = document.getElementById("aitTopic").value.trim() || "Uploaded Material";
            const level = Number(document.getElementById("aitLevel").value);
            if (!aitFile) return;

            runBtn.disabled = true;
            runBtn.textContent = "⏳ Running tests…";
            setAitStatus(globalSt, "Uploading material and running AI tests — this may take 20-40 seconds…", "info");

            // Track this attempt so it can be cancelled/cleaned up server-side if the admin
            // navigates away mid-upload — see aitCancelActiveUpload() above.
            window.__aitLastTopic = topic;
            aitActiveUploadId = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random());
            aitUploadInProgress = true;
            aitBar.setLocked(true);   // chip stays locked for the whole run, not just the file transfer

            try {
                const formData = new FormData();
                formData.append("topic", topic);
                formData.append("file", aitFile);
                formData.append("sandbox", "true");   // server skips the summary, categorization and starter quiz
                // Real upload % then live Claude stage messages over SSE (upload-progress.js). Cancel-on-leave and
                // the chip lock are handled here (aitCancelActiveUpload / aitBar), so the helper's own are switched off.
                const uploadResult = await UploadProgress.send({
                    bar: aitBar,
                    formData,
                    topic,
                    uploadId: aitActiveUploadId,
                    cancelOnLeave: false,
                    manageLock: false,
                    doneMessage: "Material processed — starting AI tests…",
                    hideAfterMs: 1500
                });
                if (!uploadResult.success) throw new Error(uploadResult.message || "Upload failed");

                setAitStatus(globalSt, `Upload succeeded — generating the all-types quiz at ${aitLevelLabel(level)}…`, "info");

                // Summary/categorization are produced during upload and the quiz is saved server-side;
                // both are viewable from the topic list below, so nothing is rendered inline here.
                let allTypesRes;
                try {
                    allTypesRes = { status: "fulfilled", value: await apiFetch(`/api/quiz/test-types?topic=${encodeURIComponent(topic)}&level=${level}`, { method: "POST" }) };
                } catch (e) {
                    allTypesRes = { status: "rejected", reason: e };
                }

                const qRes = (allTypesRes.status === "fulfilled") ? allTypesRes.value : null;
                const qCount = (qRes && qRes.success) ? (qRes.questions?.length || 0) : 0;
                const qMissing = (qRes && qRes.success && Array.isArray(qRes.missingTypes)) ? qRes.missingTypes : [];
                if (qCount > 0) aitTopicLevels[topic] = level;

                if (qCount === 0) {
                    // No set was saved (a partial set is never saved): say why instead of "All tests complete."
                    const qFailMsg = (allTypesRes.status === "fulfilled") ? allTypesRes.value?.message : allTypesRes.reason?.message;
                    setAitStatus(globalSt, `Tests finished, but the all-types quiz was NOT generated: ${qFailMsg || "Unknown error"} Press ↻ Regenerate on the topic below to try again.`, "error");
                } else if (qMissing.length) {
                    setAitStatus(globalSt, `Tests finished, but these question types were NOT generated: ${qMissing.join(", ")}. Press ↻ Regenerate on the topic below to try again.`, "error");
                } else {
                    setAitStatus(globalSt, "All tests complete.", "success");
                }
                await loadAitTopicsList();

            } catch (err) {
                setAitStatus(globalSt, "Test run failed: " + (err.message || "Unknown error"), "error");
            } finally {
                aitBar.setLocked(false);
                aitUploadInProgress = false;
                aitActiveUploadId = null;
                runBtn.disabled = false;
                runBtn.textContent = "🚀 Run All AI Tests";
            }
        };

        function escHtml(str) {
            return String(str ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
        }
    }
