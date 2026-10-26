document.addEventListener("DOMContentLoaded", () => {
    const form = document.querySelector("form");
    if (!form) return;

    const errorDiv = document.createElement("div");
    errorDiv.style.cssText = "margin-top:12px; padding:10px 14px; border-radius:8px; font-size:0.85rem; display:none;";
    form.appendChild(errorDiv);

    const submitBtn = form.querySelector("button[type=submit]");
    const submitBtnDefaultText = submitBtn ? submitBtn.textContent : "Login";

    function resetFormState() {
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.textContent = submitBtnDefaultText;
        }
        errorDiv.style.display = "none";
    }

    // When the page is restored from bfcache (browser back/forward),
    // event.persisted is true and DOMContentLoaded does NOT fire again,
    // so we must reset any "in-flight" UI state here.
    window.addEventListener("pageshow", (event) => {
        if (event.persisted) {
            resetFormState();
        }
    });

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
            if (data.success && data.requiresVerification) {
                showOtpPanel(data.message);
            } else if (data.success) {
                finishLogin(data);
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

    // ── Emailed login code (non-admin accounts) ──
    const loginPanel = document.getElementById("loginPanel");
    const otpPanel   = document.getElementById("otpPanel");
    const otpMessage = document.getElementById("otpMessage");
    const otpTimer   = document.getElementById("otpTimer");
    const verifyBtn  = document.getElementById("verifyBtn");
    const resendBtn  = document.getElementById("resendBtn");
    const digits     = Array.from(document.querySelectorAll(".otp-digit"));
    let timerHandle = null, cooldownHandle = null;

    function finishLogin(data) {
        window.location.href = data.isAdmin ? "/admin.html#/overview" : (data.redirect || "/dashboard.html");
    }

    function showOtpMsg(text, isError) {
        otpMessage.textContent = text;
        otpMessage.style.display = "block";
        otpMessage.style.background = isError ? "#fef2f2" : "#f0fdf4";
        otpMessage.style.color = isError ? "#991b1b" : "#065f46";
        otpMessage.style.border = isError ? "1px solid #fecaca" : "1px solid #bbf7d0";
    }

    function clearOtp() { digits.forEach(d => { d.value = ""; d.classList.remove("error"); }); }
    function getOtp() { return digits.map(d => d.value).join(""); }

    function startTimer(seconds) {
        clearInterval(timerHandle);
        let remaining = seconds;
        verifyBtn.disabled = false;
        const render = () => {
            const m = Math.floor(remaining / 60), s = remaining % 60;
            otpTimer.textContent = `Code expires in ${m}:${String(s).padStart(2, "0")}`;
            otpTimer.classList.toggle("expiring", remaining <= 60);
        };
        render();
        timerHandle = setInterval(() => {
            remaining--;
            if (remaining <= 0) {
                clearInterval(timerHandle);
                otpTimer.textContent = "Code expired. Resend or log in again.";
                otpTimer.classList.add("expiring");
                verifyBtn.disabled = true;
            } else render();
        }, 1000);
    }

    function startResendCooldown(seconds) {
        clearInterval(cooldownHandle);
        resendBtn.disabled = true;
        let remaining = seconds;
        resendBtn.textContent = `Resend code (${remaining}s)`;
        cooldownHandle = setInterval(() => {
            remaining--;
            if (remaining <= 0) {
                clearInterval(cooldownHandle);
                resendBtn.disabled = false;
                resendBtn.textContent = "Resend code";
            } else resendBtn.textContent = `Resend code (${remaining}s)`;
        }, 1000);
    }

    function showOtpPanel(message) {
        loginPanel.style.display = "none";
        otpPanel.style.display = "block";
        document.getElementById("otpSubtitle").textContent = message || "Enter the 6-digit login code we sent you.";
        otpMessage.style.display = "none";
        clearOtp();
        startTimer(5 * 60);
        startResendCooldown(30);
        digits[0].focus();
    }

    function showLoginPanel() {
        clearInterval(timerHandle);
        clearInterval(cooldownHandle);
        otpPanel.style.display = "none";
        loginPanel.style.display = "block";
        const btn = form.querySelector("button[type=submit]");
        btn.disabled = false;
        btn.textContent = submitBtnDefaultText;
    }

    digits.forEach((input, i) => {
        input.addEventListener("input", () => {
            input.value = input.value.replace(/\D/g, "").slice(-1);
            if (input.value && i < digits.length - 1) digits[i + 1].focus();
        });
        input.addEventListener("keydown", (e) => {
            if (e.key === "Backspace" && !input.value && i > 0) { digits[i - 1].value = ""; digits[i - 1].focus(); }
            if (e.key === "Enter") verifyOtp();
        });
        input.addEventListener("paste", (e) => {
            e.preventDefault();
            const pasted = (e.clipboardData || window.clipboardData).getData("text").replace(/\D/g, "").slice(0, 6);
            pasted.split("").forEach((ch, j) => { if (digits[j]) digits[j].value = ch; });
            const last = Math.min(pasted.length, digits.length) - 1;
            if (last >= 0) digits[last].focus();
        });
    });

    async function verifyOtp() {
        const otp = getOtp();
        if (otp.length !== 6) { showOtpMsg("Please enter all 6 digits.", true); return; }
        verifyBtn.disabled = true;
        verifyBtn.textContent = "Verifying...";
        try {
            const data = await apiFetch("/login/verify", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: new URLSearchParams({ otp })
            });
            if (data.success) { finishLogin(data); return; }
            if (data.expired) { showLoginPanel(); showError(data.message); return; }
            showOtpMsg(data.message || "Incorrect code.", true);
            digits.forEach(d => d.classList.add("error"));
            setTimeout(() => digits.forEach(d => d.classList.remove("error")), 400);
        } catch (err) {
            showOtpMsg(err.message || "Verification failed. Please try again.", true);
        }
        verifyBtn.disabled = false;
        verifyBtn.textContent = "Verify & Log in";
    }

    verifyBtn.addEventListener("click", verifyOtp);

    resendBtn.addEventListener("click", async () => {
        resendBtn.disabled = true;
        try {
            const data = await apiFetch("/login/resend", { method: "POST" });
            if (data.success) {
                clearOtp();
                startTimer(5 * 60);
                startResendCooldown(30);
                showOtpMsg(data.message || "A new code has been sent.", false);
                digits[0].focus();
            } else if (data.expired) {
                showLoginPanel();
                showError(data.message);
            } else {
                showOtpMsg(data.message || "Could not resend code.", true);
                startResendCooldown(30);
            }
        } catch (err) {
            showOtpMsg(err.message || "Could not resend code.", true);
            startResendCooldown(30);
        }
    });

    document.getElementById("backBtn").addEventListener("click", showLoginPanel);
});
