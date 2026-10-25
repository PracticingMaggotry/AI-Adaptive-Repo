/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    function aitCancelActiveUpload() {
        if (!aitUploadInProgress) return;
        const uploadId = aitActiveUploadId;
        const topic = window.__aitLastTopic || "";
        aitUploadInProgress = false;
        aitActiveUploadId = null;
        if (uploadId) {
            try {
                const payload = new Blob([JSON.stringify({ uploadId, topic })], { type: "application/json" });
                navigator.sendBeacon(`${API_BASE}/api/materials/cancel-upload`, payload);
            } catch (e) {}
        }
    }

    function renderRangeFilterInto(targetId) {
        const el = document.getElementById(targetId);
        if (!el) return;
        const options = [
            { key: "week", label: "This Week" },
            { key: "month", label: "This Month" },
            { key: "all", label: "All Time" }
        ];
        el.innerHTML = options.map(o => `
            <button class="range-filter-btn ${currentCategoryRange === o.key ? 'active' : ''}"
                    onclick="setCategoryRange('${o.key}')">${o.label}</button>`).join("");
    }

    function drawCategoryCharts(mc) {
        if (categoryChartInstance) { categoryChartInstance.destroy(); categoryChartInstance = null; }
        if (subCategoryChartInstance) { subCategoryChartInstance.destroy(); subCategoryChartInstance = null; }

        const catCanvas = document.getElementById("categoryChart");
        if (catCanvas && mc.categoryBreakdown) {
            const nonZero = mc.categoryBreakdown.filter(c => c.count > 0);
            categoryChartInstance = new Chart(catCanvas.getContext("2d"), {
                type: "bar",
                data: {
                    labels: nonZero.map(c => c.category),
                    datasets: [{
                        label: "Materials",
                        data: nonZero.map(c => c.count),
                        backgroundColor: nonZero.map((_, i) => CHART_PALETTE[i % CHART_PALETTE.length]),
                        borderRadius: 6
                    }]
                },
                options: {
                    indexAxis: "y",
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: { legend: { display: false } },
                    scales: {
                        x: { beginAtZero: true, ticks: { precision: 0 } },
                        y: { ticks: { font: { size: 10 } } }
                    }
                }
            });
        }

        const subCanvas = document.getElementById("subCategoryChart");
        if (subCanvas && mc.subCategoryBreakdown && mc.subCategoryBreakdown.length > 0) {
            subCategoryChartInstance = new Chart(subCanvas.getContext("2d"), {
                type: "doughnut",
                data: {
                    labels: mc.subCategoryBreakdown.map(s => s.subCategory),
                    datasets: [{
                        data: mc.subCategoryBreakdown.map(s => s.count),
                        backgroundColor: mc.subCategoryBreakdown.map((_, i) => CHART_PALETTE[i % CHART_PALETTE.length]),
                        borderWidth: 2,
                        borderColor: "#fff"
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    cutout: "55%",
                    plugins: {
                        legend: { position: "bottom", labels: { font: { size: 10 }, boxWidth: 10 } }
                    }
                }
            });
        }
    }

    function showModal(html) {
        const m = document.getElementById("modal");
        m.style.display = "flex";
        m.style.cssText = "position:fixed;inset:0;background:rgba(15,23,42,0.5);z-index:1000;display:flex;align-items:center;justify-content:center;padding:20px;";
        m.innerHTML = `<div class="modal-box">${html}</div>`;
    }

    function closeModal() {
        document.getElementById("modal").style.display = "none";
    }

    function showInfoModal(message, type = "info") {
        const variants = {
            info:    { icon: "ℹ️", title: "Notice",  color: "#1d4ed8", bg: "#eff6ff", border: "#bfdbfe" },
            success: { icon: "✅", title: "Success", color: "#059669", bg: "#f0fdf4", border: "#bbf7d0" },
            error:   { icon: "⚠️", title: "Error",   color: "#dc2626", bg: "#fef2f2", border: "#fecaca" }
        };
        const v = variants[type] || variants.info;
        showModal(`
    <div class="modal-title" style="display:flex;align-items:center;gap:8px;">
      <span style="font-size:1.2rem;">${v.icon}</span> ${v.title}
    </div>
    <div class="modal-body" style="background:${v.bg};border:1px solid whitesmoke ${v.border};border-radius:10px;padding:12px 14px;color:${v.color === "#1d4ed8" ? "#1e3a8a" : v.color === "#059669" ? "#065f46" : "#7f1d1d"};margin-bottom:18px;">
      ${escHtmlGlobal(message)}
    </div>
    <div class="modal-actions">
      <button class="btn primary" style="background:${v.color};border-color:${v.color};width:100%;" onclick="closeModal()">OK</button>
    </div>`);
    }

    function escHtmlGlobal(str) {
        return String(str ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
    }
