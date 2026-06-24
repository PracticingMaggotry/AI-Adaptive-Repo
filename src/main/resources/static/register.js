document.addEventListener("DOMContentLoaded", () => {
    const form = document.querySelector("form");
    if (!form) return;

    const errorDiv = document.createElement("div");
    errorDiv.style.cssText = "margin-top:12px; padding:10px 14px; border-radius:8px; font-size:0.85rem; display:none;";
    form.appendChild(errorDiv);

    function showMessage(msg, isError) {
        errorDiv.textContent = msg;
        errorDiv.style.display = "block";
        if (isError) {
            errorDiv.style.background = "#fef2f2";
            errorDiv.style.color = "#991b1b";
            errorDiv.style.border = "1px solid #fecaca";
        } else {
            errorDiv.style.background = "#f0fdf4";
            errorDiv.style.color = "#065f46";
            errorDiv.style.border = "1px solid #bbf7d0";
        }
    }

    function showError(msg) {
        showMessage(msg, true);
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        errorDiv.style.display = "none";
        const btn = form.querySelector("button[type=submit]");
        btn.disabled = true;
        btn.textContent = "Creating account...";
        try {
            const body = new URLSearchParams(new FormData(form));
            const data = await apiFetch("/register", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body
            });
            if (data.success) {
                showMessage("Account created! Redirecting to login...", false);
                setTimeout(() => { window.location.href = "/login.html"; }, 1200);
            } else {
                showError(data.message || "Registration failed.");
                btn.disabled = false;
                btn.textContent = "Register";
            }
        } catch (error) {
            showMessage(error.message || "Registration failed. Please try again.", true);
            btn.disabled = false;
            btn.textContent = "Register";
        }
    });
});