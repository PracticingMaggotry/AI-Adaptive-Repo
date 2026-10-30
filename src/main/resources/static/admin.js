
    let adminData = window.__adminDevSeedData || { stats: {}, users: [], topics: [], bannedEmails: [], activityLog: [], materialCategories: { categoryBreakdown: [], subCategoryBreakdown: [] } };
    let currentTab = "overview";
    let currentCategoryRange = "all"; // "week" | "month" | "all" — shared by Overview charts and the printable Report
    // Shell state (Phase 2). The hash router lives in admin/router.js, the route table in admin/routes.js.
    let adminRouter = null;      // null until the router modules load (and in admin-dev.html, which skips initAdmin)
    let adminShell = null;       // { routes, sections, buildBreadcrumb, ui } once loaded
    let adminBadgeStore = null;
    let adminStartup = null;     // boot-request hand-off (admin/startup-prefetch.js); null -> plain apiFetch everywhere
    let activeRoute = null;      // route record currently shown (null: none / not found)
    const adminSession = { user: null };

    // ── AI Testing sandbox upload cancellation (mirrors quizhub.html / learninghub.html) ──
    // Ensures an abandoned test upload (tab closed / navigated away while Claude is still
    // running) doesn't leak a Material row + MaterialContent dedup row + Question rows forever.
    let aitUploadInProgress = false;
    let aitActiveUploadId = null;















    // Leaving the page for real while a test upload is in flight — cancel and clean up.
    window.addEventListener("pagehide", aitCancelActiveUpload);
    window.addEventListener("beforeunload", () => { if (aitUploadInProgress) aitCancelActiveUpload(); });

    // ── Shell / hash-router compatibility bridge (Phase 2) ────────────────────────────────────
    // admin/routes.js and admin/router.js are ES modules. This file stays a classic script because the
    // legacy renderers depend on inline onclick handlers and shared global state, so the bridge loads
    // the modules with dynamic import() and mounts every route through the existing admin/views/*.js
    // adapters, which call the legacy renderX() functions unchanged. Routing is navigation only; it is
    // not authorization (backend controllers remain authoritative for every /api/admin call).

    function legacyRenderers() {
        return { overview: renderOverview, users: renderUsers, topics: renderTopics, bannedemails: renderBannedEmails, deletionrequests: renderDeletionRequests, reports: renderReports, activitylog: renderActivityLog, aitesting: renderAiTesting, reportedquestions: renderReportedQuestions, assignedhandouts: renderAssignedHandouts };
    }

    // Legacy entry point: inline onclick="switchTab('users')" handlers and old quick actions.
    // Accepts an old tab name ("users"), a canonical path ("/people/users") or a "#..." hash.
    function switchTab(tab) {
        if (adminRouter) { adminRouter.navigate(tab); return; }
        currentTab = tab;           // router unavailable (admin-dev.html, or its modules failed to load)
        renderCurrentTab();
    }

    // Legacy re-render used after in-place actions (suspend, ban, limits, ...). Re-renders the legacy
    // screen only; it never re-opens modals or moves focus. Sub-tab selection is restored because
    // renderTopics() always starts on Materials.
    function renderCurrentTab() {
        const renderer = legacyRenderers()[currentTab];
        if (!renderer) return;
        const result = renderer();
        const subTab = activeRoute && activeRoute.after && activeRoute.after.subTab;
        if (subTab) Promise.resolve(result).then(() => { if (activeRoute && activeRoute.after && activeRoute.after.subTab === subTab) applyRouteSubTab(subTab); }).catch((err) => console.error("Admin re-render failed:", err));
    }

    function applyRouteSubTab(subTab) {
        if (subTab && typeof setTopicsSubTab === "function") setTopicsSubTab(subTab);
    }

    // The legacy Topics screen has its own Materials/Categories buttons. Keep the URL, title, breadcrumb and
    // sidebar in step with them (replaceState, no remount) so refresh and Back land where the user is.
    function installTopicsSubTabSync() {
        const original = window.setTopicsSubTab;
        if (typeof original !== "function" || original.__shellWrapped) return;
        const wrapped = function (tab) {
            const out = original.apply(this, arguments);
            const target = { materials: "/content/materials", categories: "/content/categories" }[tab];
            if (adminRouter && activeRoute && activeRoute.legacyTab === "topics" && target && activeRoute.path !== target) adminRouter.sync(target);
            return out;
        };
        wrapped.__shellWrapped = true;
        window.setTopicsSubTab = wrapped;
    }

    function setAdminChromeVisible(visible) {
        ["adminNav", "adminBreadcrumb", "adminRouteNotice"].forEach((id) => {
            const el = document.getElementById(id);
            if (el) el.style.display = visible ? "" : "none";
        });
    }

    function showRouteNotice(notice) {
        const el = document.getElementById("adminRouteNotice");
        if (!el) return;
        el.replaceChildren();
        if (!notice) { el.hidden = true; el.className = "shell-notice"; return; }
        const label = document.createElement("strong");
        label.textContent = notice.type === "warning" ? "Warning:" : "Note:";
        el.append(label, document.createTextNode(" " + notice.text));
        el.className = "shell-notice shell-notice--" + (notice.type === "warning" ? "warning" : "info");
        el.hidden = false;
    }

    function renderBreadcrumb(match) {
        const nav = document.getElementById("adminBreadcrumb");
        if (!nav) return [];
        nav.replaceChildren();
        const trail = match ? adminShell.buildBreadcrumb(match, adminShell) : [{ label: "Page not found" }];
        const items = [{ label: "Admin", path: "/overview" }, ...trail];
        const ol = document.createElement("ol");
        items.forEach((item, i) => {
            const li = document.createElement("li");
            if (item.path && i < items.length - 1) {
                const a = document.createElement("a");
                a.href = "#" + item.path;
                a.textContent = item.label;
                li.append(a);
            } else {
                li.textContent = item.label;
                if (i === items.length - 1) li.setAttribute("aria-current", "page");
            }
            ol.append(li);
        });
        nav.append(ol);
        return items;
    }

    function applyRouteChrome(match) {
        const route = match ? match.route : null;
        activeRoute = route;
        currentTab = (route && route.legacyTab) || "";
        const crumbs = renderBreadcrumb(match);
        const title = route ? crumbs[crumbs.length - 1].label : "Page not found";
        const heading = document.getElementById("adminPageTitle");
        if (heading) heading.textContent = title;
        document.title = title + " | Admin Panel | Learning System";
        const navPath = route ? (route.navPath || route.path) : null;
        document.querySelectorAll("#adminNav a[data-route]").forEach((a) => {
            const on = a.dataset.route === navPath;
            a.classList.toggle("active", on);
            if (on) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
        });
        showRouteNotice(route && route.after ? route.after.notice : null);
        closeAdminNavDrawer(false);
    }

    function leaveRoute(match) {
        const route = match.route;
        if (route.after && route.after.modal) closeModal();   // modal opened by this route (Usage limits)
        // Same protection the old sidebar links gave: do not orphan an in-flight AI Testing sandbox upload.
        if (route.legacyTab === "aitesting" && aitUploadInProgress) aitCancelActiveUpload();
    }

    function focusRouteTarget(selector) {
        const root = document.getElementById("adminContent");
        const target = root && root.querySelector(selector);
        if (!target) return false;
        document.querySelectorAll("[data-route-focus]").forEach((n) => n.removeAttribute("data-route-focus"));
        if (!target.matches("a[href], button, input, select, textarea, [tabindex]")) target.setAttribute("tabindex", "-1");
        target.setAttribute("data-route-focus", "true");
        target.addEventListener("blur", () => target.removeAttribute("data-route-focus"), { once: true });
        target.focus({ preventScroll: true });
        target.scrollIntoView({ block: "start" });
        return true;
    }

    // Runs after the legacy renderer has finished: sub-tab, modal, focus. Never re-runs on legacy re-renders.
    function finishRoute(match, info) {
        const after = match.route.after || {};
        if (after.subTab) applyRouteSubTab(after.subTab);
        if (after.modal) {
            const open = window[after.modal];
            if (typeof open === "function") Promise.resolve(open()).catch((err) => console.error("Could not open " + after.modal + ":", err));
        }
        let focused = false;
        if (after.focus) focused = focusRouteTarget(after.focus);
        if (!focused && !after.modal && !(info && info.initial)) {
            const heading = document.getElementById("adminPageTitle");
            if (heading) heading.focus({ preventScroll: true });
        }
    }

    function renderRouteNotFound(el, resolved) {
        const card = document.createElement("section");
        card.className = "admin-card";
        const title = document.createElement("h2");
        title.className = "admin-card-title";
        title.textContent = "Page not found";
        const hint = document.createElement("p");
        hint.className = "admin-card-hint";
        hint.textContent = "No admin page matches " + (resolved && resolved.path ? resolved.path : "this address") + ".";
        const link = document.createElement("a");
        link.className = "btn primary";
        link.href = "#/overview";
        link.textContent = "Go to Overview";
        card.append(title, hint, link);
        el.replaceChildren(card);
    }

    function showRouteError(error, match, phase) {
        console.error("Admin route " + (match ? match.route.path : "(none)") + " failed during " + phase + ":", error);
        showRouteNotice({ type: "warning", text: "This page did not load correctly (" + phase + "): " + ((error && error.message) || "unknown error") + ". Reload the page or try again." });
        const content = document.getElementById("adminContent");
        if (content && content.childElementCount === 0) {
            const empty = document.createElement("div");
            empty.className = "empty-state";
            empty.textContent = "This page could not be displayed.";
            content.append(empty);
        }
    }

    function paintNavBadge(key, count) {
        if (!/^[\w.-]+$/.test(key)) return;
        document.querySelectorAll('[data-badge-key="' + key + '"]').forEach((badge) => {
            if (count > 0) { badge.textContent = count; badge.style.display = "inline-block"; }
            else badge.style.display = "none";
        });
    }

    // Command palette (Ctrl+K / Cmd+K). Optional enhancement: it lives in admin/search/*, owns its own DOM and
    // keyboard handling, and reads the same route registry as the sidebar. A failure to load it must never
    // affect the router, so every error is contained here and the trigger button simply stays hidden.
    async function initAdminCommandPalette(routesModule) {
        try {
            const { initCommandPalette } = await import("./admin/search/command-palette.js");
            initCommandPalette({
                routes: routesModule.routes,
                sections: routesModule.sections,
                aliases: routesModule.aliases,
                navigate: (target) => adminRouter && adminRouter.navigate(target),
                resolve: (target) => (adminRouter ? adminRouter.resolve(target) : null),
                getCurrentPath: () => (adminRouter && adminRouter.current() ? adminRouter.current().path : null),
                actions: [{
                    id: "reload-current-page",
                    title: "Reload current page",
                    description: "Re-render the page you are on with fresh data",
                    keywords: ["refresh", "reload", "rerender", "update"],
                    run: () => (adminRouter ? adminRouter.reload() : undefined)
                }],
                trigger: document.getElementById("adminSearchTrigger")
            });
        } catch (err) {
            console.error("Admin command palette failed to start:", err);
        }
    }

    async function startAdminShell() {
        try {
            const [routerModule, routesModule, uiModule, badgesModule] = await Promise.all([
                import("./admin/router.js"), import("./admin/routes.js"), import("./admin/ui.js"), import("./admin/badges.js")
            ]);
            adminShell = { routes: routesModule.routes, sections: routesModule.sections, buildBreadcrumb: routerModule.buildBreadcrumb, ui: uiModule.default };
            adminBadgeStore = badgesModule.createBadgeStore(paintNavBadge);
            installTopicsSubTabSync();
            adminRouter = routerModule.createRouter({
                routes: routesModule.routes,
                aliases: routesModule.aliases,
                defaultPath: routesModule.defaultPath,
                mount: document.getElementById("adminContent"),
                hooks: {
                    getContext: () => ({ api: { get: (path, options) => (adminStartup ? adminStartup.get(path, options) : apiFetch(path, options)), request: (path, options) => (adminStartup ? adminStartup.request(path, options) : apiFetch(path, options)) }, ui: adminShell.ui, session: adminSession, badges: adminBadgeStore }),
                    onRouteChange: applyRouteChrome,
                    beforeLeave: leaveRoute,
                    afterMount: (match, ctx, info) => finishRoute(match, info),
                    onNotFound: renderRouteNotFound,
                    onError: showRouteError
                }
            });
            await adminRouter.start();
            initAdminCommandPalette(routesModule);
        } catch (err) {
            console.error("Admin shell failed to start:", err);
            adminRouter = null;
            currentTab = "overview";
            renderCurrentTab();
            showRouteNotice({ type: "warning", text: "Navigation failed to load, so the sidebar links will not work. Reload the page. Only the Overview is shown." });
        }
    }

    async function setCategoryRange(range) {
        if (range === currentCategoryRange) return;
        currentCategoryRange = range;
        try {
            adminData.materialCategories = await apiFetch(`/api/admin/material-categories?range=${range}`);
        } catch (err) {
            adminData.materialCategories = { categoryBreakdown: [], subCategoryBreakdown: [] };
        }
        renderCurrentTab();
    }

    async function fetchActivityLog() {
        try {
            const resp = await apiFetch("/api/admin/activity-log");
            return resp.log || [];
        } catch (err) {
            console.error("Could not load activity log:", err);
            return [];
        }
    }

    async function refreshActivityLog() {
        adminData.activityLog = await fetchActivityLog();
    }

    async function refreshUsers() {
        try {
            const resp = await apiFetch("/api/admin/users");
            adminData.users = resp.users || [];
            adminData.flaggedUsers = adminData.users.filter(u => u.flagged).map(u => u.email);
            refreshFlaggedBadge();
        } catch (err) {
            console.error("Could not refresh users:", err);
        }
    }

    async function initAdmin() {
        try {
            const me = await apiFetch("/api/me");
            if (me?.name) document.getElementById("topbarName").textContent = me.name.split(" ")[0];
            applyAvatarInitial(me?.name);
            adminSession.user = me;

            if (!me?.isAdmin) {
                document.getElementById("adminContent").innerHTML = `
        <div class="access-denied">
          <div style="font-size:2rem;margin-bottom:10px;">🚫</div>
          <h2 style="margin-bottom:8px;">Admin access required</h2>
          <p style="margin-bottom:16px;">This account doesn't have admin permissions.</p>
          <a class="btn primary" href="dashboard.html">← Back to Dashboard</a>
        </div>`;
                setAdminChromeVisible(false);
                return;
            }

            // The classic `adminData` global (Users view, legacy actions) still needs these six lists. Load them all in
            // parallel, and register each request with the hand-off so the Overview view reuses it once instead of
            // asking the server for the same data again. If the module cannot load we simply use plain requests.
            try {
                adminStartup = (await import("./admin/startup-prefetch.js")).createStartupPrefetch((path, options) => apiFetch(path, options));
            } catch (err) {
                console.warn("Startup hand-off unavailable, using plain requests:", err);
                adminStartup = null;
            }
            const preload = (path) => (adminStartup ? adminStartup.prefetch(path) : apiFetch(path));

            const [overview, usersResp, topicsResp, materialCatResp, bannedResp, logResp] = await Promise.all([
                preload("/api/admin/overview").catch(() => ({ stats: {} })),
                preload("/api/admin/users").catch(() => ({ users: [] })),
                preload("/api/admin/topics").catch(() => []),
                preload(`/api/admin/material-categories?range=${currentCategoryRange}`).catch(() => ({ categoryBreakdown: [], subCategoryBreakdown: [] })),
                preload("/api/admin/banned-emails").catch((err) => { console.error("Could not load banned emails:", err); return {}; }),
                preload("/api/admin/activity-log").catch((err) => { console.error("Could not load activity log:", err); return {}; })
            ]);

            adminData.stats = overview.stats || {};
            adminData.users = usersResp.users || [];
            adminData.topics = Array.isArray(topicsResp) ? topicsResp : [];
            adminData.materialCategories = materialCatResp || { categoryBreakdown: [], subCategoryBreakdown: [] };

            adminData.bannedEmails = (bannedResp && bannedResp.bannedEmails) || [];
            adminData.flaggedUsers = adminData.users.filter(u => u.flagged).map(u => u.email);
            adminData.activityLog = (logResp && logResp.log) || [];

            refreshReportedBadge();
            refreshFlaggedBadge();
            refreshDeletionBadge();

            await startAdminShell();

        } catch (err) {
            if (err && err.status === 401) { window.location.href = "/login.html"; return; }
            document.getElementById("adminContent").innerHTML = `
        <div class="access-denied">
          <div style="font-size:2rem;margin-bottom:10px;">⚠️</div>
          <h2 style="margin-bottom:8px;">Could not load admin data</h2>
          <p style="margin-bottom:16px;">We couldn't reach the server just now. Please refresh or try again shortly.</p>
        </div>`;
            setAdminChromeVisible(false);
        }
    }






























































































































    let categoryChartInstance = null;
    let subCategoryChartInstance = null;

    const CHART_PALETTE = ["#0093ad","#7c3aed","#f59e0b","#10b981","#ef4444","#3b82f6","#ec4899","#14b8a6","#a855f7","#64748b"];

























































    let userFlagFilter = "all"; // "all" | "flagged" | "notflagged"




























































































































































































































































    const ACTION_TYPE_LABELS = {
        "material-upload":     "Material Uploads",
        "quiz-submit":         "Quiz Submissions",
        "adapted-quiz":        "Adapted Quizzes",
        "targeted-quiz":       "Target Problems",
        "question-report":     "Question Reports",
        "lesson-regeneration": "Lesson Regenerations"
    };

























































































































































































































































































































































































































































































































































































































































































































































    // ── Topics & Content sub-tabs: "Uploaded Materials" (existing content review) vs "Categories" (CRUD) ──
    let topicsSubTab = "materials";











    let categoriesData = [];








































































































































































































































































































































    // ── Deletion requests (dual control) ──

    let deletionStatusFilter = "PENDING";































































































































































































































































































































































































































































    let reportedQuestionsData = [];
    let reportedStatusFilter = "PENDING";



































































    const REASON_LABELS = {
        WRONG_ANSWER: "❌ Wrong answer",
        MISLEADING:   "❓ Misleading / confusing",
        OUT_OF_SCOPE: "📎 Out of scope",
        DUPLICATE:    "♻️ Duplicate",
        OTHER:        "💬 Other"
    };

    const STATUS_BADGE = {
        PENDING:   '<span class="badge-flagged">Pending</span>',
        FIXED:     '<span class="badge-active">Fixed</span>',
        DISMISSED: '<span class="role-badge role-student">Dismissed</span>',
        DELETED:   '<span class="badge-blocked">Deleted</span>'
    };
























































































































































































































































































































    // ── Assigned Handouts: study participants can't upload, so the admin uploads for them ──














































































































































































































































































































































































































































































































































































































































































































































































































    document.getElementById("modal").addEventListener("click", function(e) {
        if (e.target === this) closeModal();
    });


























    // Narrow screens (<= 900px): the sidebar is an off-canvas drawer opened from the topbar menu button.
    // On wider screens the sidebar is always visible and these controls are hidden by admin-shell.css.
    function setAdminNavOpen(open) {
        document.body.classList.toggle("admin-nav-open", open);
        const btn = document.getElementById("adminNavToggle");
        if (btn) {
            btn.setAttribute("aria-expanded", String(open));
            btn.setAttribute("aria-label", open ? "Close navigation menu" : "Open navigation menu");
        }
    }

    function closeAdminNavDrawer(restoreFocus) {
        if (!document.body.classList.contains("admin-nav-open")) return;
        setAdminNavOpen(false);
        if (restoreFocus) document.getElementById("adminNavToggle")?.focus();
    }

    (function initAdminNavDrawer() {
        const btn = document.getElementById("adminNavToggle");
        const sidebar = document.getElementById("adminSidebar");
        if (!btn || !sidebar) return;

        btn.addEventListener("click", () => {
            const open = !document.body.classList.contains("admin-nav-open");
            setAdminNavOpen(open);
            if (open) (sidebar.querySelector('a[aria-current="page"]') || sidebar.querySelector("a[data-route]"))?.focus();
        });
        document.getElementById("adminNavBackdrop")?.addEventListener("click", () => closeAdminNavDrawer(true));
        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape" && document.body.classList.contains("admin-nav-open")) closeAdminNavDrawer(true);
        });
        // Clicking the link for the page you are already on does not change the route, so close here too.
        sidebar.querySelectorAll("a[data-route]").forEach((a) => a.addEventListener("click", () => closeAdminNavDrawer(false)));
        if (window.matchMedia) {
            const wide = window.matchMedia("(min-width: 901px)");
            const onChange = (e) => { if (e.matches) setAdminNavOpen(false); };
            if (wide.addEventListener) wide.addEventListener("change", onChange); else if (wide.addListener) wide.addListener(onChange);
        }
    })();

    (function initAdminSkipLink() {
        // Not a real hash link: a bare "#" must never reach the hash router.
        document.getElementById("adminSkipLink")?.addEventListener("click", (e) => {
            e.preventDefault();
            document.getElementById("adminContent")?.focus();
        });
    })();

    document.querySelectorAll(".logout").forEach(link => {
        link.addEventListener("click", async function(e) {
            e.preventDefault();
            try { await apiFetch("/logout", { method: "POST" }); } catch(err) {}
            window.location.href = "/login.html";
        });
    });

    // Navigating away via the sidebar (or logging out) while an AI Testing sandbox upload is
    // still in flight — cancel it first so it isn't left orphaned server-side.
    document.querySelectorAll(".sidebar a[href]").forEach(link => {
        // In-app route links (a[data-route]) stay on this page; the router's beforeLeave hook cancels
        // an in-flight AI Testing upload only when the route really changes (also on Back/Forward).
        if (link.hasAttribute("data-route")) return;
        link.addEventListener("click", function() {
            if (aitUploadInProgress) aitCancelActiveUpload();
        }, true);
    });

    let crAllMaterials = [];
    let crActiveFilter = "all";
    let crFindMatches = [];
    let crFindIdx = 0;
    let crCurrentMaterialId = null;
    let crViewMode = 'text';          // 'text' | 'pdf'
    let crLastReviewData = null;      // last /content JSON response, reused when toggling back to Text
    let crPdfObjectUrl = null;        // revoked on close / re-open so blob URLs don't leak



























































































































































































































































































































































    document.addEventListener("click", e => {
        const modal = document.getElementById("crModal");
        if (modal && e.target === modal) crCloseModal();
    });
    document.addEventListener("keydown", e => {
        // #crModal only exists on the Topics screen; without this guard Escape threw on every other page.
        if (e.key === "Escape" && document.getElementById("crModal")) crCloseModal();
    });













































































    if (!window.__ADMIN_DEV__) initAdmin();
