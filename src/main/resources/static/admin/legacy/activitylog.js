/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    function renderActivityLog() {
        const log = adminData.activityLog || [];

        const iconMap = {
            "delete": { icon: "🗑", bg: "#fee2e2" },
            "delete-topic": { icon: "📚", bg: "#fee2e2" },
            "delete-question": { icon: "🚩", bg: "#fee2e2" },
            "block-ip": { icon: "🚫", bg: "#fef3c7" },
            "unblock": { icon: "✓", bg: "#d1fae5" },
            "flag": { icon: "⚑", bg: "#fef3c7" },
            "unflag": { icon: "✓", bg: "#d1fae5" },
            "promote": { icon: "⬆", bg: "#ede9fe" },
            "ban-email": { icon: "🚫", bg: "#fee2e2" },
            "unban-email": { icon: "✓", bg: "#d1fae5" },
            "archive": { icon: "⏸", bg: "#fef3c7" },
            "unarchive": { icon: "✓", bg: "#d1fae5" },
            "request-deletion": { icon: "🗑", bg: "#fef3c7" },
            "deletion-approved": { icon: "✅", bg: "#d1fae5" },
            "deletion-rejected": { icon: "✕", bg: "#fee2e2" },
            "deletion-cancelled": { icon: "↩", bg: "#f1f5f9" },
        };

        document.getElementById("adminContent").innerHTML = `
    <div class="admin-card">
      <div class="report-header">
        <div>
          <div class="admin-card-title">🕐 Admin Activity Log</div>
          <div class="admin-card-hint">A permanent, shared record of moderation actions — visible to every admin, regardless of who performed them or which device they used. ${log.length} entries.</div>
        </div>
      </div>
      ${log.length === 0 ? `<div class="empty-state">No admin actions recorded yet.</div>` :
            log.map(entry => {
                const info = iconMap[entry.type] || { icon: "📝", bg: "#f1f5f9" };
                return `<div class="log-row">
          <div class="log-icon" style="background:${info.bg};">${info.icon}</div>
          <div class="log-body">
            <div class="log-title">${escHtmlGlobal(entry.message)}</div>
            ${entry.detail ? `<div class="log-sub">${escHtmlGlobal(entry.detail)}</div>` : ""}
            ${entry.performedBy ? `<div class="log-sub">by ${escHtmlGlobal(entry.performedBy)}</div>` : ""}
          </div>
          <div class="log-time">${entry.time ? new Date(entry.time).toLocaleString() : "—"}</div>
        </div>`;
            }).join("")}
    </div>`;
    }
