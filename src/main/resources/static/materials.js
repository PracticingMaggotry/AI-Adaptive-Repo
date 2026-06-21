const form = document.getElementById("uploadForm");
const uploadBtn = document.getElementById("uploadBtn");
const uploadMessage = document.getElementById("uploadMessage");
const materialsList = document.getElementById("materialsList");
const refreshBtn = document.getElementById("refreshBtn");

function setMessage(text, type = "") {
    uploadMessage.textContent = text;
    uploadMessage.className = `message ${type}`;
}

function formatSize(bytes) {
    if (!bytes) return "0 KB";
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

async function loadMaterials() {
    try {
        const data = await apiFetch("/api/materials");
        const materials = data.materials || [];
        if (materials.length === 0) {
            materialsList.innerHTML = `<div class="empty">No uploaded materials yet. Upload a TXT or text-based PDF handout to generate content-based quiz questions.</div>`;
            return;
        }
        materialsList.innerHTML = materials.map(item => `
            <article class="material-item">
                <div class="material-top">
                    <div>
                        <span class="topic-pill">${item.topic}</span>
                        <div class="filename">${item.filename}</div>
                        <div class="meta">Uploaded ${item.uploadedAt || "recently"} • ${formatSize(item.sizeBytes)}</div>
                    </div>
                    <div style="display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end;">
                        <a class="start-link" href="${item.quizUrl}">Start Content Quiz</a>
                    </div>
                </div>
                <p class="preview">${item.preview || "Material saved."}</p>
            </article>
        `).join("");
    } catch (err) {
        materialsList.innerHTML = `<div class="empty">Please log in first, then return to this page.</div>`;
    }
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    setMessage("Uploading material and generating quiz questions from extracted text...");
    uploadBtn.disabled = true;

    try {
        const formData = new FormData(form);
        const result = await apiFetch("/api/materials/upload", {
            method: "POST",
            body: formData
        });
        setMessage(result.message || "Upload successful.", "success");
        form.reset();
        await loadMaterials();
        if (result.quizUrl) {
            setTimeout(() => {
                if (confirm("Material uploaded. Start the content-based quiz now?")) {
                    window.location.href = result.quizUrl;
                }
            }, 200);
        }
    } catch (err) {
        setMessage(err.message || "Upload failed. Check IntelliJ console.", "error");
    } finally {
        uploadBtn.disabled = false;
    }
});

refreshBtn.addEventListener("click", loadMaterials);
document.addEventListener("DOMContentLoaded", loadMaterials);