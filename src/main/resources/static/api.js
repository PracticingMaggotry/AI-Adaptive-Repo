const API_BASE = "";

// --- Global bfcache fix ---
// Browsers can restore a page from the back-forward cache (bfcache) when the
// user hits Back/Forward, showing the exact DOM as it was left (e.g. a button
// stuck on "Logging in..." or "Generating...") instead of re-running page JS.
// event.persisted is true only for a bfcache restore (not a normal load), so
// we force a full reload in that case to guarantee fresh state everywhere.
window.addEventListener("pageshow", (event) => {
    if (event.persisted) {
        window.location.reload();
    }
});

function getCsrfToken() {
    const match = document.cookie.split("; ").find(c => c.startsWith("XSRF-TOKEN="));
    return match ? decodeURIComponent(match.split("=")[1]) : "";
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

const DIFFICULTY_HARD_MIN = 80;
const DIFFICULTY_MEDIUM_MIN = 50;
function difficultyTierForScore(score) {
    if (score >= DIFFICULTY_HARD_MIN) return "Hard";
    if (score >= DIFFICULTY_MEDIUM_MIN) return "Medium";
    return "Easy";
}

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

const KATEX_DELIMITERS = [
    { left: "$$", right: "$$", display: true },
    { left: "$", right: "$", display: false },
    { left: "\\(", right: "\\)", display: false },
    { left: "\\[", right: "\\]", display: true }
];

function escapeRichText(value) {
    return String(value ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[c]));
}

function richTextToHtml(raw) {
    const text = String(raw ?? "");
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

function renderMathOnly(el) {
    const node = typeof el === "string" ? document.getElementById(el) : el;
    if (!node || !window.renderMathInElement) return;
    try {
        renderMathInElement(node, { delimiters: KATEX_DELIMITERS, throwOnError: false });
    } catch (e) {}
}

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
        error.status = response.status;
        throw error;
    }

    return data;
}

/**
 * Sets the topbar avatar's letter from the logged-in user's real name.
 * Every page's topbar previously hardcoded a placeholder letter (e.g. "U"
 * for students, "A" for admins) directly in the HTML and never updated it
 * after the real session loaded — this is the one shared fix so every
 * page (mobile and desktop, since they share the same .avatar element)
 * shows the actual first initial instead.
 */
function applyAvatarInitial(name) {
    if (!name) return;
    const avatarEl = document.querySelector(".topbar-actions .avatar");
    if (avatarEl) avatarEl.textContent = name.trim().charAt(0).toUpperCase();
}

async function handleFetchFailure(error, { renderEmpty }) {
    if (error && error.status === 401) {
        window.location.href = "/login.html";
        return;
    }
    console.error("Live data fetch failed:", error);
    if (renderEmpty) renderEmpty();
}