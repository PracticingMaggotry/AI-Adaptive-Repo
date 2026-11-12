/**
 * material-upload.js — the ONE "upload a handout" widget, shared by the Quiz Hub drawer and the Learning Hub card.
 *
 * The two pages used to carry their own upload code and had drifted apart: the Learning Hub had no replace warning,
 * no uploaded-materials list and did not cancel an upload on in-app navigation; the Quiz Hub had no file-type check
 * and its own copy of the progress code. Everything an upload does now lives here, so the two cannot differ again —
 * each page only supplies a container element and an optional onUploaded() callback.
 *
 * What it renders and does (identical on both pages):
 *   - Topic name field (required)
 *   - Click / drag-and-drop file zone with a client-side file-type check
 *   - File chip, real byte progress and live Claude stage messages (upload-progress.js)
 *   - "Replace your current question set?" warning when the topic already has one (replace-warning.js)
 *   - Success / error message, then a redirect to the new quiz
 *   - The upload is cancelled (and cleaned up server-side) if the student leaves the page or clicks a nav link mid-upload
 *   - An "Uploaded Materials" list with a Start Quiz link per material (skipped when mounted with showMaterials: false,
 *     which the Learning Hub does: its topic list already shows every upload)
 *
 * Requires api.js and upload-progress.js. replace-warning.js is used when it is loaded.
 *
 *     const uploader = MaterialUpload.mount(document.getElementById("materialUploadHost"), {
 *         onUploaded: (result) => refreshTopicList(),  // optional: after an upload that does not redirect
 *         showMaterials: false                         // optional: omit the Uploaded Materials list (default true)
 *     });
 *     uploader.reload();     // re-fetch the Uploaded Materials list (e.g. when a drawer opens)
 *     uploader.isBusy();     // true while confirming or uploading — don't close the widget or navigate away
 */
(function () {
    "use strict";

    if (window.MaterialUpload) return;

    // Same list as the file input's accept="" and the server's ALLOWED_FILE_EXTENSIONS default (pdf,txt,csv,doc,docx).
    const ALLOWED_EXTENSIONS = ["pdf", "docx", "doc", "txt", "csv"];
    const LABEL_IDLE = "✨ Upload & Generate Quiz";
    const REDIRECT_DELAY_MS = 1500;   // time to read the success message before the new quiz opens
    const CLEAR_DELAY_MS = 1200;      // how long "Done!" stays up when the page stays where it is

    const CSS = `
.mu{display:flex;flex-direction:column;gap:16px}
.mu-label{display:block;margin-bottom:5px;font-size:.78rem;font-weight:700;color:var(--text-2,#374151)}
.mu-topic{width:100%;box-sizing:border-box;padding:9px 12px;font:inherit;font-size:.85rem;color:var(--text,#111827);background:#fff;border:1px solid var(--border,#e2e8f0);border-radius:var(--r-md,12px);outline:none;transition:border-color .15s,box-shadow .15s}
.mu-topic:focus{border-color:var(--primary,#0284c7);box-shadow:0 0 0 3px rgba(0,147,173,.18)}
.mu-topic.mu-invalid{border-color:#ef4444}
.mu-topic:disabled{opacity:.6;cursor:not-allowed}
.mu-zone{padding:20px 16px;text-align:center;cursor:pointer;background:#f8fafc;border:2px dashed #cbd5e1;border-radius:10px;transition:border-color .2s,background .2s}
.mu-zone:hover,.mu-zone.mu-dragover{border-color:var(--primary,#0284c7);background:var(--primary-soft,#eff6ff)}
.mu-zone.mu-disabled{cursor:not-allowed;opacity:.6}
.mu-zone.mu-shake{animation:mu-shake .4s ease}
.mu-zone-icon{margin-bottom:6px;font-size:1.5rem}
.mu-zone-text{font-size:.82rem;font-weight:600;color:var(--text-2,#374151)}
.mu-zone-sub{margin-top:3px;font-size:.72rem;color:var(--muted,#64748b)}
.mu-file-input{display:none}
.mu-hint{font-size:.72rem;line-height:1.5;color:var(--muted,#64748b)}
.mu-submit{width:100%}
.mu-message{display:none;padding:8px 12px;font-size:.8rem;line-height:1.45;border-radius:8px}
.mu-message.success{display:block;color:#065f46;background:#f0fdf4;border:1px solid #bbf7d0}
.mu-message.error{display:block;color:#991b1b;background:#fef2f2;border:1px solid #fecaca}
.mu-divider{margin:0;border:none;border-top:1px solid #f1f5f9}
.mu-section-title{font-size:.75rem;font-weight:700;letter-spacing:.05em;text-transform:uppercase;color:#6b7280}
.mu-materials{display:flex;flex-direction:column;gap:10px}
.mu-material{display:flex;flex-direction:column;gap:5px;padding:12px 14px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px}
.mu-material-topic{align-self:flex-start;margin-bottom:2px;padding:2px 8px;font-size:.72rem;font-weight:700;color:#1e40af;background:#dbeafe;border-radius:99px}
.mu-material-filename{font-size:.82rem;font-weight:600;color:#111827;overflow-wrap:anywhere}
.mu-material-meta{font-size:.72rem;color:var(--muted,#64748b)}
.mu-material-actions{display:flex;flex-wrap:wrap;gap:6px;margin-top:4px}
.mu-material-start{padding:6px 12px;font-size:.75rem}
.mu-empty{padding:16px 0;text-align:center;font-size:.82rem;color:var(--muted,#64748b)}
@keyframes mu-shake{0%,100%{transform:translateX(0)}20%{transform:translateX(-5px)}40%{transform:translateX(5px)}60%{transform:translateX(-3px)}80%{transform:translateX(3px)}}
@media (prefers-reduced-motion:reduce){.mu-zone.mu-shake{animation:none}}
`;

    function injectStyles() {
        if (document.getElementById("material-upload-styles")) return;
        const style = document.createElement("style");
        style.id = "material-upload-styles";
        style.textContent = CSS;
        document.head.appendChild(style);
    }

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function formatSize(bytes) {
        if (!bytes) return "0 KB";
        if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
        return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    }

    let instanceCount = 0;

    function mount(host, options) {
        const opts = options || {};
        const showMaterials = opts.showMaterials !== false;
        if (!window.UploadProgress) throw new Error("material-upload.js needs upload-progress.js to be loaded first.");
        injectStyles();
        host.textContent = "";
        const uid = "mu" + (++instanceCount);

        let file = null;
        let busy = false;   // from clicking Upload (replace warning included) until the upload has failed or finished

        // ---- markup -------------------------------------------------------------------------------------------
        const root = el("div", "mu");

        const topicField = el("div");
        const topicLabel = el("label", "mu-label", "Topic name");
        topicLabel.htmlFor = uid + "-topic";
        const topicInput = el("input", "mu-topic");
        topicInput.type = "text";
        topicInput.id = uid + "-topic";
        topicInput.placeholder = "e.g. Data Structures";
        topicInput.autocomplete = "off";
        topicField.append(topicLabel, topicInput);

        const fileField = el("div");
        const fileLabel = el("div", "mu-label", "Upload file");
        const zone = el("div", "mu-zone");
        zone.append(
            el("div", "mu-zone-icon", "📄"),
            el("div", "mu-zone-text", "Click to upload or drag & drop"),
            el("div", "mu-zone-sub", "PDF, DOCX, DOC, TXT, or CSV · Max 10MB")
        );
        const fileInput = el("input", "mu-file-input");
        fileInput.type = "file";
        fileInput.accept = ALLOWED_EXTENSIONS.map((ext) => "." + ext).join(",");
        const progressHost = el("div");
        fileField.append(fileLabel, zone, fileInput, progressHost);

        const hint = el("div", "mu-hint",
            "Upload any document and the AI will create a topic and pre-assessment quiz from it. " +
            "Supported: text-based PDF, DOCX, DOC, TXT, and CSV.");

        const submitBtn = el("button", "btn primary mu-submit", LABEL_IDLE);
        submitBtn.type = "button";
        submitBtn.disabled = true;

        const message = el("div", "mu-message");
        message.setAttribute("role", "status");
        message.setAttribute("aria-live", "polite");

        const materialsHost = el("div", "mu-materials");

        root.append(topicField, fileField, hint, submitBtn, message);
        if (showMaterials) {
            root.append(
                el("hr", "mu-divider"),
                el("div", "mu-section-title", "Uploaded Materials"),
                materialsHost
            );
        }
        host.appendChild(root);

        // ---- progress bar + file chip (shared with the admin AI-testing upload) -----------------------------------
        const bar = UploadProgress.mount(progressHost, {
            // Keep the button honest: it says "Uploading" only while bytes are moving, then switches to the server's work.
            onPhase: (phase) => {
                if (!busy) return;
                if (phase === "uploading") submitBtn.textContent = "Uploading file…";
                else if (phase === "processing") submitBtn.textContent = "Preparing your quiz…";
                else if (phase === "done") submitBtn.textContent = "Done";
            },
            onRemove: () => {
                if (busy) return;   // only reachable while the success redirect is pending; leave the message alone
                file = null;
                fileInput.value = "";
                setMessage("", "");
                syncControls();
            }
        });

        // ---- small helpers ----------------------------------------------------------------------------------------
        function setMessage(text, type) {
            message.textContent = text;
            message.className = "mu-message" + (type ? " " + type : "");
        }

        function syncControls() {
            submitBtn.disabled = busy || !file;
            topicInput.disabled = busy;
            zone.classList.toggle("mu-disabled", busy);
            if (!busy) submitBtn.textContent = LABEL_IDLE;
        }

        function shake(node) {
            node.classList.remove("mu-shake");
            void node.offsetWidth;   // restart the animation if it is already running
            node.classList.add("mu-shake");
            setTimeout(() => node.classList.remove("mu-shake"), 400);
        }

        // ---- choosing a file --------------------------------------------------------------------------------------
        function handleFile(chosen) {
            if (busy || !chosen) return;   // don't swap the file (and wipe the progress bar) mid-upload
            const ext = chosen.name.split(".").pop().toLowerCase();
            if (!ALLOWED_EXTENSIONS.includes(ext)) {
                fileInput.value = "";
                shake(zone);
                setMessage("Unsupported file type. Please choose a PDF, DOCX, DOC, TXT, or CSV file.", "error");
                return;
            }
            file = chosen;
            setMessage("", "");
            bar.setFile(chosen);
            syncControls();
        }

        zone.addEventListener("click", () => { if (!busy) fileInput.click(); });
        zone.addEventListener("dragover", (e) => { e.preventDefault(); if (!busy) zone.classList.add("mu-dragover"); });
        zone.addEventListener("dragleave", () => zone.classList.remove("mu-dragover"));
        zone.addEventListener("drop", (e) => {
            e.preventDefault();
            zone.classList.remove("mu-dragover");
            if (e.dataTransfer && e.dataTransfer.files[0]) handleFile(e.dataTransfer.files[0]);
        });
        fileInput.addEventListener("change", () => { if (fileInput.files[0]) handleFile(fileInput.files[0]); });
        topicInput.addEventListener("input", () => topicInput.classList.remove("mu-invalid"));

        // ---- uploading --------------------------------------------------------------------------------------------
        submitBtn.addEventListener("click", async () => {
            if (busy) return;
            const topic = topicInput.value.trim();
            if (!topic) {
                topicInput.classList.add("mu-invalid");
                topicInput.focus();
                setMessage("Please enter a topic name.", "error");
                return;
            }
            if (!file) { setMessage("Please select a file.", "error"); return; }

            busy = true;            // locks the form from here: through the replace warning, the upload and any redirect
            bar.setLocked(true);
            syncControls();
            let redirecting = false;
            try {
                if (window.ReplaceWarning && !(await ReplaceWarning.confirmIfSetExists("upload", topic))) return;

                submitBtn.textContent = "Uploading file…";
                setMessage("", "");
                const formData = new FormData();
                formData.append("topic", topic);
                formData.append("file", file);

                // Real upload %, then live Claude stage messages over SSE; aborts + cleans up if the page is left.
                const result = await UploadProgress.send({
                    bar,
                    formData,
                    topic,
                    manageLock: false,   // locked above, so the chip also stays locked through the replace warning and redirect
                    doneMessage: "Done! Topic and quiz created."
                });

                redirecting = !!result.quizUrl;
                setMessage(
                    (result.message || "Upload successful! Quiz generated.") + (redirecting ? " Redirecting to your quiz…" : ""),
                    "success");
                topicInput.value = "";
                file = null;
                fileInput.value = "";
                loadMaterials();
                if (redirecting) {
                    setTimeout(() => { window.location.href = result.quizUrl; }, REDIRECT_DELAY_MS);
                } else {
                    setTimeout(() => { if (!file && !busy) bar.clear(); }, CLEAR_DELAY_MS);
                    if (typeof opts.onUploaded === "function") {
                        try { opts.onUploaded(result); } catch (e) { console.warn("onUploaded failed:", e); }
                    }
                }
            } catch (err) {
                if (!err.cancelled) {   // cancelled = the student left the page mid-upload
                    setMessage(err.message || "Upload failed. Check that you are logged in and the backend is running.", "error");
                }
            } finally {
                if (!redirecting) {
                    busy = false;
                    bar.setLocked(false);
                    syncControls();
                }
            }
        });

        // Navigating to another in-app page (sidebar or mobile tab bar) mid-upload: cancel first, then go.
        // Closing the tab / typing a URL is already covered by upload-progress.js (pagehide / beforeunload).
        document.addEventListener("click", (e) => {
            if (!busy) return;
            const link = e.target.closest && e.target.closest(".sidebar a[href], .mobile-tabbar a[href]");
            if (!link) return;
            if (link.classList.contains("logout")) {
                // Just cancel; the page's own logout handler still runs (bubble phase) and does the sign-out + redirect.
                UploadProgress.cancelActive();
                return;
            }
            const href = link.getAttribute("href");
            if (!href || href.charAt(0) === "#" || link.target === "_blank" || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            e.preventDefault();
            UploadProgress.cancelActive();
            window.location.href = link.href;
        }, true);   // capture: runs before the per-link handlers (e.g. logout's own click listener)

        // ---- uploaded materials list ------------------------------------------------------------------------------
        let loadSeq = 0;

        function showMaterialsNote(text) {
            materialsHost.textContent = "";
            materialsHost.appendChild(el("div", "mu-empty", text));
        }

        async function loadMaterials() {
            if (!showMaterials) return;   // no list to fill, so no /api/materials request either
            const seq = ++loadSeq;   // a slow, older response must never overwrite a newer one
            showMaterialsNote("Loading...");
            try {
                const data = await apiFetch("/api/materials");
                if (seq !== loadSeq) return;
                const materials = (data && data.materials) || [];
                if (materials.length === 0) {
                    showMaterialsNote("No uploads yet. Upload a handout to get started!");
                    return;
                }
                materialsHost.textContent = "";
                materials.forEach((m) => {
                    const item = el("div", "mu-material");
                    item.append(
                        el("span", "mu-material-topic", m.topic || ""),
                        el("div", "mu-material-filename", m.filename || ""),
                        el("div", "mu-material-meta", (m.uploadedAt || "Recently") + " · " + formatSize(m.sizeBytes))
                    );
                    if (m.quizUrl) {
                        const actions = el("div", "mu-material-actions");
                        const start = el("a", "btn primary mu-material-start", "▶ Start Quiz");
                        start.href = m.quizUrl;
                        actions.appendChild(start);
                        item.appendChild(actions);
                    }
                    materialsHost.appendChild(item);
                });
            } catch (err) {
                if (seq === loadSeq) showMaterialsNote("Could not load materials.");
            }
        }

        loadMaterials();

        return { reload: loadMaterials, isBusy: () => busy };
    }

    window.MaterialUpload = { mount };
})();
