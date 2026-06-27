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