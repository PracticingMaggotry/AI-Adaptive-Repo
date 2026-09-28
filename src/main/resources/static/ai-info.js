(function () {
    "use strict";
    if (window.__aiInfoInit) return;
    window.__aiInfoInit = true;

    var CSS = `
.aiinfo-btn{width:44px;flex-shrink:0;display:flex;align-items:center;justify-content:center;border-radius:10px;border:1px solid rgba(255,255,255,.12);background:rgba(255,255,255,.05);color:#FFD400;font-weight:800;font-size:.8rem;cursor:pointer;transition:.2s}
.aiinfo-btn:hover{background:#0093ad;color:#fff;border-color:#0093ad}
.sidebar-footer.aiinfo-row{display:flex;gap:8px;align-items:stretch}
.sidebar-footer.aiinfo-row .logout{flex:1;min-width:0}
.mobile-tabbar-item.aiinfo-tab{color:#FFD400}
.aiinfo-overlay{position:fixed;inset:0;background:rgba(15,23,42,.55);z-index:10001;display:none;align-items:center;justify-content:center;padding:16px}
.aiinfo-overlay.open{display:flex}
.aiinfo-modal{background:#fff;border-radius:18px;max-width:760px;width:100%;max-height:90vh;display:flex;flex-direction:column;box-shadow:0 24px 60px rgba(0,0,0,.25);overflow:hidden}
.aiinfo-head{display:flex;justify-content:space-between;align-items:center;padding:16px 22px;border-bottom:1px solid #e5e7eb}
.aiinfo-head h2{font-size:1.05rem;color:#111827;margin:0}
.aiinfo-x{width:32px;height:32px;border-radius:8px;border:1px solid #e5e7eb;background:#f8fafc;cursor:pointer}
.aiinfo-x:hover{background:#fee2e2;color:#dc2626}
.aiinfo-body{padding:18px 22px 22px;overflow-y:auto;font-size:.86rem;color:#374151;line-height:1.6}
.aiinfo-body h3{font-size:.92rem;color:#111827;margin:18px 0 6px}
.aiinfo-body h3:first-child{margin-top:0}
.aiinfo-body ul{margin:4px 0 0;padding-left:18px}
.aiinfo-body li{margin-bottom:4px}
.aiinfo-tag{display:inline-block;font-size:.66rem;font-weight:800;padding:2px 8px;border-radius:99px;margin-left:6px;vertical-align:middle}
.aiinfo-ai{background:#ede9fe;color:#5b21b6}
.aiinfo-code{background:#d1fae5;color:#065f46}
.aiinfo-note{background:#fffbeb;border:1px solid #fcd34d;border-radius:10px;padding:10px 12px;margin-top:14px;color:#92400e}
`;

    var AI = '<span class="aiinfo-tag aiinfo-ai">Claude</span>';
    var CODE = '<span class="aiinfo-tag aiinfo-code">No AI</span>';

    var HTML = `
<h3>The short version</h3>
<p>This app uses Claude, an AI model made by Anthropic, for content jobs: reading your handouts and writing quiz questions, lessons and feedback. Plain code handles the math: your scores, difficulty level, mastery and streaks. Anthropic's models are <b>Claude Sonnet</b> (harder reasoning) and <b>Claude Haiku</b> (lighter tasks).</p>

<h3>When you upload a handout</h3>
<ul>
<li><b>Knowledge extraction</b> ${AI}: Haiku reads your document and pulls out a summary, key concepts, learning objectives and important sentences. These are reused later so the whole file isn't re-read every time.</li>
<li><b>Topic summary</b> ${AI}: the 1-2 sentence blurb on your topic card.</li>
<li><b>Categorization</b> ${AI}: Haiku picks a subject category (e.g. Mathematics, Computer Science) and a short sub-topic label. Your topic name is <i>not</i> used as a source of facts.</li>
<li><b>Quiz generation</b> ${AI}: Sonnet writes 15-30 questions from your handout: multiple choice, true/false, matching, fill-in-the-blank, sorting, "four ideas, one name", short answer/essay and practical problems. Each comes with a hint, an explanation and an answer key.</li>
<li><b>Practical-question double-check</b> ${AI}: for calculation/code questions, Claude solves each one again <i>without seeing the answer key</i>. If the answers disagree, that question is thrown out.</li>
<li><b>Text extraction, file storage and duplicate detection</b> ${CODE}: done by the server. If someone uploaded identical content before, the earlier AI summary and category are reused, but your questions are always generated fresh for you.</li>
</ul>

<h3>While you take a quiz</h3>
<ul>
<li><b>Checking answers</b> ${CODE}: multiple choice, true/false, matching, fill-in-the-blank, sorting, concept ID and practical answers are graded by code against the stored key. Matching, fill-in-the-blank and sorting give partial credit per correct part.</li>
<li><b>Essay / short answer grading</b> ${AI}: Sonnet grades against the rubric and gives a 0-100 score, written feedback, and which rubric points you covered or missed. Your answer is treated as content to grade, never as instructions.</li>
<li><b>Hints and explanations</b> ${AI}: written by Claude when the question was generated, then shown by the app.</li>
</ul>

<h3>After you submit: scoring and the 5 skill categories</h3>
<ul>
<li><b>Overall score</b> ${CODE}: correct answers plus partial credit plus essay scores, as a percentage.</li>
<li><b>Sorting each question into 5 categories</b> ${AI}: Haiku labels every question as <b>Terminology</b>, <b>Computation</b>, <b>Application</b>, <b>Analysis</b> or <b>Process Steps</b>, based on what kind of thinking it needs.</li>
<li><b>Category percentages</b> (radar chart, "Where You Need Work", Skill Overview) ${CODE}: calculated from those labels and your results. Claude picks the label, and the math is plain arithmetic.</li>
<li><b>Next difficulty</b> ${CODE}: 80%+ is Hard, 50-79% is Medium, below 50% is Easy.</li>
</ul>

<h3>Adapted Quiz and Target Problems</h3>
<ul>
<li><b>Adapted Quiz</b> ${AI}: Sonnet writes a new question set based on your best score tier and the mistakes from your recent attempts.</li>
<li><b>Target Problems</b> ${AI}: Sonnet builds new questions aimed at the specific wrong or partial answers you gave, testing the same weak point from a different angle.</li>
<li>The tier used for these, and the daily limits, are set by code.</li>
</ul>

<h3>Learning Hub lessons</h3>
<ul>
<li><b>Lesson content</b> ${AI}: Haiku writes an intro, key concepts, study tips and a 4-step study plan calibrated to your tier, based only on your handout. It's saved so it isn't regenerated every time you open it.</li>
</ul>

<h3>Things that look like AI but aren't</h3>
<ul>
<li>The "AI Tutor Insight" after a quiz, "What Should I Do Today?" on the Dashboard, and "What the AI Says" in Reports ${CODE}: these are fixed rules based on your score and streak, not live Claude output.</li>
<li>Dashboard stats, SPI, mastery, streaks, charts, and the Notepad and highlights ${CODE}.</li>
</ul>

<h3>What gets sent to Anthropic</h3>
<ul>
<li>The text of your uploaded handouts (or a distilled version), your essay answers when you submit a quiz, and your recent wrong answers when you request Adapted or Target quizzes.</li>
<li>Your email and password are never sent to Anthropic.</li>
</ul>

<div class="aiinfo-note">⚠️ AI can make mistakes. If a question looks wrong or confusing, use <b>🚩 Report Issue</b> after answering, and an admin will review it. Essay scores are AI judgments, not a teacher's grade.</div>
`;

    function buildModal() {
        var ov = document.createElement("div");
        ov.className = "aiinfo-overlay";
        ov.innerHTML =
            '<div class="aiinfo-modal" role="dialog" aria-modal="true" aria-label="How AI is used">' +
            '<div class="aiinfo-head"><h2>🤖 How AI (Anthropic\'s Claude) is used in this app</h2>' +
            '<button class="aiinfo-x" type="button" aria-label="Close">✕</button></div>' +
            '<div class="aiinfo-body">' + HTML + '</div></div>';
        document.body.appendChild(ov);
        ov.addEventListener("click", function (e) { if (e.target === ov) close(); });
        ov.querySelector(".aiinfo-x").addEventListener("click", close);
        document.addEventListener("keydown", function (e) { if (e.key === "Escape") close(); });
        return ov;
    }

    var overlay;
    function open() { overlay.classList.add("open"); document.body.style.overflow = "hidden"; }
    function close() { overlay.classList.remove("open"); document.body.style.overflow = ""; }

    function init() {
        var style = document.createElement("style");
        style.textContent = CSS;
        document.head.appendChild(style);
        overlay = buildModal();

        // Desktop: square button beside Logout
        var footer = document.querySelector(".sidebar-footer");
        if (footer) {
            footer.classList.add("aiinfo-row");
            var b = document.createElement("button");
            b.type = "button";
            b.className = "aiinfo-btn";
            b.title = "How AI is used in this app";
            b.setAttribute("aria-label", "How AI is used in this app");
            b.textContent = "AI";
            b.addEventListener("click", open);
            footer.appendChild(b);
        }

        // Mobile: extra item in the bottom tab bar (next to Exit)
        var tabbar = document.querySelector(".mobile-tabbar");
        if (tabbar) {
            var t = document.createElement("button");
            t.type = "button";
            t.className = "mobile-tabbar-item aiinfo-tab";
            t.innerHTML = '<span class="mtb-icon">🤖</span><span class="mtb-label">AI</span>';
            t.addEventListener("click", open);
            tabbar.appendChild(t);
        }
    }

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
})();