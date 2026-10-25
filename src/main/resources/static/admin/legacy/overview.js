/* Generated from the pre-split admin script during the behavior-preserving extraction.
 * Keep these compatibility functions in this tab-owned file until its ES module is
 * independently implemented and regression-tested. Do not add top-level let/const
 * declarations here; shared state remains in ../admin.js.
 */

    function renderOverview() {
        const s = adminData.stats;
        const bannedCount = (adminData.bannedEmails || []).length;
        const suspendedCount = (adminData.users || []).filter(u => u.archived).length;
        const flaggedCount = (adminData.flaggedUsers || []).length;
        const deletedCount = (adminData.activityLog || []).filter(e => e.type === "delete").length;
        const mc = adminData.materialCategories || { categoryBreakdown: [], subCategoryBreakdown: [] };

        const tiles = [
            { icon: "👤", value: s.totalUsers ?? 0, label: "Registered Users" },
            { icon: "📚", value: s.totalTopics ?? 0, label: "Topics" },
            { icon: "❓", value: s.totalQuestions ?? 0, label: "Questions" },
            { icon: "📝", value: s.totalAttempts ?? 0, label: "Quiz Attempts" },
            { icon: "📄", value: s.totalMaterials ?? 0, label: "Uploaded Materials" },
        ];

        document.getElementById("adminContent").innerHTML = `
    <div class="admin-tiles">
      ${tiles.map(t => `
        <div class="admin-tile">
          <div class="admin-tile-icon">${t.icon}</div>
          <div class="admin-tile-value">${t.value}</div>
          <div class="admin-tile-label">${t.label}</div>
        </div>`).join("")}
    </div>

    <div class="stats-2col">
      <div class="admin-card">
        <div class="admin-card-title">🔒 Security Summary</div>
        <div class="admin-card-hint">Current security status of the platform.</div>
        <div style="display:flex;flex-direction:column;gap:12px;">
          <div style="display:flex;justify-content:space-between;align-items:center;padding:10px 12px;background:#f8fafc;border-radius:10px;border:1px solid #e2e8f0;">
            <span style="font-size:.85rem;font-weight:600;">Banned Emails</span>
            <span class="badge-blocked">${bannedCount} banned</span>
          </div>
          <div style="display:flex;justify-content:space-between;align-items:center;padding:10px 12px;background:#f8fafc;border-radius:10px;border:1px solid #e2e8f0;">
            <span style="font-size:.85rem;font-weight:600;">Suspended Accounts</span>
            <span class="${suspendedCount > 0 ? 'badge-flagged' : 'badge-active'}">${suspendedCount} suspended</span>
          </div>
          <div style="display:flex;justify-content:space-between;align-items:center;padding:10px 12px;background:#f8fafc;border-radius:10px;border:1px solid #e2e8f0;">
            <span style="font-size:.85rem;font-weight:600;">Flagged Users</span>
            <span class="${flaggedCount > 0 ? 'badge-flagged' : 'badge-active'}">${flaggedCount} flagged</span>
          </div>
          <div style="display:flex;justify-content:space-between;align-items:center;padding:10px 12px;background:#f8fafc;border-radius:10px;border:1px solid #e2e8f0;">
            <span style="font-size:.85rem;font-weight:600;">Deleted Accounts</span>
            <span class="badge-blocked">${(deletedCount)} deleted</span>
          </div>
        </div>
      </div>

      <div class="admin-card">
        <div class="admin-card-title">⚡ Quick Actions</div>
        <div class="admin-card-hint">Common administrative tasks.</div>
        <div style="display:flex;flex-direction:column;gap:8px;">
          <button class="btn primary" onclick="switchTab('users')" style="text-align:left;">👥 Manage Users</button>
          <button class="btn primary" onclick="switchTab('bannedemails')" style="text-align:left;">🚫 Ban an Email</button>
          <button class="btn primary" onclick="switchTab('deletionrequests')" style="text-align:left;">🗑 Review Deletion Requests</button>
          <button class="btn primary" onclick="switchTab('topics')" style="text-align:left;">📚 Review Uploaded Topics</button>
          <button class="btn primary" onclick="switchTab('reports')" style="text-align:left;background:#7c3aed;border-color:#7c3aed;">📋 Generate & Print Report</button>
        </div>
      </div>
    </div>

    <div class="admin-card">
      <div class="admin-card-title">📊 Platform Activity Overview</div>
      <div class="admin-card-hint">Real-time view of platform usage and content metrics.</div>
      <div class="stats-3col">
        ${[
            { label: "Questions per Topic", value: s.totalTopics ? Math.round(s.totalQuestions / s.totalTopics) : 0, bar: 60 },
            { label: "Avg Attempts per User", value: s.totalUsers ? Math.round(s.totalAttempts / s.totalUsers) : 0, bar: 40 },
            { label: "Materials per User", value: s.totalUsers ? (s.totalMaterials / s.totalUsers).toFixed(1) : 0, bar: 30 }
        ].map(m => `
          <div style="padding:12px;background:#f8fafc;border-radius:10px;border:1px solid #e2e8f0;">
            <div style="font-size:.72rem;color:#6b7280;font-weight:600;text-transform:uppercase;letter-spacing:.04em;">${m.label}</div>
            <div style="font-size:1.4rem;font-weight:900;color:#111827;margin:4px 0;">${m.value}</div>
            <div class="mini-bar"><div class="mini-bar-fill" style="width:${m.bar}%;background:#0093ad;"></div></div>
          </div>`).join("")}
      </div>
    </div>

    <div class="admin-card">
      <div class="report-header" style="margin-bottom:4px;">
        <div>
          <div class="admin-card-title">🏷 Material Categorization</div>
          <div class="admin-card-hint">How uploaded materials break down by auto-assigned subject category and sub-topic.</div>
        </div>
        <div class="range-filter" id="overviewRangeFilter"></div>
      </div>
    </div>

    <div class="stats-2col">
      <div class="admin-card">
        <div class="admin-card-title">🏷 Most Uploaded Category</div>
        <div class="admin-card-hint">${mc.topCategory ? `Top category (${rangeLabel(currentCategoryRange)}): <strong>${escHtmlGlobal(mc.topCategory)}</strong>` : "How uploaded materials break down by auto-assigned subject category."}</div>
        ${mc.categoryBreakdown && mc.categoryBreakdown.some(c => c.count > 0) ? `
        <div style="position:relative;height:190px;"><canvas id="categoryChart"></canvas></div>` : `
        <div class="empty-state">No categorized materials for this range yet.</div>`}
      </div>

      <div class="admin-card">
        <div class="admin-card-title">📌 Most Uploaded Sub-Category</div>
        <div class="admin-card-hint">${mc.topSubCategory ? `Top sub-category (${rangeLabel(currentCategoryRange)}): <strong>${escHtmlGlobal(mc.topSubCategory)}</strong>` : "Specific subjects within categories, as auto-detected from uploads."}</div>
        ${mc.subCategoryBreakdown && mc.subCategoryBreakdown.length > 0 ? `
        <div style="position:relative;height:190px;"><canvas id="subCategoryChart"></canvas></div>` : `
        <div class="empty-state">No sub-categories detected for this range yet.</div>`}
      </div>
    </div>`;

        renderRangeFilterInto("overviewRangeFilter");
        drawCategoryCharts(mc);
    }
