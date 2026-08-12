/**
 * quiz-mobile.js
 *
 * Mobile-only interaction layer for quizpage.html. Reuses ALL existing
 * grading/state logic already defined in quizpage.html's own inline
 * <script> (submitBtn/prevBtn/tipBtn and their click handlers) — this
 * file only relocates those exact same DOM elements and adds touch
 * gestures that trigger the very same clicks a desktop user would make.
 * No answer-checking, scoring, or timer logic is duplicated here.
 *
 * Load order requirement: include this AFTER quizpage.html's own inline
 * <script> (so submitBtn.onclick etc. are already assigned) and after
 * mobile-nav.js (so the shared tab bar exists before this hides it).
 *
 * Safe by construction:
 *   - Idempotent — re-inclusion is a no-op.
 *   - Every DOM lookup is null-checked; a missing element (e.g. this
 *     script accidentally loaded on a non-quiz page) makes the relevant
 *     feature silently skip itself instead of throwing.
 *   - Never calls into quiz state directly — it only ever calls
 *     .click() on the real buttons, so every safety check those
 *     handlers already have (e.g. submitBtn's "no answer selected"
 *     shake) applies exactly the same way it would to a tap.
 *   - Swipe detection explicitly backs off near drag-and-drop controls
 *     (word chips, drop targets, sort chips) so it never fights a
 *     question type that needs its own horizontal touch gestures.
 */
(function () {
    "use strict";

    if (window.__quizMobileInitialized) return;
    window.__quizMobileInitialized = true;

    var MOBILE_QUERY = "(max-width: 768px)";
    var SWIPE_THRESHOLD_PX = 60;
    var SWIPE_MAX_VERTICAL_PX = 80;
    var SHEET_DISMISS_DRAG_PX = 60;

    function isMobileViewport() {
        try {
            return window.matchMedia(MOBILE_QUERY).matches;
        } catch (e) {
            return false;
        }
    }

    /** Moves the existing .quiz-nav (with its live button handlers) into a fixed bottom bar. */
    function moveActionBarToBottom() {
        var nav = document.querySelector(".quiz-nav");
        if (!nav || nav.classList.contains("quiz-nav-mobile")) return;
        nav.classList.add("quiz-nav-mobile");
        document.body.appendChild(nav);
        document.body.classList.add("quiz-mobile-active");
    }

    function buildBackdrop() {
        var existing = document.querySelector(".quiz-mobile-backdrop");
        if (existing) return existing;
        var backdrop = document.createElement("div");
        backdrop.className = "quiz-mobile-backdrop";
        document.body.appendChild(backdrop);
        return backdrop;
    }

    /** Turns the existing hint box into a dismissible bottom sheet without touching how it's populated. */
    function wireHintSheet() {
        var tipBtn = document.getElementById("tipBtn");
        var tipBox = document.getElementById("tipBox");
        if (!tipBtn || !tipBox) return;

        var backdrop = buildBackdrop();

        function syncBackdrop() {
            if (tipBox.classList.contains("visible")) {
                backdrop.classList.add("visible");
            } else {
                backdrop.classList.remove("visible");
            }
        }

        function closeSheet() {
            tipBox.classList.remove("visible");
            syncBackdrop();
        }

        // Registered AFTER quizpage.html's own tipBtn.onclick (already
        // assigned before this script ever runs), so by the time this
        // fires, tipBox's "visible" class already reflects the new state.
        tipBtn.addEventListener("click", syncBackdrop);
        backdrop.addEventListener("click", closeSheet);

        // Swipe-down on the open sheet to dismiss it, matching the native
        // bottom-sheet gesture users already expect on mobile.
        var startY = null;
        tipBox.addEventListener("touchstart", function (e) {
            if (e.touches.length !== 1) return;
            startY = e.touches[0].clientY;
        }, { passive: true });

        tipBox.addEventListener("touchend", function (e) {
            if (startY === null) return;
            var deltaY = e.changedTouches[0].clientY - startY;
            startY = null;
            if (deltaY > SHEET_DISMISS_DRAG_PX) closeSheet();
        }, { passive: true });
    }

    /** Swipe left/right on the question card to advance/go back — calls the real buttons, nothing more. */
    function wireSwipeNavigation() {
        var wrapper = document.getElementById("quizWrapper");
        var submitBtn = document.getElementById("submitBtn");
        var prevBtn = document.getElementById("prevBtn");
        if (!wrapper || !submitBtn || !prevBtn) return;

        var startX = null;
        var startY = null;

        wrapper.addEventListener("touchstart", function (e) {
            if (e.touches.length !== 1) return;
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;
        }, { passive: true });

        wrapper.addEventListener("touchend", function (e) {
            if (startX === null) return;
            var touch = e.changedTouches[0];
            var deltaX = touch.clientX - startX;
            var deltaY = touch.clientY - startY;
            startX = null;
            startY = null;

            if (Math.abs(deltaY) > SWIPE_MAX_VERTICAL_PX) return; // likely a scroll, not a swipe
            if (Math.abs(deltaX) < SWIPE_THRESHOLD_PX) return;

            // Don't hijack horizontal drags meant for a question's own
            // drag-and-drop controls (fill-in-the-blank word bank /
            // drop targets, sort-and-classify chips).
            var target = e.target;
            if (target && target.closest && target.closest(".word-chip, .blank-drop, .sort-chip")) {
                return;
            }

            if (deltaX < 0) {
                // Forward. submitBtn's own handler already guards the
                // "nothing selected yet" case (it shakes instead of
                // advancing), so calling it unconditionally is safe.
                submitBtn.click();
            } else {
                // Back. prevBtn's own handler already guards index 0.
                prevBtn.click();
            }
        }, { passive: true });
    }

    function init() {
        if (!isMobileViewport()) return;
        if (!document.getElementById("quizWrapper")) return; // not the quiz page

        try {
            moveActionBarToBottom();
            wireHintSheet();
            wireSwipeNavigation();
        } catch (e) {
            // A failure here should degrade to "desktop-style buttons in
            // their original spot" (still fully usable), never break the quiz.
            console.warn("Mobile quiz enhancements could not be initialized:", e);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
