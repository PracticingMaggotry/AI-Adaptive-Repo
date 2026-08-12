/**
 * mobile-nav.js
 *
 * Injects a bottom tab bar (see mobile-nav.css) as a genuinely different
 * navigation surface for small screens — not a squeezed copy of the
 * desktop sidebar in dashboard.css.
 *
 * Include on student-facing pages only, after api.js and mobile-nav.css:
 *   <link rel="stylesheet" href="mobile-nav.css" />
 *   ...
 *   <script src="api.js"></script>
 *   <script src="mobile-nav.js"></script>
 *
 * Deliberately does NOT read anything from the server-side
 * DeviceDetector/DeviceDetectionInterceptor — these pages are served as
 * static files, not rendered per-request, so that classification never
 * reaches the browser. This script re-decides on the client instead,
 * using viewport width (matching the mobile-nav.css breakpoint) as the
 * single source of truth so CSS and JS never disagree about what
 * "mobile" means on this page.
 *
 * Safe by construction:
 *   - Idempotent: re-running this script (e.g. accidentally included
 *     twice) is a no-op after the first run.
 *   - Never assumes dashboard.css's .sidebar/.main/.whole exist; bails
 *     out quietly if the page doesn't have them, instead of throwing.
 *   - Logout is wired independently of each page's own
 *     `document.querySelectorAll(".logout")` binding, so load order
 *     between this script and a page's inline script can never leave
 *     the tab bar's logout button unwired.
 *   - The tab bar is built fully off-DOM and attached in one append, then
 *     revealed via a CSS class — never left half-built if something
 *     throws partway through.
 */
(function () {
    "use strict";

    if (window.__mobileTabbarInitialized) return;
    window.__mobileTabbarInitialized = true;

    var NAV_ITEMS = [
        { href: "dashboard.html", icon: "▦", label: "Home" },
        { href: "quizhub.html", icon: "✎", label: "Quiz" },
        { href: "learninghub.html", icon: "🎓", label: "Learn" },
        { href: "quizfinish.html", icon: "📈", label: "Stats" },
        { href: "reports.html", icon: "🗂", label: "Reports" }
    ];

    function currentFileName() {
        try {
            var path = window.location.pathname || "";
            var last = path.substring(path.lastIndexOf("/") + 1);
            return last || "dashboard.html";
        } catch (e) {
            return "";
        }
    }

    function buildTabbar() {
        var activeFile = currentFileName();

        var nav = document.createElement("nav");
        nav.className = "mobile-tabbar";
        nav.setAttribute("aria-label", "Primary");

        NAV_ITEMS.forEach(function (item) {
            var a = document.createElement("a");
            a.className = "mobile-tabbar-item";
            a.href = item.href;
            if (item.href.toLowerCase() === activeFile.toLowerCase()) {
                a.classList.add("active");
                a.setAttribute("aria-current", "page");
            }

            var icon = document.createElement("span");
            icon.className = "mtb-icon";
            icon.textContent = item.icon;

            var label = document.createElement("span");
            label.className = "mtb-label";
            label.textContent = item.label;

            a.appendChild(icon);
            a.appendChild(label);
            nav.appendChild(a);
        });

        var logoutBtn = document.createElement("button");
        logoutBtn.type = "button";
        logoutBtn.className = "mobile-tabbar-item logout-item";

        var logoutIcon = document.createElement("span");
        logoutIcon.className = "mtb-icon";
        logoutIcon.textContent = "⎋";
        var logoutLabel = document.createElement("span");
        logoutLabel.className = "mtb-label";
        logoutLabel.textContent = "Exit";

        logoutBtn.appendChild(logoutIcon);
        logoutBtn.appendChild(logoutLabel);
        logoutBtn.addEventListener("click", handleLogout);
        nav.appendChild(logoutBtn);

        return nav;
    }

    function handleLogout() {
        var perform = (typeof window.apiFetch === "function")
            ? window.apiFetch("/logout", { method: "POST" })
            : fetch("/logout", { method: "POST", credentials: "include" });

        Promise.resolve(perform)
            .catch(function () { /* best-effort; still redirect below */ })
            .then(function () {
                window.location.href = "/login.html";
            });
    }

    function init() {
        // Only build on pages that actually use the sidebar/main layout
        // this component is meant to replace — anything else (e.g. a
        // page missing dashboard.css entirely) is left untouched.
        if (!document.querySelector(".sidebar") || !document.querySelector(".main")) {
            return;
        }

        try {
            var tabbar = buildTabbar();
            document.body.appendChild(tabbar);
            // Reveal only after a full, successful build — avoids a
            // half-constructed bar ever being visible.
            requestAnimationFrame(function () {
                tabbar.classList.add("mobile-tabbar-ready");
            });
        } catch (e) {
            // Never let a nav-bar failure break the rest of the page.
            console.warn("Mobile tab bar could not be initialized:", e);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();