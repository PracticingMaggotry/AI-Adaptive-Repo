/**
 * progress-log.js - live, step-by-step server progress for long AI requests
 * (Target Problems, Adapted Quiz). Requires api.js (API_BASE, apiFetch).
 *
 * The server publishes real stages over SSE (/api/materials/upload-stream/{id})
 * while it works. This shows them as a timestamped log inside a modal.
 *
 * Usage:
 *   const result = await ProgressLog.run({
 *       title: "Building Target Problems", topic, endpoint: "/api/quiz/targeted"
 *   });
 *   // result = the JSON response, or { success:false, message } on error. Never throws.
 */
(function () {
    "use strict";
    if (window.ProgressLog) return;

    function esc(v) {
        return String(v ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" }[c]));
    }

    const CSS = `
.pl-fill{height:100%;width:0;background:linear-gradient(90deg,#3b82f6,#8b5cf6);border-radius:99px;transition:width .4s ease}
.pl-fill.indeterminate{width:28%!important;animation:pl-slide 1.15s ease-in-out infinite}
@keyframes pl-slide{0%{margin-left:-28%}100%{margin-left:100%}}
@media (prefers-reduced-motion:reduce){.pl-fill.indeterminate{animation:none;width:100%!important;opacity:.55}}
`;

    function injectStyles() {
        if (document.getElementById("progress-log-styles")) return;
        const s = document.createElement("style");
        s.id = "progress-log-styles";
        s.textContent = CSS;
        document.head.appendChild(s);
    }

    async function run(opts) {
        injectStyles();
        const requestId = (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random());

        const old = document.getElementById("progressLogModal");
        if (old) old.remove();
        const modal = document.createElement("div");
        modal.id = "progressLogModal";
        modal.style.cssText = "position:fixed;inset:0;background:rgba(15,23,42,0.55);z-index:10000;display:flex;align-items:center;justify-content:center;padding:20px;";
        modal.innerHTML = `
            <div style="background:#fff;border-radius:20px;padding:24px;max-width:540px;width:100%;box-shadow:0 24px 60px rgba(0,0,0,0.2);">
                <div style="font-size:1rem;font-weight:800;color:#111827;margin-bottom:4px;">${esc(opts.title || "Working...")}</div>
                <div style="font-size:0.8rem;color:#6b7280;margin-bottom:12px;">Topic: <strong>${esc(opts.topic)}</strong></div>
                <div style="height:6px;background:#e5e7eb;border-radius:99px;overflow:hidden;"><div class="pl-fill indeterminate" id="plFill"></div></div>
                <div id="plLog" style="margin-top:12px;max-height:280px;overflow-y:auto;font-size:0.78rem;line-height:1.5;border:1px solid #e2e8f0;border-radius:10px;padding:10px 12px;background:#f8fafc;"></div>
                <div id="plFinal" style="display:none;margin-top:12px;font-size:0.82rem;padding:9px 12px;border-radius:8px;"></div>
                <div style="margin-top:14px;text-align:right;"><button id="plClose" class="btn ghost" style="display:none;">Close</button></div>
            </div>`;
        document.body.appendChild(modal);

        const log = modal.querySelector("#plLog");
        const fill = modal.querySelector("#plFill");
        const finalEl = modal.querySelector("#plFinal");
        const closeBtn = modal.querySelector("#plClose");
        closeBtn.onclick = () => modal.remove();
        const t0 = Date.now();
        let lastRow = null;

        function addLine(text) {
            if (lastRow) lastRow.querySelector(".pl-mark").textContent = "✓";
            const row = document.createElement("div");
            row.style.cssText = "display:flex;gap:8px;padding:2px 0;";
            const sec = ((Date.now() - t0) / 1000).toFixed(1);
            row.innerHTML = `<span class="pl-mark" style="width:14px;flex-shrink:0;">⏳</span>` +
                `<span style="color:#9ca3af;flex-shrink:0;">${sec}s</span><span style="color:#374151;">${esc(text)}</span>`;
            log.appendChild(row);
            log.scrollTop = log.scrollHeight;
            lastRow = row;
        }

        function finish(ok, message) {
            if (lastRow) lastRow.querySelector(".pl-mark").textContent = ok ? "✓" : "✕";
            fill.classList.remove("indeterminate");
            fill.style.width = "100%";
            fill.style.background = ok ? "#10b981" : "#ef4444";
            finalEl.style.display = "block";
            finalEl.style.background = ok ? "#f0fdf4" : "#fef2f2";
            finalEl.style.border = ok ? "1px solid #bbf7d0" : "1px solid #fecaca";
            finalEl.style.color = ok ? "#065f46" : "#991b1b";
            finalEl.textContent = (ok ? "✅ " : "❌ ") + message;
            if (!ok) closeBtn.style.display = "inline-flex";
        }

        let es = null;
        try {
            es = new EventSource(`${API_BASE}/api/materials/upload-stream/${requestId}`, { withCredentials: true });
            es.addEventListener("progress", (evt) => {
                let d; try { d = JSON.parse(evt.data); } catch (_) { return; }
                if (d.stage === "connected" || d.finished) return;
                if (d.message) addLine(d.message);
            });
            es.onerror = () => { try { es.close(); } catch (_) {} };
            // Wait for the stream to open so no early stage is missed.
            await new Promise(res => { es.onopen = res; setTimeout(res, 1500); });
        } catch (_) {}

        addLine("Request sent to the server...");
        try {
            const result = await apiFetch(opts.endpoint, {
                method: "POST",
                body: JSON.stringify({ topic: opts.topic, requestId })
            });
            if (es) es.close();
            finish(!!result.success, (result.message || (result.success ? "Done." : "Failed.")) + (result.success ? " Redirecting..." : ""));
            return result;
        } catch (err) {
            if (es) es.close();
            const message = err.message || "Request failed.";
            finish(false, message);
            return { success: false, message };
        }
    }

    window.ProgressLog = { run };
})();