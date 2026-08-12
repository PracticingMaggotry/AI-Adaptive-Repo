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

    /**
     * Forces mobile layout via inline styles + a body class, independent
     * of whether the CSS media query itself evaluated correctly. Some
     * real-world Android WebViews (notably budget ColorOS/Realme UI
     * builds) have been observed reporting window.innerWidth larger than
     * the actual device width on first paint, or silently expanding the
     * layout viewport when any child overflows — which makes a pure CSS
     * `@media (max-width: 768px)` unreliable even though DevTools
     * emulation (which honors the viewport meta tag exactly) shows it
     * working. This is a JS-side backstop, not a replacement for the CSS.
     */
    function isNarrowViewport() {
        try {
            var w = Math.min(
                window.innerWidth || Infinity,
                document.documentElement.clientWidth || Infinity,
                window.screen && window.screen.width ? window.screen.width : Infinity
            );
            return w <= 768;
        } catch (e) {
            return isMobileViewport();
        }
    }

    function syncMobileClass() {
        var narrow = isNarrowViewport() || isMobileViewport();
        document.documentElement.classList.toggle("force-mobile-nav", narrow);
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
            syncMobileClass();
            window.addEventListener("resize", syncMobileClass, { passive: true });
            window.addEventListener("orientationchange", syncMobileClass, { passive: true });
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