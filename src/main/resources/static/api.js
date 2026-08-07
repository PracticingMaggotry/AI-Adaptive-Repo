const API_BASE = (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1") && window.location.port !== "8080"
    ? "http://localhost:8080"
    : "";

function getCsrfToken() {
    const match = document.cookie.split("; ").find(c => c.startsWith("XSRF-TOKEN="));
    return match ? decodeURIComponent(match.split("=")[1]) : "";
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

// Mirrors the backend's DifficultyTier.fromScore (HARD_MIN=80, MEDIUM_MIN=50)
// so any page that needs to label a score never invents its own cutoffs.
// Must live at top level — pages like quizfinish.html call this directly,
// and nesting it inside apiFetch (as before) made it inaccessible outside
// that function, causing a ReferenceError on the fallback path.
const DIFFICULTY_HARD_MIN = 80;
const DIFFICULTY_MEDIUM_MIN = 50;
function difficultyTierForScore(score) {
    if (score >= DIFFICULTY_HARD_MIN) return "Hard";
    if (score >= DIFFICULTY_MEDIUM_MIN) return "Medium";
    return "Easy";
}

// ══════════════════════════════════════════════════════════════════════
// ── DEVELOPER-ONLY DEMO/PLACEHOLDER FALLBACK GATE ──────────────────────
// ══════════════════════════════════════════════════════════════════════
//
// Several pages (dashboard.html, reports.html, profile.html, admin.html,
// admindashboard.html, quizhub.html, quizfinish.html) have a hardcoded
// DEMO_DATA / PLACEHOLDER DATA object they render when a live API call
// fails, purely so the mobile layout has something real to lay out and
// screenshot against during development.
//
// The bug this closes: apiFetch() throws the same generic Error for a
// 401 (expired session), a 403 (blocked IP), a 500 (server error), and a
// genuine network failure — every page's catch block treated all of
// those identically to "backend is offline" and rendered fake data to
// WHOEVER happened to be looking at the screen at that moment, including
// real logged-in students/admins. That's a real information-integrity
// problem, not just a dev convenience gone stale.
//
// Fix: demo data may now only ever render for the account(s) listed in
// DEV_FALLBACK_EMAILS below. Every other user who hits a failed fetch
// sees an honest error/empty state instead. Edit this list to your own
// login email(s) before relying on it.
const DEV_FALLBACK_EMAILS = [
    "roninwolfsoriano@gmail.com" // ← set this to your real account email(s)
];

let _demoFallbackAllowedCache = null;

/**
 * Resolves to true only when the currently logged-in account's email is
 * in DEV_FALLBACK_EMAILS. Result is cached for the lifetime of the page
 * (one extra /api/me round trip per page load, not per failed fetch).
 * Fails closed — any error while checking means demo data is NOT shown.
 */
async function isDemoFallbackAllowed() {
    if (_demoFallbackAllowedCache !== null) return _demoFallbackAllowedCache;
    try {
        const me = await apiFetch("/api/me");
        const email = (me && me.email ? String(me.email) : "").trim().toLowerCase();
        _demoFallbackAllowedCache = !!email && DEV_FALLBACK_EMAILS
            .map(e => e.trim().toLowerCase())
            .includes(email);
    } catch (e) {
        _demoFallbackAllowedCache = false;
    }
    return _demoFallbackAllowedCache;
}

/**
 * Drops a small, honest error banner into the page when a live fetch
 * failed and the viewer is NOT the developer account (so no fake data
 * was substituted). Safe to call multiple times — only inserts once.
 */
function renderFetchErrorBanner(message) {
    if (document.getElementById("fetchErrorBanner")) return;
    const banner = document.createElement("div");
    banner.id = "fetchErrorBanner";
    banner.style.cssText = "margin:16px 26px;padding:14px 18px;background:#fef2f2;" +
        "border:1px solid #fecaca;border-radius:12px;color:#7f1d1d;font-size:0.85rem;line-height:1.5;";
    banner.textContent = message || "We couldn't load your data just now. Please refresh the page or try again shortly.";
    const main = document.querySelector(".main") || document.body;
    main.insertBefore(banner, main.firstChild);
}

// ── Rich text rendering (math + code) ────────────────────────────────────
// Shared by quizpage.html, learninghub.html, and admin.html's Content
// Review modal — anywhere question text, excerpts, rubrics, lesson content,
// or extracted material text is shown to a person. Handouts tagged under
// the "Mathematics & Quantitative Reasoning" or "Computer Science &
// Programming" material categories routinely contain LaTeX-style math
// ($x^2$, $$\int f(x)dx$$) or fenced code (```python ... ```) — without
// this, that content just renders as mangled plain text.
//
// Requires KaTeX (+ the auto-render extension) and highlight.js to be
// loaded via <script>/<link> tags in the page's <head> — see quizpage.html,
// learninghub.html, and admin.html for the CDN includes. Both are optional:
// if the libraries aren't present (window.hljs / window.renderMathInElement
// undefined), these functions silently degrade to plain escaped text with
// no math/code styling, rather than throwing.
const KATEX_DELIMITERS = [
    { left: "$$", right: "$$", display: true },
    { left: "$", right: "$", display: false },
    { left: "\\(", right: "\\)", display: false },
    { left: "\\[", right: "\\]", display: true }
];

function escapeRichText(value) {
    return String(value ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[c]));
}

/**
 * Converts raw plain text into safe HTML: fenced ```lang ... ``` blocks
 * become <pre><code class="language-lang">, single backtick `code` spans
 * become <code>, and everything else is HTML-escaped. $...$ / $$...$$ /
 * \(...\) / \[...\] math delimiters are left untouched in the escaped text
 * so KaTeX's auto-render extension can find and typeset them afterward.
 */
function richTextToHtml(raw) {
    const text = String(raw ?? "");
    // String.split with a capturing regex interleaves the text between
    // matches with the captured groups: [before, lang1, code1, between, ...]
    const parts = text.split(/```(\w*)\n?([\s\S]*?)```/g);
    let html = "";
    for (let i = 0; i < parts.length; i += 3) {
        const plain = parts[i] || "";
        html += escapeRichText(plain).replace(/`([^`\n]+)`/g, (m, code) => `<code>${escapeRichText(code)}</code>`);
        const lang = parts[i + 1];
        const code = parts[i + 1] !== undefined ? parts[i + 2] : undefined;
        if (code !== undefined) {
            const cls = lang ? ` class="language-${escapeRichText(lang)}"` : "";
            html += `<pre><code${cls}>${escapeRichText(code)}</code></pre>`;
        }
    }
    return html;
}

/** Runs KaTeX auto-render over an element's existing DOM. Safe to call on
 * containers that already hold real HTML (inputs, badges, etc.) since it
 * only scans for math delimiters in text nodes — it never touches innerHTML. */
function renderMathOnly(el) {
    const node = typeof el === "string" ? document.getElementById(el) : el;
    if (!node || !window.renderMathInElement) return;
    try {
        renderMathInElement(node, { delimiters: KATEX_DELIMITERS, throwOnError: false });
    } catch (e) { /* non-fatal — leave text as-is */ }
}

/**
 * Renders `raw` plain text into `el` (element or element id), converting
 * fenced/inline code into syntax-highlighted blocks and typesetting any
 * $...$/$$...$$ math. Use this instead of .textContent wherever question
 * text, hints, explanations, essay rubrics, or lesson content is displayed.
 */
function renderRichText(el, raw) {
    const node = typeof el === "string" ? document.getElementById(el) : el;
    if (!node) return;
    node.innerHTML = richTextToHtml(raw);
    if (window.hljs) {
        node.querySelectorAll("pre code").forEach(block => {
            try { hljs.highlightElement(block); } catch (e) {}
        });
    }
    renderMathOnly(node);
}

async function apiFetch(path, options = {}) {
    const method = (options.method || "GET").toUpperCase();
    const isFormData = options.body instanceof FormData;

    const csrfHeaders = (!SAFE_METHODS.has(method))
        ? { "X-XSRF-TOKEN": getCsrfToken() }
        : {};

    const headers = {
        ...(isFormData ? {} : { "Content-Type": "application/json" }),
        ...csrfHeaders,
        ...(options.headers || {})
    };

    const response = await fetch(`${API_BASE}${path}`, {
        credentials: "include",
        ...options,
        headers
    });

    const contentType = response.headers.get("content-type") || "";
    const data = contentType.includes("application/json") ? await response.json() : await response.text();

    if (!response.ok) {
        const error = new Error(typeof data === "string" ? data : (data.message || "Request failed"));
        // Preserved so callers can distinguish "you're logged out" (401)
        // from "server hiccuped" (5xx) from "IP blocked" (403), instead of
        // treating every failure identically — see the demo-fallback gate
        // above, and DEV_FALLBACK_EMAILS's javadoc-style comment for why
        // this matters.
        error.status = response.status;
        throw error;
    }

    return data;
}

/**
 * Shared catch-block helper for every page's top-level data load.
 * Call this from a try/catch around the page's main API fetch(es):
 *
 *   } catch (error) {
 *       await handleFetchFailure(error, {
 *           renderDemo: () => renderDashboard(dashboardData),
 *           renderEmpty: () => renderFetchErrorBanner("Could not load your dashboard."),
 *       });
 *   }
 *
 * - 401 → redirect to /login.html (session expired), nothing else runs.
 * - Otherwise, demo data is rendered ONLY for DEV_FALLBACK_EMAILS accounts;
 *   every other viewer gets renderEmpty() (an honest error/empty state).
 */
async function handleFetchFailure(error, { renderDemo, renderEmpty }) {
    if (error && error.status === 401) {
        window.location.href = "/login.html";
        return;
    }
    if (await isDemoFallbackAllowed()) {
        console.warn("Dev account — showing placeholder data after a failed fetch:", error);
        if (renderDemo) renderDemo();
    } else {
        console.error("Live data fetch failed:", error);
        if (renderEmpty) renderEmpty();
    }
}