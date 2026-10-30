/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    function renderAiTesting() {
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


        .ait-status { font-size:0.8rem; padding:9px 12px; border-radius:8px; margin-top:10px; display:none; }
        .ait-status.info    { background:#eff6ff; border:1px solid #bfdbfe; color:#1e40af; display:block; }
        .ait-status.success { background:#f0fdf4; border:1px solid #bbf7d0; color:#065f46; display:block; }
        .ait-status.error   { background:#fef2f2; border:1px solid #fecaca; color:#991b1b; display:block; }


        .ait-q-card {
            border:1px solid #e2e8f0; border-radius:10px; padding:12px 14px; margin-bottom:10px; background:#fafafa;
        }
        .ait-q-card:last-child { margin-bottom:0; }
        .ait-q-type  { display:inline-block; font-size:0.65rem; font-weight:800; text-transform:uppercase; letter-spacing:0.06em; padding:2px 8px; border-radius:99px; background:#ede9fe; color:#5b21b6; margin-bottom:6px; }
        .ait-q-text  { font-size:0.85rem; font-weight:600; color:#111827; margin-bottom:4px; line-height:1.4; }
        .ait-q-meta  { font-size:0.75rem; color:#6b7280; line-height:1.7; }
        .ait-q-meta b { color:#374151; }


        .ait-concept { border:1px solid #e2e8f0; border-radius:8px; padding:10px 12px; margin-bottom:8px; background:#fafafa; }
        .ait-concept:last-child { margin-bottom:0; }
        .ait-concept-term { font-size:0.85rem; font-weight:700; color:#111827; margin-bottom:2px; }
        .ait-concept-exp  { font-size:0.8rem; color:#374151; line-height:1.55; }


        .ait-form-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
        @media (max-width:700px) { .ait-form-grid { grid-template-columns:1fr; } }


        .ait-results-shell {
            display: none;
            gap: 16px;
            align-items: flex-start;
        }
        .ait-results-shell.visible { display: flex; }


        .ait-panel-questions {
            flex: 1;
            min-width: 0;
            display: flex;
            flex-direction: column;
            gap: 10px;
        }


        .ait-panel-lesson {
            width: 320px;
            flex-shrink: 0;
            display: flex;
            flex-direction: column;
            gap: 10px;
            position: sticky;
            top: 0;
        }


        .ait-panel-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 10px 14px;
            background: #f8fafc;
            border: 1px solid #e2e8f0;
            border-radius: 12px 12px 0 0;
            font-size: 0.85rem;
            font-weight: 700;
            color: #111827;
        }
        .ait-panel-header-badge {
            font-size: 0.68rem;
            font-weight: 700;
            padding: 2px 8px;
            border-radius: 99px;
            background: #e0f2fe;
            color: #0369a1;
        }


        .ait-panel-body {
            background: #fff;
            border: 1px solid #e2e8f0;
            border-top: none;
            border-radius: 0 0 12px 12px;
            padding: 14px;
            max-height: 72vh;
            overflow-y: auto;
        }
        .ait-panel-body::-webkit-scrollbar { width: 6px; }
        .ait-panel-body::-webkit-scrollbar-track { background: #f8fafc; border-radius: 99px; }
        .ait-panel-body::-webkit-scrollbar-thumb { background: #cbd5e1; border-radius: 99px; }


        .ait-sub-section { margin-bottom: 16px; }
        .ait-sub-section:last-child { margin-bottom: 0; }
        .ait-sub-title {
            font-size: 0.7rem;
            font-weight: 800;
            text-transform: uppercase;
            letter-spacing: 0.07em;
            color: #6b7280;
            margin-bottom: 8px;
            padding-bottom: 5px;
            border-bottom: 1px solid #f1f5f9;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }


        .ait-device-frame {
            border: 1px solid #e2e8f0;
            border-radius: 14px;
            overflow: hidden;
            background: #fff;
            box-shadow: 0 4px 14px rgba(0,0,0,0.06);
        }
        .ait-device-bar {
            display: flex;
            align-items: center;
            gap: 6px;
            padding: 8px 12px;
            background: #f1f5f9;
            border-bottom: 1px solid #e2e8f0;
        }
        .ait-device-dot { width: 9px; height: 9px; border-radius: 50%; flex-shrink: 0; }
        .ait-device-url {
            margin-left: 6px;
            font-size: 0.7rem;
            color: #64748b;
            font-family: ui-monospace, "Cascadia Code", "Fira Code", monospace;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .ait-device-iframe { width: 100%; height: 680px; border: none; display: block; }


        .ait-lesson-intro {
            font-size: 0.85rem;
            color: #374151;
            line-height: 1.6;
            border-left: 3px solid #0093ad;
            padding-left: 10px;
            margin-bottom: 14px;
        }
        .ait-lesson-tier-badge {
            display: inline-flex;
            align-items: center;
            gap: 5px;
            font-size: 0.78rem;
            font-weight: 700;
            padding: 4px 10px;
            border-radius: 99px;
            margin-bottom: 12px;
        }
        .ait-lesson-tier-easy   { background: #d1fae5; color: #065f46; }
        .ait-lesson-tier-medium { background: #fef3c7; color: #92400e; }
        .ait-lesson-tier-hard   { background: #fee2e2; color: #991b1b; }
        .ait-lesson-section-label {
            font-size: 0.68rem;
            font-weight: 800;
            text-transform: uppercase;
            letter-spacing: 0.07em;
            color: #6b7280;
            margin: 14px 0 6px;
        }


        @media (max-width: 1100px) {
            .ait-results-shell { flex-direction: column; }
            .ait-panel-lesson  { width: 100%; position: static; }
            .ait-panel-body    { max-height: 60vh; }
        }
    </style>


    <div class="ait-card">
        <div class="ait-title">🧪 AI Content Testing Sandbox</div>
        <div class="ait-hint">Upload a handout and test what Claude generates for it — quiz questions (all types), lesson content, topic summary, and categorization. Nothing here is saved to any student's account.</div>

        <div class="ait-form-grid" style="margin-bottom:14px;">
            <div class="ait-field">
                <label>Topic name (for generation context)</label>
                <input type="text" id="aitTopic" placeholder="e.g. Data Structures" />
            </div>
            <div class="ait-field">
                <label>Quiz difficulty</label>
                <select id="aitDifficulty">
                    <option value="Easy">Easy</option>
                    <option value="Medium" selected>Medium</option>
                    <option value="Hard">Hard</option>
                </select>
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

        <div class="ait-field" style="margin-bottom:12px;">
            <label>Question test cases to generate
                <a href="#" id="aitTypesAll" style="margin-left:8px;font-weight:400;">all</a> ·
                <a href="#" id="aitTypesNone" style="font-weight:400;">none</a>
            </label>
            <div id="aitTypesBox" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:6px 12px;">
                ${[
                    ["MCQ", "Multiple choice"], ["TRUEFALSE", "True / False"], ["MATCHING", "Matching"],
                    ["FILLBLANK_DRAGDROP", "Fill blank · drag-drop"], ["FILLBLANK_TYPED", "Fill blank · typed"],
                    ["ESSAY", "Essay"], ["SORTING", "Sorting"], ["CONCEPTID", "Four Ideas – One Name"],
                    ["PRACTICAL_SOLVE", "Practical · Solve It"], ["PRACTICAL_OUTPUT", "Practical · Predict the Output"],
                    ["PRACTICAL_ERROR_SPOT", "Practical · Spot the Error"]
                ].map(([v, l]) => `<label style="font-weight:400;display:flex;gap:6px;align-items:center;"><input type="checkbox" class="ait-type" value="${v}" checked /> ${l}</label>`).join("")}
            </div>
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
    </div>


    <div id="aitResults" class="ait-results-shell">


        <div class="ait-panel-questions">
            <div class="ait-panel-header">
                <span>🎯 Generated Questions</span>
                <span class="ait-panel-header-badge" id="aitQBadge">Waiting…</span>
            </div>
            <div class="ait-panel-body">


                <div class="ait-sub-section">
                    <div class="ait-sub-title">
                        <span>📝 Topic Summary &amp; Categorization</span>
                    </div>
                    <div id="aitSummaryBody">
                        <div class="ait-status info" id="aitSummaryStatus">Waiting for upload…</div>
                    </div>
                </div>


                <div class="ait-sub-section">
                    <div class="ait-sub-title">
                        <span>🖥 Live Quiz Preview — exactly what the student sees (all question types)</span>
                    </div>
                    <div id="aitPreviewBody">
                        <div class="ait-status info" id="aitPreviewStatus">Waiting for upload…</div>
                    </div>
                </div>

            </div>
        </div>


        <div class="ait-panel-lesson">
            <div class="ait-panel-header">
                <span>🎓 Lesson Content</span>
                <span class="ait-panel-header-badge" id="aitLessonBadge">Waiting…</span>
            </div>
            <div class="ait-panel-body" id="aitLessonBody">
                <div class="ait-status info" id="aitLessonStatus">Waiting for upload…</div>
            </div>
        </div>

    </div>`;

        let aitFile = null;

        const zone      = document.getElementById("aitZone");
        const fileInput = document.getElementById("aitFileInput");
        const runBtn    = document.getElementById("aitRunAll");
        const setAllTypes = (on) => document.querySelectorAll(".ait-type").forEach(c => { c.checked = on; });
        document.getElementById("aitTypesAll").onclick  = (e) => { e.preventDefault(); setAllTypes(true); };
        document.getElementById("aitTypesNone").onclick = (e) => { e.preventDefault(); setAllTypes(false); };
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
                        <div style="display:flex; align-items:center; gap:8px; flex-shrink:0;">
                            <a class="action-btn" style="text-decoration:none; display:inline-flex; align-items:center; justify-content:center; background:#eff6ff; color:#1d4ed8; border:1px solid #bfdbfe;" href="/quizpage.html?topic=${encodeURIComponent(m.topic || "")}&difficulty=Test&adminPreview=1" target="_blank" rel="noopener">▶ Open Test Quiz</a>
                            <button class="action-btn btn-delete" data-topic="${escHtml(m.topic)}" onclick="deleteAitTopic(this.dataset.topic)">🗑 Delete</button>
                        </div>
                    </div>`).join("");
            } catch (err) {
                listEl.innerHTML = `<div class="empty-state">Could not load test topics: ${err.message || "Unknown error"}</div>`;
            }
        }

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
                    await apiFetch(`/api/admin/ai-test-data/${encodeURIComponent(topic)}`, { method: "DELETE" });
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
            const difficulty = document.getElementById("aitDifficulty").value;
            if (!aitFile) return;

            const selectedTypes = [...document.querySelectorAll(".ait-type:checked")].map(c => c.value);
            if (!selectedTypes.length) {
                setAitStatus(globalSt, "Select at least one question test case.", "error");
                return;
            }

            runBtn.disabled = true;
            runBtn.textContent = "⏳ Running tests…";
            setAitStatus(globalSt, "Uploading material and running AI tests — this may take 20-40 seconds…", "info");

            // Track this attempt so it can be cancelled/cleaned up server-side if the admin
            // navigates away mid-upload — see aitCancelActiveUpload() above.
            window.__aitLastTopic = topic;
            aitActiveUploadId = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random());
            aitUploadInProgress = true;
            aitBar.setLocked(true);   // chip stays locked for the whole run, not just the file transfer

            const resultsShell = document.getElementById("aitResults");
            resultsShell.classList.add("visible");

            const qBadge = document.getElementById("aitQBadge");
            const lBadge = document.getElementById("aitLessonBadge");
            if (qBadge) qBadge.textContent = "Running…";
            if (lBadge) lBadge.textContent = "Running…";

            const resetPairs = [
                ["aitSummaryBody", "aitSummaryStatus"],
                ["aitPreviewBody", "aitPreviewStatus"],
                ["aitLessonBody",  "aitLessonStatus"],
            ];
            resetPairs.forEach(([bodyId, statusId]) => {
                const bodyEl = document.getElementById(bodyId);
                if (bodyEl) {
                    bodyEl.innerHTML = `<div class="ait-status info" id="${statusId}">Running…</div>`;
                }
            });

            try {
                const formData = new FormData();
                formData.append("topic", topic);
                formData.append("file", aitFile);
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

                setAitStatus(globalSt, "Upload succeeded — generating the all-types quiz for the live preview…", "info");
                setAitStatus(document.getElementById("aitPreviewStatus"),
                    "Generating the all-question-types quiz — the preview will appear once it's ready…", "info");

                // Deliberately does NOT use the standard/mixed 30-question quiz for the
                // preview — the sandbox's live preview is always the all-types quiz, and
                // the iframe is only ever given a src once that quiz actually exists, so
                // an in-progress/half-loaded quiz is never shown.
                const [materialsRes, allTypesRes, lessonRes] = await Promise.allSettled([
                    apiFetch("/api/materials"),
                    apiFetch(`/api/quiz/test-types?topic=${encodeURIComponent(topic)}&types=${encodeURIComponent(selectedTypes.join(","))}`, { method: "POST" }),
                    apiFetch(`/api/ai/lesson?topic=${encodeURIComponent(topic)}`)
                ]);

                renderAitSummary(materialsRes, topic);
                renderAitLesson(lessonRes, difficulty);

                const qCount = (allTypesRes.status === "fulfilled" && allTypesRes.value?.success)
                    ? (allTypesRes.value.questions?.length || 0) : 0;
                const qBadgeEl = document.getElementById("aitQBadge");
                if (qBadgeEl) qBadgeEl.textContent = qCount > 0 ? `${qCount} types OK` : "Failed";

                if (allTypesRes.status === "fulfilled" && allTypesRes.value?.success && qCount > 0) {
                    loadAitPreviewFrame(topic, "Test");
                } else {
                    const previewBody = document.getElementById("aitPreviewBody");
                    if (previewBody) {
                        previewBody.innerHTML = `<div class="ait-status error">Could not generate the all-types quiz — no preview available.</div>`;
                    }
                }

                const missingTypes = (allTypesRes.status === "fulfilled" && allTypesRes.value?.missingTypes) || [];
                setAitStatus(globalSt, missingTypes.length
                    ? "Done, but these test cases did not come back: " + missingTypes.join(", ") + " — run again."
                    : "All tests complete.", missingTypes.length ? "error" : "success");
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

        function renderAitSummary(settled, topic) {
            const el = document.getElementById("aitSummaryBody");
            const st = document.getElementById("aitSummaryStatus");
            if (settled.status === "rejected") {
                setAitStatus(st, "Failed: " + settled.reason?.message, "error"); return;
            }
            const materials = (settled.value?.materials || []);
            const mat = materials.find(m => m.topic && m.topic.toLowerCase() === topic.toLowerCase());
            if (!mat) {
                setAitStatus(st, "Material not found in list after upload — try refreshing.", "error"); return;
            }
            st.style.display = "none";
            el.innerHTML += `
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-top:4px;">
                    <div style="padding:12px; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px;">
                        <div style="font-size:0.68rem; font-weight:700; color:#6b7280; text-transform:uppercase; letter-spacing:0.05em; margin-bottom:6px;">Topic Summary</div>
                        <div style="font-size:0.85rem; color:#374151; line-height:1.6;">${escHtml(mat.topicSummary || "—")}</div>
                    </div>
                    <div style="padding:12px; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px;">
                        <div style="font-size:0.68rem; font-weight:700; color:#6b7280; text-transform:uppercase; letter-spacing:0.05em; margin-bottom:6px;">Category</div>
                        <div style="font-size:0.88rem; font-weight:700; color:#111827;">${escHtml(mat.primaryCategory || "—")}</div>
                        ${mat.subCategory ? `<div style="font-size:0.78rem; color:#6b7280; margin-top:3px;">${escHtml(mat.subCategory)}</div>` : ""}
                    </div>
                </div>`;
            renderMathOnly(el);
        }

        /**
         * Loads the REAL quizpage.html (all-question-types quiz) — the exact same page a
         * student gets — inside a bordered "device frame" via an iframe, so the admin sees
         * the live quiz UI rather than a re-implemented preview. Backed by AuthInterceptor's
         * narrow ?adminPreview=1 exemption for this one page. Only ever called once the
         * all-types quiz has actually been generated and saved — never given a src while
         * the quiz is still being generated, so a half-loaded quiz is never shown.
         */
        function loadAitPreviewFrame(topic, difficulty) {
            const body = document.getElementById("aitPreviewBody");
            if (!body) return;
            const path = `/quizpage.html?topic=${encodeURIComponent(topic)}&difficulty=${encodeURIComponent(difficulty)}`;
            const src = `${path}&adminPreview=1&_ts=${Date.now()}`;
            body.innerHTML = `
                <div class="ait-device-frame">
                    <div class="ait-device-bar">
                        <span class="ait-device-dot" style="background:#ef4444;"></span>
                        <span class="ait-device-dot" style="background:#f59e0b;"></span>
                        <span class="ait-device-dot" style="background:#10b981;"></span>
                        <span class="ait-device-url">${escHtml(path)}</span>
                    </div>
                    <iframe src="${src}" class="ait-device-iframe" title="Live student quiz preview"></iframe>
                </div>`;
        }

        function renderAitLesson(settled, difficulty) {
            const el = document.getElementById("aitLessonBody");
            const st = document.getElementById("aitLessonStatus");
            const lBadgeEl = document.getElementById("aitLessonBadge");

            if (settled.status === "rejected") {
                setAitStatus(st, "Failed: " + settled.reason?.message, "error");
                if (lBadgeEl) lBadgeEl.textContent = "Error";
                return;
            }
            const d = settled.value;
            if (!d || d.noAttemptYet) {
                setAitStatus(st, "No quiz attempt on record yet — take a quiz on this topic first, then re-run to see the lesson.", "info");
                if (lBadgeEl) lBadgeEl.textContent = "No attempt yet";
                return;
            }

            const tier = d.tier || difficulty;
            const tierIcon  = tier === "Hard" ? "🔥" : tier === "Medium" ? "📈" : "🌱";
            const tierLabel = tier === "Hard" ? "Advanced" : tier === "Medium" ? "Intermediate" : "Foundational";
            const tierCls   = tier === "Hard" ? "ait-lesson-tier-hard" : tier === "Medium" ? "ait-lesson-tier-medium" : "ait-lesson-tier-easy";

            if (lBadgeEl) lBadgeEl.textContent = `${tierIcon} ${tierLabel}`;
            if (st) st.style.display = "none";

            el.innerHTML += `
                <div>
                    <div class="ait-lesson-tier-badge ${tierCls}">${tierIcon} ${escHtml(tier)} — ${tierLabel}</div>

                    ${d.intro ? `
                    <div class="ait-lesson-section-label">Introduction</div>
                    <div class="ait-lesson-intro">${escHtml(d.intro)}</div>` : ""}

                    ${d.concepts && d.concepts.length ? `
                    <div class="ait-lesson-section-label">Key Concepts (${d.concepts.length})</div>
                    ${d.concepts.map(c => `
                        <div class="ait-concept">
                            <div class="ait-concept-term">${escHtml(c.term || "")}</div>
                            <div class="ait-concept-exp">${escHtml(c.explanation || "")}</div>
                            ${c.example ? `<div class="ait-concept-exp" style="color:#6b7280; margin-top:4px;">e.g. ${escHtml(c.example)}</div>` : ""}
                        </div>`).join("")}` : ""}

                    ${d.tips && d.tips.length ? `
                    <div class="ait-lesson-section-label">Common Misconceptions</div>
                    <ul style="margin:0; padding-left:18px; font-size:0.82rem; color:#374151; line-height:2;">
                        ${d.tips.map(t => `<li>${escHtml(t)}</li>`).join("")}
                    </ul>` : ""}

                    ${d.studyPlan && d.studyPlan.length ? `
                    <div class="ait-lesson-section-label">How It Fits Together</div>
                    <ol style="margin:0; padding-left:18px; font-size:0.82rem; color:#374151; line-height:2;">
                        ${d.studyPlan.map(s => `<li>${escHtml(stripLeadingNumber(s))}</li>`).join("")}
                    </ol>` : ""}

                    ${d.checkYourself && d.checkYourself.length ? `
                    <div class="ait-lesson-section-label">Check Yourself</div>
                    <ol style="margin:0; padding-left:18px; font-size:0.82rem; color:#374151; line-height:2;">
                        ${d.checkYourself.map(q => `<li>${escHtml(stripLeadingNumber(q))}</li>`).join("")}
                    </ol>` : ""}
                </div>`;
            renderMathOnly(el);
        }

        function escHtml(str) {
            return String(str ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
        }

        function stripLeadingNumber(text) {
            return String(text || "").replace(/^(\s*\d+[\.\)]\s*)+/, "");
        }
    }
