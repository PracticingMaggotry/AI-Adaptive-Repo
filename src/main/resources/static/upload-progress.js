/**
 * upload-progress.js — shared upload feedback for every place a file can be uploaded.
 *
 * Reproduces the Quiz Hub drawer's behaviour so all upload surfaces feel the same:
 *   1. A file chip (name + ✕) that is locked while an upload is in flight.
 *   2. Real byte progress while the file transfers        →  "Uploading your file… 42%"
 *   3. An animated bar + live stage messages from Claude  →  "Claude is analyzing your document's…"
 *      (pushed by the server over SSE — see UploadProgressService / MaterialController)
 *   4. "Done!" on success; the bar hides again on failure so the caller can show its own error.
 *
 * Requires api.js (API_BASE, getCsrfToken). Load it right after api.js:
 *     <script src="api.js"></script>
 *     <script src="upload-progress.js"></script>
 *
 * Usage:
 *     const bar = UploadProgress.mount(document.getElementById("myHost"), {
 *         onRemove: () => { file = null; input.value = ""; }     // user clicked ✕ (never fires mid-upload)
 *     });
 *     bar.setFile(file);                                          // show the chip
 *     const fd = new FormData(); fd.append("topic", t); fd.append("file", file);
 *     try {
 *         const result = await UploadProgress.send({ bar, formData: fd, topic: t });
 *     } catch (err) { if (!err.cancelled) showError(err.message); }
 */
(function () {
    "use strict";

    if (window.UploadProgress) return;

    const DEFAULT_PROCESSING_MESSAGE =
        "File uploaded. The server is now extracting the text and asking Claude to summarize it, " +
        "categorize it, and generate quiz questions — this can take 10–40 seconds depending on the document's length.";

    const CSS = `
.up-chip{display:flex;align-items:center;gap:8px;padding:8px 12px;margin-top:8px;background:#f0fdf4;border:1px solid #bbf7d0;border-radius:8px;font-size:.8rem}
.up-chip[hidden],.up-progress[hidden]{display:none}
.up-chip-name{flex:1;min-width:0;font-weight:600;color:#065f46;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.up-chip-remove{cursor:pointer;color:#6b7280;padding:2px 6px;border:0;border-radius:4px;background:none;font:inherit;font-size:.75rem;line-height:1}
.up-chip-remove:hover:not(:disabled){background:#fee2e2;color:#dc2626}
.up-chip-remove:disabled{opacity:.35;cursor:not-allowed}
.up-progress{margin-top:8px}
.up-bar{height:6px;background:#e5e7eb;border-radius:99px;overflow:hidden}
.up-fill{height:100%;width:0;background:linear-gradient(90deg,#3b82f6,#8b5cf6);border-radius:99px;transition:width .4s ease}
.up-fill.indeterminate{width:28%!important;animation:up-slide 1.15s ease-in-out infinite}
@keyframes up-slide{0%{margin-left:-28%}100%{margin-left:100%}}
.up-label{margin-top:8px;min-height:32px;font-size:.76rem;line-height:1.4;color:#475569;text-align:center}
@media (prefers-reduced-motion:reduce){.up-fill.indeterminate{animation:none;width:100%!important;opacity:.55}}
`;

    function injectStyles() {
        if (document.getElementById("upload-progress-styles")) return;
        const style = document.createElement("style");
        style.id = "upload-progress-styles";
        style.textContent = CSS;
        document.head.appendChild(style);
    }

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function newId() {
        return (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now()) + Math.random();
    }

    function apiBase() {
        return (typeof API_BASE === "string") ? API_BASE : "";
    }

    // ── UI: chip + progress bar ──────────────────────────────────────────────
    function mount(host, options) {
        const opts = options || {};
        injectStyles();
        host.textContent = "";

        const chip = el("div", "up-chip");
        chip.hidden = true;
        const icon = el("span", "up-chip-icon", "📄");
        const name = el("span", "up-chip-name");
        const remove = el("button", "up-chip-remove", "✕");
        remove.type = "button";
        remove.setAttribute("aria-label", "Remove file");
        remove.title = "Remove file";
        chip.append(icon, name, remove);

        const progress = el("div", "up-progress");
        progress.hidden = true;
        const track = el("div", "up-bar");
        const fill = el("div", "up-fill");
        track.appendChild(fill);
        const label = el("div", "up-label");
        label.setAttribute("role", "status");
        label.setAttribute("aria-live", "polite");
        progress.append(track, label);

        host.append(chip, progress);

        let hideTimer = null;
        let locked = false;

        const api = {
            /** Show the chip for a chosen file (and clear any previous progress). */
            setFile(file) {
                name.textContent = file.name;
                name.title = file.name;
                chip.hidden = false;
                api.reset();
            },
            /** Hide the chip and progress bar. */
            clear() {
                chip.hidden = true;
                name.textContent = "";
                api.reset();
            },
            /** While locked the ✕ is disabled, so the file can't be removed mid-upload. */
            setLocked(value) {
                locked = !!value;
                remove.disabled = locked;
                remove.title = locked ? "Please wait — upload in progress" : "Remove file";
            },
            /** Begin: bar visible at 0%. */
            start() {
                clearTimeout(hideTimer);
                progress.hidden = false;
                api.setPercent(0);
            },
            /** File is transferring: real percentage. */
            setPercent(pct) {
                fill.classList.remove("indeterminate");
                fill.style.width = pct + "%";
                label.textContent = "Uploading your file… " + pct + "%";
            },
            /** File is on the server and Claude is working: animated bar + live stage message. */
            setProcessing(message) {
                progress.hidden = false;
                fill.classList.add("indeterminate");
                label.textContent = message || DEFAULT_PROCESSING_MESSAGE;
            },
            /** Success: full bar + message; optionally hides itself after hideAfterMs. */
            finish(message, hideAfterMs) {
                clearTimeout(hideTimer);
                progress.hidden = false;
                fill.classList.remove("indeterminate");
                fill.style.width = "100%";
                label.textContent = message || "Done!";
                if (hideAfterMs) hideTimer = setTimeout(api.reset, hideAfterMs);
            },
            /** Hide the bar and return it to 0%. */
            reset() {
                clearTimeout(hideTimer);
                progress.hidden = true;
                fill.classList.remove("indeterminate");
                fill.style.width = "0%";
                label.textContent = "";
            }
        };

        remove.addEventListener("click", () => {
            if (locked) return;
            if (opts.onRemove) opts.onRemove();
            api.clear();
        });

        return api;
    }

    // ── Transport: SSE stage messages + XHR byte progress ────────────────────
    function openStream(uploadId, onEvent) {
        let es;
        try {
            es = new EventSource(`${apiBase()}/api/materials/upload-stream/${uploadId}`, { withCredentials: true });
        } catch (e) { return null; }
        es.addEventListener("progress", (evt) => {
            let data;
            try { data = JSON.parse(evt.data); } catch (e) { return; }
            onEvent(data);
            if (data.finished) closeStream(es);
        });
        es.onerror = () => closeStream(es);   // never let EventSource auto-reconnect (it would re-subscribe the uploadId)
        return es;
    }

    function closeStream(es) {
        if (es) { try { es.close(); } catch (e) {} }
    }

    function httpError(xhr, data) {
        let message = data && data.message;
        if (!message) {
            if (xhr.status === 413) message = "File is too large.";
            else if (xhr.status === 401 || xhr.status === 403) message = "Your session may have expired — please refresh the page and try again.";
            else message = `Upload failed (status ${xhr.status}).`;
        }
        const err = new Error(message);
        err.status = xhr.status;
        return err;
    }

    // The one upload currently in flight (if any), so it can be cancelled if the user leaves the page.
    let active = null;

    /**
     * Abort the in-flight upload and ask the server to clean up whatever it already created.
     * Called automatically on page hide/unload (unless send() was given cancelOnLeave:false).
     */
    function cancelActive() {
        if (!active) return;
        const a = active;
        active = null;
        try { a.xhr.abort(); } catch (e) {}
        closeStream(a.stream);
        try {
            const payload = new Blob([JSON.stringify({ uploadId: a.uploadId, topic: a.topic })], { type: "application/json" });
            navigator.sendBeacon(`${apiBase()}/api/materials/cancel-upload`, payload);
        } catch (e) {}
    }

    window.addEventListener("pagehide", () => { if (active && active.auto) cancelActive(); });
    window.addEventListener("beforeunload", () => { if (active && active.auto) cancelActive(); });

    /**
     * POST a FormData to /api/materials/upload with full progress feedback on `bar`.
     * Resolves with the parsed JSON response. Rejects with an Error (err.cancelled === true if aborted).
     *
     * options:
     *   bar            (required) controller returned by mount()
     *   formData       (required) FormData containing the fields the endpoint expects (topic, file, …)
     *   topic          topic name, sent with the cancel beacon so the server can clean up
     *   uploadId       reuse a caller-generated id (otherwise one is created and appended to formData)
     *   url            endpoint path (default "/api/materials/upload")
     *   doneMessage    label shown on success (default "Done!")
     *   hideAfterMs    auto-hide the bar this long after success (default: stay visible)
     *   cancelOnLeave  set false if the caller already handles cancelling on page leave
     *   manageLock     set false if the caller locks/unlocks the chip itself (e.g. for a longer multi-step run)
     */
    function send(options) {
        const bar = options.bar;
        const formData = options.formData;
        const uploadId = options.uploadId || newId();
        const manageLock = options.manageLock !== false;
        if (!formData.has("uploadId")) formData.append("uploadId", uploadId);

        let sawStage = false;   // once the server reports a stage, byte-progress events must not overwrite it
        if (manageLock) bar.setLocked(true);
        bar.start();

        // Open the SSE stream BEFORE posting so it's already listening when the server starts publishing.
        const stream = openStream(uploadId, (ev) => {
            if (ev.stage === "connected" || ev.finished) return;   // the final message comes from the HTTP response
            if (ev.message) { sawStage = true; bar.setProcessing(ev.message); }
        });

        return new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();
            const entry = { uploadId, xhr, stream, topic: options.topic || "", auto: options.cancelOnLeave !== false };
            active = entry;

            const settle = () => {
                if (active === entry) active = null;
                closeStream(stream);
                if (manageLock) bar.setLocked(false);
            };

            xhr.open("POST", apiBase() + (options.url || "/api/materials/upload"), true);
            xhr.withCredentials = true;
            const csrf = (typeof getCsrfToken === "function") ? getCsrfToken() : "";
            if (csrf) xhr.setRequestHeader("X-XSRF-TOKEN", csrf);

            xhr.upload.onprogress = (e) => {
                if (sawStage || !e.lengthComputable) return;
                const pct = Math.round((e.loaded / e.total) * 100);
                if (pct >= 100) bar.setProcessing();
                else bar.setPercent(pct);
            };

            xhr.onload = () => {
                settle();
                let data = null;
                try { data = JSON.parse(xhr.responseText); } catch (e) {}
                if (xhr.status >= 200 && xhr.status < 300 && data && data.success !== false) {
                    bar.finish(options.doneMessage || "Done!", options.hideAfterMs);
                    resolve(data);
                } else {
                    bar.reset();
                    reject(httpError(xhr, data));
                }
            };
            xhr.onerror = () => { settle(); bar.reset(); reject(new Error("Could not connect to the server.")); };
            xhr.onabort = () => {
                settle(); bar.reset();
                reject(Object.assign(new Error("Upload cancelled."), { cancelled: true }));
            };

            xhr.send(formData);
        });
    }

    window.UploadProgress = { mount, send, cancelActive, newId };
})();