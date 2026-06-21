document.addEventListener("DOMContentLoaded", () => {
    const form = document.querySelector("form");
    if (!form) return;

    const errorDiv = document.createElement("div");
    errorDiv.style.cssText = "margin-top:12px; padding:10px 14px; border-radius:8px; font-size:0.85rem; display:none;";
    form.appendChild(errorDiv);

    function showError(msg) {
        errorDiv.textContent = msg;
        errorDiv.style.display = "block";
        errorDiv.style.background = "#fef2f2";
        errorDiv.style.color = "#991b1b";
        errorDiv.style.border = "1px solid #fecaca";
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        errorDiv.style.display = "none";
        const btn = form.querySelector("button[type=submit]");
        btn.disabled = true;
        btn.textContent = "Logging in...";
        try {
            const body = new URLSearchParams(new FormData(form));
            const data = await apiFetch("/login", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body
            });
            if (data.success) {
                // Route admins to their own dashboard, students to theirs
                if (data.isAdmin) {
                    window.location.href = "/admindashboard.html";
                } else {
                    window.location.href = data.redirect || "/dashboard.html";
                }
            } else {
                showError(data.message || "Invalid credentials.");
                btn.disabled = false;
                btn.textContent = "Login";
            }
        } catch (error) {
            showError(error.message || "Login failed. Please try again.");
            btn.disabled = false;
            btn.textContent = "Login";
        }
    });
});