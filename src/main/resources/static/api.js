const API_BASE = "";

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

// The one definition of "needs practice / on track / mastered" for every learner page. Keep in step with
// TopicStatus.java. These are display bands for a topic's average score; they do not decide the next quiz's level
// (that comes from the server's ELO estimate). No red: needing practice is a to-do, not an error.
const TOPIC_WEAK_BELOW = 60;
const TOPIC_MASTERED_AT = 80;
const TOPIC_BAND_COLORS = { mastered: "#10b981", onTrack: "#3b82f6", needsPractice: "#f59e0b" };
function topicBand(score) {
    const s = Number(score);
    if (s >= TOPIC_MASTERED_AT) return { key: "mastered", label: "Mastered", color: TOPIC_BAND_COLORS.mastered, cls: "green" };
    if (s >= TOPIC_WEAK_BELOW) return { key: "on-track", label: "On track", color: TOPIC_BAND_COLORS.onTrack, cls: "blue" };
    return { key: "needs-practice", label: "Needs practice", color: TOPIC_BAND_COLORS.needsPractice, cls: "orange" };
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
        // Containers (Tomcat, proxies) answer malformed requests with a full HTML error page.
        // Show a short generic message instead of dumping markup into the UI.
        let message;
        if (typeof data === "string") {
            const looksLikeHtml = /^\s*<(!doctype|html|head|body)/i.test(data);
            message = (!data.trim() || looksLikeHtml)
                ? `Request failed (HTTP ${response.status}). Please try again.`
                : data.slice(0, 300);
        } else {
            message = (data && data.message) || "Request failed";
        }
        const error = new Error(message);
        error.status = response.status;
        throw error;
    }

    return data;
}

/**
 * Sets the topbar avatar's letter from the logged-in user's real name.
 * Shared by every page (mobile and desktop share the same .avatar element).
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