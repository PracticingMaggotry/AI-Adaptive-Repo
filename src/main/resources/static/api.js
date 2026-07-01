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
        throw new Error(typeof data === "string" ? data : (data.message || "Request failed"));
    }

    return data;
}