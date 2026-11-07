/**
 * onboarding.js
 *
 * First-time welcome guide for regular (non-admin) users: a short, skippable
 * 4-step popup that explains the menu, how quizzes adapt, and a few "good to
 * know" safety points. Shown once per account, and replayable at any time.
 *
 * Why a popup and not a spotlight tour
 *  - It does not point at page elements, so it keeps working while the pages
 *    behind it are redesigned, and it behaves the same on desktop and phones.
 *  - It never covers or depends on the quiz page, where focus matters most.
 *
 * When it shows by itself (all must be true)
 *  - /api/me says the person is logged in and is NOT an admin.
 *  - Study consent is not pending (consent.js owns the screen until accepted;
 *    it reloads the page afterwards and the guide is evaluated again).
 *  - This account has not seen the guide, and has no quiz history yet (so
 *    existing users and people on a new device are not treated as new).
 *  - The page's own loading screen is gone.
 *
 * Where it is stored
 *  - localStorage, one key per account email, so accounts that share a device
 *    do not hide it for each other. If storage is blocked, the guide never
 *    opens by itself (no nagging on every page) but can still be replayed.
 *  - Bump VERSION to show a revised guide to everyone again.
 *
 * How to replay
 *  - The "?" button (bottom of the left menu on desktop, top bar on phones),
 *    any element with data-onboarding-open, or Onboarding.open().
 *  - Profile page: a "Replay the welcome guide" button is added next to
 *    "Learn how AI is used".
 *
 * Not loaded on quizpage.html, sus.html, login/register, or any admin page.
 * Exposes: window.Onboarding = { open, reset }
 */
(function () {
    "use strict";
    if (window.Onboarding) return;
    if (/admin/i.test(window.location.pathname || "")) return;

    var VERSION = "v1";
    var KEY_PREFIX = "onboarding." + VERSION + ".";
    var MOBILE_QUERY = "(max-width: 768px)";
    var QUIZ_HUB = "quizhub.html";

    var ctx = null;        // { email, name, isParticipant }
    var ui = null;         // live dialog state while open

    /* ───────────────────────── storage ───────────────────────── */

    function keyFor(email) { return KEY_PREFIX + String(email || "").toLowerCase(); }

    // true = already seen, false = not seen, null = storage unavailable
    function readSeen(email) {
        try { return localStorage.getItem(keyFor(email)) ? true : false; } catch (e) { return null; }
    }
    function markSeen(email, how) {
        try { localStorage.setItem(keyFor(email), JSON.stringify({ at: Date.now(), how: how })); } catch (e) {}
    }
    function clearSeen(email) {
        try { localStorage.removeItem(keyFor(email)); } catch (e) {}
    }

    /* ───────────────────────── helpers ───────────────────────── */

    function isMobile() {
        try {
            return window.matchMedia(MOBILE_QUERY).matches ||
                document.documentElement.classList.contains("force-mobile-nav");
        } catch (e) { return false; }
    }

    function currentFile() {
        var p = window.location.pathname || "";
        return (p.substring(p.lastIndexOf("/") + 1) || "dashboard.html").toLowerCase();
    }

    function firstName(full) {
        var n = String(full || "").trim().split(/\s+/)[0] || "";
        return n.length > 30 ? n.slice(0, 30) : n;
    }

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    function iconSpan(glyph) {
        var s = el("span", "ob-glyph", glyph);
        s.setAttribute("aria-hidden", "true");
        return s;
    }

    /* ───────────────────────── content ───────────────────────── */

    function buildSteps() {
        var mobile = isMobile();
        var name = firstName(ctx && ctx.name);
        var participant = !!(ctx && ctx.isParticipant);

        var nav = mobile
            ? [["▦", "Home", "Your progress at a glance"],
               ["✎", "Quiz", "Take quizzes"],
               ["🎓", "Learn", "Read a lesson for each topic"],
               ["📈", "Stats", "See how your last quiz went"],
               ["🗂", "Reports", "Your scores over time"]]
            : [["▦", "Dashboard", "Your progress at a glance"],
               ["✎", "Quiz Hub", "Take quizzes"],
               ["🎓", "Learning Hub", "Read a lesson for each topic"],
               ["📈", "Performance", "See how your last quiz went"],
               ["🗂", "Reports", "Your scores over time"]];

        var start = participant
            ? "Your researcher sets up your first quiz. Open the Quiz Hub to start it. " +
              "If a handout is waiting there instead, open it and press send."
            : "Add a handout: tap Upload Handout in the Quiz Hub (PDF, DOCX or TXT, up to 10 MB).";

        var aiHint = mobile
            ? "To see where AI is used, open your Profile (tap your name at the top)."
            : "To see where AI is used, tap the AI button in the menu or open your Profile.";
        var helpHint = mobile
            ? "Need this tour again? Tap the ? button at the top of the screen."
            : "Need this tour again? Tap the ? button at the bottom of the menu.";

        return [
            {
                icon: "👋",
                title: name ? "Welcome, " + name + "!" : "Welcome!",
                render: function (box) {
                    box.appendChild(el("p", "ob-text",
                        "This system turns your handouts into quizzes, then adjusts how hard they are to match how you are doing."));
                    box.appendChild(el("p", "ob-text ob-muted", "A quick 4-step tour. It takes about 30 seconds."));
                }
            },
            {
                icon: "🧭",
                title: "Find your way around",
                render: function (box) {
                    box.appendChild(el("p", "ob-text",
                        mobile ? "Use the bar at the bottom of the screen." : "Use the menu on the left."));
                    var ul = el("ul", "ob-list");
                    nav.forEach(function (row) {
                        var li = el("li", "ob-row");
                        li.appendChild(iconSpan(row[0]));
                        var t = el("span", "ob-row-text");
                        t.appendChild(el("strong", null, row[1]));
                        t.appendChild(document.createTextNode(" – " + row[2]));
                        li.appendChild(t);
                        ul.appendChild(li);
                    });
                    box.appendChild(ul);
                }
            },
            {
                icon: "✎",
                title: "Quizzes that fit you",
                render: function (box) {
                    var ol = el("ol", "ob-steps");
                    [start,
                     "Take the quiz. Your results show what you know and what needs work.",
                     "Do well and the next quiz gets harder. Struggle and it gets easier."
                    ].forEach(function (line) { ol.appendChild(el("li", null, line)); });
                    box.appendChild(ol);
                }
            },
            {
                icon: "💡",
                title: "Good to know",
                render: function (box) {
                    var ul = el("ul", "ob-list");
                    [["🚩", "AI can make mistakes. If a question looks wrong, tap Report Issue after you answer."],
                     ["🤖", aiHint],
                     ["🔒", "Keep your name and other personal details out of handouts and answers."],
                     ["❓", helpHint]
                    ].forEach(function (row) {
                        var li = el("li", "ob-row");
                        li.appendChild(iconSpan(row[0]));
                        li.appendChild(el("span", "ob-row-text", row[1]));
                        ul.appendChild(li);
                    });
                    box.appendChild(ul);
                }
            }
        ];
    }

    /* ───────────────────────── styles ───────────────────────── */

    var CSS = [
        ".ob-overlay{position:fixed;inset:0;z-index:10050;display:flex;align-items:center;justify-content:center;" +
        "padding:16px;background:rgba(15,23,42,.6);animation:obFade .18s ease-out}",
        "@keyframes obFade{from{opacity:0}to{opacity:1}}",
        "@keyframes obRise{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:none}}",
        "@keyframes obSwap{from{opacity:0;transform:translateX(8px)}to{opacity:1;transform:none}}",

        ".ob-dialog{position:relative;box-sizing:border-box;width:100%;max-width:460px;" +
        "max-height:calc(100vh - 32px);max-height:calc(100dvh - 32px);overflow-y:auto;background:#fff;color:#1f2937;" +
        "border-radius:18px;padding:30px 24px 20px;box-shadow:0 24px 60px rgba(0,0,0,.3);font-family:inherit;" +
        "font-size:16px;line-height:1.5;animation:obRise .22s ease-out}",
        ".ob-dialog *{box-sizing:border-box}",
        ".ob-dialog [hidden]{display:none!important}",

        ".ob-skip{position:absolute;top:8px;right:8px;min-width:44px;min-height:44px;padding:0 12px;border:0;" +
        "border-radius:10px;background:transparent;color:#4b5563;font:inherit;font-size:.85rem;font-weight:600;" +
        "text-decoration:underline;cursor:pointer}",
        ".ob-skip:hover{background:#f1f5f9;color:#111827}",

        ".ob-content{animation:obSwap .18s ease-out}",
        ".ob-icon{width:56px;height:56px;border-radius:50%;background:#e6f4f7;display:flex;align-items:center;" +
        "justify-content:center;font-size:1.7rem;margin-bottom:14px}",
        ".ob-title{margin:0 0 10px;font-size:1.3rem;font-weight:800;line-height:1.25;color:#111827}",
        ".ob-title:focus{outline:none}",
        ".ob-body{min-height:196px}",
        ".ob-text{margin:0 0 10px;font-size:.96rem;color:#1f2937}",
        ".ob-muted{color:#4b5563;font-size:.88rem}",

        ".ob-list{list-style:none;margin:6px 0 0;padding:0;display:flex;flex-direction:column;gap:10px}",
        ".ob-row{display:flex;align-items:flex-start;gap:12px;font-size:.92rem;color:#1f2937}",
        ".ob-glyph{flex:0 0 28px;width:28px;text-align:center;font-size:1.15rem;line-height:1.4}",
        ".ob-row-text{flex:1;min-width:0}",
        ".ob-row-text strong{color:#111827}",
        ".ob-steps{margin:2px 0 0;padding-left:22px;display:flex;flex-direction:column;gap:12px;" +
        "font-size:.94rem;color:#1f2937}",
        ".ob-steps li::marker{font-weight:800;color:#00758a}",

        ".ob-footer{display:flex;flex-direction:column;gap:14px;margin-top:18px}",
        ".ob-progress{display:flex;align-items:center;justify-content:center;gap:10px}",
        ".ob-count{font-size:.8rem;font-weight:700;color:#4b5563}",
        ".ob-dots{display:flex;gap:6px;align-items:center}",
        ".ob-dot{width:8px;height:8px;border-radius:99px;background:#cbd5e1;transition:width .2s,background .2s}",
        ".ob-dot.on{width:22px;background:#00758a}",
        ".ob-nav{display:flex;justify-content:space-between;gap:10px}",
        ".ob-btn{min-height:44px;padding:0 20px;border-radius:10px;font:inherit;font-size:.92rem;font-weight:700;" +
        "cursor:pointer;border:1px solid transparent}",
        ".ob-back{background:#fff;border-color:#cbd5e1;color:#334155}",
        ".ob-back:hover{background:#f1f5f9}",
        ".ob-next{margin-left:auto;background:#00758a;color:#fff}",
        ".ob-next:hover{background:#005f70}",

        ".ob-dialog button:focus-visible,.ob-help-top:focus-visible," +
        ".ob-replay:focus-visible{outline:3px solid #1d4ed8;outline-offset:2px}",
        /* the sidebar is dark, so its focus ring needs a light color to stay visible (10:1 vs 2:1) */
        ".ob-help-btn:focus-visible{outline:3px solid #FFD400;outline-offset:2px}",

        /* "?" help buttons: left menu on desktop, top bar on phones */
        ".sidebar-footer.ob-row-footer{display:flex;gap:8px;align-items:stretch}",
        ".sidebar-footer.ob-row-footer .logout{flex:1;min-width:0}",
        ".ob-help-btn{width:44px;flex-shrink:0;display:flex;align-items:center;justify-content:center;" +
        "border-radius:10px;border:1px solid rgba(255,255,255,.12);background:rgba(255,255,255,.05);" +
        "color:#FFD400;font:inherit;font-weight:800;font-size:.95rem;cursor:pointer;transition:.2s}",
        ".ob-help-btn:hover{background:#00758a;color:#fff;border-color:#00758a}",
        ".ob-help-top{display:none;width:44px;height:44px;flex-shrink:0;align-items:center;justify-content:center;" +
        "border-radius:12px;border:1px solid #e2e8f0;background:#fff;color:#00758a;font:inherit;font-weight:800;" +
        "font-size:1.05rem;cursor:pointer}",
        ".ob-replay{margin-left:8px}",
        "@media (max-width:768px){.ob-help-btn{display:none!important}" +
        ".ob-help-top{display:inline-flex}}",
        "html.force-mobile-nav .ob-help-btn{display:none!important}",
        "html.force-mobile-nav .ob-help-top{display:inline-flex}",

        /* phones: bottom sheet within thumb reach */
        "@media (max-width:600px){.ob-overlay{align-items:flex-end;padding:0}" +
        ".ob-dialog{max-width:none;max-height:92vh;max-height:92dvh;border-radius:20px 20px 0 0;" +
        "padding:28px 18px calc(16px + env(safe-area-inset-bottom,0px))}" +
        ".ob-body{min-height:0}" +
        ".ob-next{flex:1}}",

        "@media (prefers-reduced-motion:reduce){.ob-overlay,.ob-dialog,.ob-content{animation:none}" +
        ".ob-dot{transition:none}}"
    ].join("\n");

    function injectStyles() {
        if (document.getElementById("obStyles")) return;
        var st = document.createElement("style");
        st.id = "obStyles";
        st.textContent = CSS;
        document.head.appendChild(st);
    }

    /* ───────────────────────── dialog ───────────────────────── */

    function focusables() {
        if (!ui) return [];
        return Array.prototype.filter.call(
            ui.dialog.querySelectorAll("button"),
            function (b) { return !b.hidden && !b.disabled && b.getClientRects().length > 0; }
        );
    }

    function render() {
        var steps = ui.steps;
        var i = ui.index;
        var step = steps[i];
        var last = i === steps.length - 1;

        ui.icon.textContent = step.icon;
        ui.title.textContent = step.title;
        ui.body.textContent = "";
        step.render(ui.body);

        // restart the small entrance animation on each step
        ui.content.style.animation = "none";
        void ui.content.offsetWidth;
        ui.content.style.animation = "";

        ui.count.textContent = "Step " + (i + 1) + " of " + steps.length;
        Array.prototype.forEach.call(ui.dots.children, function (d, n) {
            d.classList.toggle("on", n === i);
        });

        ui.back.hidden = i === 0;
        ui.skip.textContent = last ? "Close" : "Skip tour";

        if (last) {
            ui.next.textContent = currentFile() === QUIZ_HUB ? "Got it" : "Open Quiz Hub";
        } else {
            ui.next.textContent = "Next";
        }
    }

    function go(delta) {
        var n = ui.index + delta;
        if (n < 0 || n >= ui.steps.length) return;
        ui.index = n;
        render();
    }

    function close(how, thenNavigate) {
        if (!ui) return;
        var state = ui;
        ui = null;
        document.removeEventListener("keydown", state.onKey, true);
        window.removeEventListener("resize", state.onResize);
        if (state.overlay.parentNode) state.overlay.parentNode.removeChild(state.overlay);
        document.body.style.overflow = state.prevOverflow;

        if (ctx) markSeen(ctx.email, how);

        if (thenNavigate) {
            window.location.href = thenNavigate;
            return;
        }
        var back = state.returnFocus;
        if (back && document.contains(back) && typeof back.focus === "function") {
            try { back.focus(); } catch (e) {}
        }
    }

    function open() {
        if (ui || !ctx) return;
        injectStyles();

        var overlay = el("div", "ob-overlay");
        var dialog = el("div", "ob-dialog");
        dialog.setAttribute("role", "dialog");
        dialog.setAttribute("aria-modal", "true");
        dialog.setAttribute("aria-labelledby", "obTitle");

        var skip = el("button", "ob-skip", "Skip tour");
        skip.type = "button";

        var content = el("div", "ob-content");
        // Focus stays on the Next button as steps change, so a polite live
        // region tells screen-reader users what the new step says.
        content.setAttribute("aria-live", "polite");
        content.setAttribute("aria-atomic", "true");
        var icon = el("div", "ob-icon");
        icon.setAttribute("aria-hidden", "true");
        var title = el("h2", "ob-title");
        title.id = "obTitle";
        var body = el("div", "ob-body");
        content.appendChild(icon);
        content.appendChild(title);
        content.appendChild(body);

        var footer = el("div", "ob-footer");
        var progress = el("div", "ob-progress");
        var count = el("span", "ob-count");
        var dots = el("span", "ob-dots");
        dots.setAttribute("aria-hidden", "true");
        var steps = buildSteps();
        steps.forEach(function () { dots.appendChild(el("span", "ob-dot")); });
        progress.appendChild(count);
        progress.appendChild(dots);

        var nav = el("div", "ob-nav");
        var back = el("button", "ob-btn ob-back", "Back");
        back.type = "button";
        var next = el("button", "ob-btn ob-next", "Next");
        next.type = "button";
        nav.appendChild(back);
        nav.appendChild(next);
        footer.appendChild(progress);
        footer.appendChild(nav);

        dialog.appendChild(skip);
        dialog.appendChild(content);
        dialog.appendChild(footer);
        overlay.appendChild(dialog);

        ui = {
            overlay: overlay, dialog: dialog, content: content, icon: icon, title: title, body: body,
            count: count, dots: dots, back: back, next: next, skip: skip,
            steps: steps, index: 0,
            returnFocus: document.activeElement,
            prevOverflow: document.body.style.overflow
        };

        back.onclick = function () { go(-1); };
        next.onclick = function () {
            if (ui.index < ui.steps.length - 1) { go(1); return; }
            close("done", currentFile() === QUIZ_HUB ? null : QUIZ_HUB);
        };
        skip.onclick = function () { close(ui.index === ui.steps.length - 1 ? "done" : "skipped"); };

        ui.onKey = function (e) {
            if (!ui) return;
            if (e.key === "Escape") {
                e.preventDefault();
                e.stopPropagation();
                close(ui.index === ui.steps.length - 1 ? "done" : "skipped");
                return;
            }
            if (e.key === "ArrowRight") { e.preventDefault(); go(1); return; }
            if (e.key === "ArrowLeft") { e.preventDefault(); go(-1); return; }
            if (e.key === "Tab") {
                var f = focusables();
                if (!f.length) return;
                var first = f[0], lastEl = f[f.length - 1];
                if (!ui.dialog.contains(document.activeElement)) {
                    e.preventDefault();
                    first.focus();
                } else if (e.shiftKey && document.activeElement === first) {
                    e.preventDefault();
                    lastEl.focus();
                } else if (!e.shiftKey && document.activeElement === lastEl) {
                    e.preventDefault();
                    first.focus();
                }
            }
        };
        ui.onResize = function () { if (ui) render(); };
        document.addEventListener("keydown", ui.onKey, true);
        window.addEventListener("resize", ui.onResize);

        document.body.appendChild(overlay);
        document.body.style.overflow = "hidden";
        render();
        next.focus();
    }

    /* ───────────────────────── entry points ───────────────────────── */

    function installHelpers() {
        injectStyles();

        // Desktop: "?" next to the AI button at the bottom of the left menu.
        var footer = document.querySelector(".sidebar-footer");
        if (footer && !footer.querySelector(".ob-help-btn")) {
            footer.classList.add("ob-row-footer");
            var b = el("button", "ob-help-btn", "?");
            b.type = "button";
            b.title = "Show the welcome guide";
            b.setAttribute("aria-label", "Show the welcome guide");
            b.addEventListener("click", open);
            footer.appendChild(b);
        }

        // Phones: the left menu is hidden, so put "?" in the top bar instead.
        var actions = document.querySelector(".topbar-actions");
        if (actions && !actions.querySelector(".ob-help-top")) {
            var t = el("button", "ob-help-top", "?");
            t.type = "button";
            t.title = "Show the welcome guide";
            t.setAttribute("aria-label", "Show the welcome guide");
            t.addEventListener("click", open);
            actions.insertBefore(t, actions.firstChild);
        }

        // Profile page: a clearly labelled replay button beside "Learn how AI is used".
        var aiBtn = document.querySelector("#aiInfoCard [data-ai-info-open]");
        if (aiBtn && !document.querySelector(".ob-replay")) {
            var r = el("button", "btn ghost ob-replay", "Replay the welcome guide");
            r.type = "button";
            r.addEventListener("click", open);
            aiBtn.parentNode.insertBefore(r, aiBtn.nextSibling);
        }

        // Anything else can opt in: <button data-onboarding-open>
        document.querySelectorAll("[data-onboarding-open]").forEach(function (node) {
            node.addEventListener("click", open);
        });
    }

    async function consentPending() {
        try {
            var st = await apiFetch("/api/consent/status");
            return !!(st && st.required && !st.accepted);
        } catch (e) {
            return true; // cannot tell: stay out of the way
        }
    }

    // true = has quiz history, false = brand new, null = could not tell
    async function hasPriorActivity() {
        try {
            var d = await apiFetch("/api/dashboard");
            if (!d) return null;
            var taken = d.student && Number(d.student.quizzesTaken);
            if (isFinite(taken)) return taken > 0;
            if (Array.isArray(d.quizHistory)) return d.quizHistory.length > 0;
            return null;
        } catch (e) {
            return null;
        }
    }

    // Wait for the page's own loading screen (dashboard has #pageLoader) to go away.
    function pageReady() {
        return new Promise(function (resolve) {
            var loader = document.getElementById("pageLoader");
            function gone() {
                return !loader || loader.classList.contains("hidden") ||
                    getComputedStyle(loader).display === "none";
            }
            function settle() { setTimeout(resolve, 500); }
            if (gone()) { settle(); return; }
            var finished = false, timer = null, mo = null;
            function finish() {
                if (finished) return;
                finished = true;
                if (mo) mo.disconnect();
                clearTimeout(timer);
                settle();
            }
            mo = new MutationObserver(function () { if (gone()) finish(); });
            mo.observe(loader, { attributes: true, attributeFilter: ["class", "style"] });
            timer = setTimeout(finish, 8000);
        });
    }

    async function autoStart() {
        if (readSeen(ctx.email) !== false) return;       // seen, or storage blocked
        if (await consentPending()) return;              // consent.js owns the screen
        var prior = await hasPriorActivity();
        if (prior === null) return;                      // try again on the next page
        if (prior) { markSeen(ctx.email, "existing-user"); return; }
        await pageReady();
        if (ui || document.getElementById("quizWrapper")) return;
        if (readSeen(ctx.email) !== false) return;       // another tab may have shown it
        open();
    }

    async function init() {
        if (typeof apiFetch !== "function") return;
        var me;
        try { me = await apiFetch("/api/me"); } catch (e) { return; }
        if (!me || !me.email || me.isAdmin) return;      // regular users only
        ctx = { email: String(me.email), name: me.name, isParticipant: !!me.isParticipant };
        installHelpers();
        autoStart();
    }

    window.Onboarding = {
        open: open,
        // For QA and the study team: make the guide appear as "first time" again for this account.
        reset: function () { if (ctx) clearSeen(ctx.email); }
    };

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
})();
