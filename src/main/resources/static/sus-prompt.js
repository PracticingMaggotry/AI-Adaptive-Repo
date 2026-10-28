/**
 * sus-prompt.js
 *
 * Shows a small "Give feedback" popup 10 seconds after a study participant
 * finishes a quiz (the results screen in quizpage.html), on whatever student
 * page they happen to be on by then, so they don't have to find Feedback in
 * the profile page.
 *
 * How it works
 *  - quizpage.html calls window.SusPrompt.arm(email) when the results render.
 *    That stores { due, email } in localStorage, so the 10-second timer
 *    survives page changes (it is a timestamp, not a setTimeout).
 *  - Every page that loads this script checks the key once per second. When
 *    the time is up it asks /api/sus/status; the popup is only shown to an
 *    enrolled participant who has NOT already submitted the feedback form.
 *  - "Maybe later" or opening the form marks the prompt dismissed. Finishing
 *    another quiz arms it again, until the form has been submitted.
 *  - It never appears over a quiz that is still in progress.
 *
 * Not loaded on sus.html itself, login/register, or admin pages.
 */
(function () {
    "use strict";
    if (window.SusPrompt) return;

    var KEY = "susPromptState";
    var DELAY_MS = 10000;
    var FORM_URL = "sus.html";

    function read() {
        try { return JSON.parse(localStorage.getItem(KEY) || "null"); } catch (e) { return null; }
    }
    function write(state) {
        try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) {}
    }
    function clear() {
        try { localStorage.removeItem(KEY); } catch (e) {}
    }

    var shown = false;
    var checking = false;
    var timer = null;

    function arm(email) {
        write({ due: Date.now() + DELAY_MS, email: email ? String(email).toLowerCase() : null, dismissed: false });
        start();   // same-page case: the quiz results screen itself
    }

    function quizInProgress() {
        // quizpage.html: the quiz wrapper exists until the results screen sets body.quiz-complete.
        return !!document.getElementById("quizWrapper") && !document.body.classList.contains("quiz-complete");
    }

    function injectStyles() {
        if (document.getElementById("susPromptStyles")) return;
        var st = document.createElement("style");
        st.id = "susPromptStyles";
        st.textContent =
            ".sus-prompt{position:fixed;right:18px;bottom:18px;z-index:9000;width:min(340px,calc(100vw - 24px));" +
            "background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:16px 16px 14px;" +
            "box-shadow:0 12px 36px rgba(15,23,42,.22);font-family:inherit;color:#374151;" +
            "animation:susPromptIn .25s ease-out}" +
            "@keyframes susPromptIn{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}" +
            "@media(prefers-reduced-motion:reduce){.sus-prompt{animation:none}}" +
            ".sus-prompt-title{font-size:.95rem;font-weight:800;color:#111827;margin:0 24px 4px 0}" +
            ".sus-prompt-text{font-size:.82rem;line-height:1.5;margin:0 0 12px}" +
            ".sus-prompt-actions{display:flex;gap:8px}" +
            ".sus-prompt-go{flex:1;padding:11px 12px;border-radius:10px;border:0;background:#0093ad;color:#fff;" +
            "font-weight:700;font-size:.88rem;cursor:pointer}" +
            ".sus-prompt-later{padding:11px 12px;border-radius:10px;border:1px solid #e2e8f0;background:#f8fafc;" +
            "color:#475569;font-weight:600;font-size:.85rem;cursor:pointer}" +
            ".sus-prompt-x{position:absolute;top:8px;right:8px;width:30px;height:30px;border:0;background:transparent;" +
            "color:#94a3b8;font-size:1.2rem;line-height:1;cursor:pointer;border-radius:8px}" +
            "@media(max-width:768px){.sus-prompt{left:12px;right:12px;width:auto;" +
            "bottom:calc(12px + env(safe-area-inset-bottom,0px))}" +
            ".sus-prompt.above-tabbar{bottom:calc(76px + env(safe-area-inset-bottom,0px))}}";
        document.head.appendChild(st);
    }

    function dismiss(box) {
        var s = read();
        if (s) { s.dismissed = true; write(s); }
        if (box && box.parentNode) box.parentNode.removeChild(box);
    }

    function show() {
        if (shown) return;
        shown = true;
        stop();
        injectStyles();

        var box = document.createElement("div");
        box.className = "sus-prompt";
        box.setAttribute("role", "complementary");
        box.setAttribute("aria-label", "Feedback request");
        box.innerHTML =
            '<button type="button" class="sus-prompt-x" aria-label="Close">×</button>' +
            '<p class="sus-prompt-title">Share your feedback</p>' +
            '<p class="sus-prompt-text">Thanks for finishing the quiz. Please tell us what you think of the ' +
            'learning system. It takes about 3 minutes.</p>' +
            '<div class="sus-prompt-actions">' +
            '<button type="button" class="sus-prompt-go">Give feedback</button>' +
            '<button type="button" class="sus-prompt-later">Maybe later</button></div>';
        document.body.appendChild(box);

        // On phones, sit above the bottom tab bar when it is visible.
        var tab = document.querySelector(".mobile-tabbar");
        if (tab && getComputedStyle(tab).display !== "none") box.classList.add("above-tabbar");

        box.querySelector(".sus-prompt-x").onclick = function () { dismiss(box); };
        box.querySelector(".sus-prompt-later").onclick = function () { dismiss(box); };
        box.querySelector(".sus-prompt-go").onclick = function () {
            var s = read();
            if (s) { s.dismissed = true; write(s); }
            window.location.href = FORM_URL;
        };
    }

    async function check() {
        if (shown || checking) return;
        var s = read();
        if (!s || s.dismissed) return;
        if (Date.now() < s.due) return;
        if (quizInProgress()) return;
        if (typeof apiFetch !== "function") return;

        checking = true;
        try {
            // Only enrolled participants who have not yet submitted the form.
            var st = await apiFetch("/api/sus/status");
            if (!st || !st.participant || st.submitted) { clear(); stop(); return; }

            // The key lives in browser storage shared by every account on this device.
            if (s.email) {
                var me = await apiFetch("/api/me");
                var current = me && me.email ? String(me.email).toLowerCase() : null;
                if (current && current !== s.email) { clear(); stop(); return; }
            }
            // Re-check: state may have changed while the requests were running.
            var again = read();
            if (!again || again.dismissed || quizInProgress()) return;
            show();
        } catch (e) {
            // Network/auth hiccup: stay quiet and try again on the next tick.
        } finally {
            checking = false;
        }
    }

    function start() {
        if (timer || shown) return;
        timer = setInterval(check, 1000);
    }
    function stop() {
        if (timer) { clearInterval(timer); timer = null; }
    }

    window.SusPrompt = { arm: arm };

    function init() {
        var s = read();
        if (s && !s.dismissed) start();
    }
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
})();
