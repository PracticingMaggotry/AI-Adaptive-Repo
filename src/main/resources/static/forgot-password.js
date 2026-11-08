// Password recovery on login.html: ask for an emailed code, then submit the code with a new password.
// Server side: PasswordResetController (POST /password/forgot, POST /password/reset).
// The server answers "forgot" the same way whether or not the address has an account, so the wording here
// ("if an account exists") must not promise that an email was sent.
document.addEventListener("DOMContentLoaded", () => {
    const $ = (id) => document.getElementById(id);

    const loginPanel  = $("loginPanel");
    const otpPanel    = $("otpPanel");
    const forgotPanel = $("forgotPanel");
    const resetPanel  = $("resetPanel");
    const forgotLink  = $("forgotLink");
    if (!loginPanel || !forgotPanel || !resetPanel || !forgotLink) return;

    const forgotForm    = $("forgotForm");
    const forgotEmail   = $("fpEmail");
    const forgotMessage = $("forgotMessage");
    const forgotSubmit  = $("forgotSubmit");

    const resetForm     = $("resetForm");
    const resetSubtitle = $("resetSubtitle");
    const codeInput     = $("rpCode");
    const newPwInput    = $("rpNew");
    const confirmInput  = $("rpConfirm");
    const resetTimer    = $("resetTimer");
    const resetMessage  = $("resetMessage");
    const resetSubmit   = $("resetSubmit");
    const resendBtn     = $("resetResend");

    const CODE_LIFETIME_SECONDS = 5 * 60;   // matches PasswordResetStore.EXPIRY_SECONDS
    const RESEND_COOLDOWN_SECONDS = 30;

    let pendingEmail = "";
    let timerHandle = null, cooldownHandle = null;

    function showPanel(panel) {
        [loginPanel, otpPanel, forgotPanel, resetPanel].forEach(p => { if (p) p.style.display = "none"; });
        panel.style.display = "block";
    }

    function showMsg(el, text, isError) {
        el.textContent = text;
        el.className = "panel-msg " + (isError ? "error" : "ok");
        el.style.display = "block";
    }

    function hideMsg(el) { el.style.display = "none"; }

    function stopTimers() {
        clearInterval(timerHandle);
        clearInterval(cooldownHandle);
    }

    function startCodeTimer(seconds) {
        clearInterval(timerHandle);
        let remaining = seconds;
        resetSubmit.disabled = false;
        const render = () => {
            const m = Math.floor(remaining / 60), s = remaining % 60;
            resetTimer.textContent = `Code expires in ${m}:${String(s).padStart(2, "0")}`;
            resetTimer.classList.toggle("expiring", remaining <= 60);
        };
        render();
        timerHandle = setInterval(() => {
            remaining--;
            if (remaining <= 0) {
                clearInterval(timerHandle);
                resetTimer.textContent = "Code expired. Resend a new one.";
                resetTimer.classList.add("expiring");
                resetSubmit.disabled = true;
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

    function goToLogin(noticeText) {
        stopTimers();
        forgotForm.reset();
        resetForm.reset();
        hideMsg(forgotMessage);
        hideMsg(resetMessage);
        pendingEmail = "";
        showPanel(loginPanel);
        if (noticeText) {
            const p = loginPanel.querySelector("p");
            if (p) { p.textContent = noticeText; p.style.color = "#4ade80"; }
        }
        const pw = $("pw");
        if (pw) pw.value = "";
    }

    function openForgot() {
        hideMsg(forgotMessage);
        forgotSubmit.disabled = false;
        forgotSubmit.textContent = "Send reset code";
        const typed = $("email");
        if (typed && typed.value && !forgotEmail.value) forgotEmail.value = typed.value.trim();
        showPanel(forgotPanel);
        forgotEmail.focus();
    }

    // Sends (or re-sends) the code. Returns the server's JSON, or null if the request itself failed.
    async function requestCode(email, messageEl) {
        try {
            const data = await apiFetch("/password/forgot", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: new URLSearchParams({ email })
            });
            if (!data.success) showMsg(messageEl, data.message || "Could not send a code.", true);
            return data;
        } catch (err) {
            showMsg(messageEl, err.message || "Could not send a code. Please try again.", true);
            return null;
        }
    }

    function openReset(email, message) {
        pendingEmail = email;
        resetForm.reset();
        hideMsg(resetMessage);
        resetSubtitle.textContent = message || "Enter the 6-digit code we sent you and choose a new password.";
        resetSubmit.textContent = "Reset password";
        showPanel(resetPanel);
        startCodeTimer(CODE_LIFETIME_SECONDS);
        startResendCooldown(RESEND_COOLDOWN_SECONDS);
        codeInput.focus();
    }

    forgotLink.addEventListener("click", openForgot);
    $("forgotBack").addEventListener("click", () => goToLogin());
    $("resetBack").addEventListener("click", () => goToLogin());

    forgotForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const email = forgotEmail.value.trim();
        if (!email) { showMsg(forgotMessage, "Enter your email address.", true); return; }

        hideMsg(forgotMessage);
        forgotSubmit.disabled = true;
        forgotSubmit.textContent = "Sending...";
        const data = await requestCode(email, forgotMessage);
        if (data && data.success) {
            openReset(data.email || email, data.message);
        } else {
            forgotSubmit.disabled = false;
            forgotSubmit.textContent = "Send reset code";
        }
    });

    resendBtn.addEventListener("click", async () => {
        resendBtn.disabled = true;
        const data = await requestCode(pendingEmail, resetMessage);
        if (data && data.success) {
            codeInput.value = "";
            startCodeTimer(CODE_LIFETIME_SECONDS);
            showMsg(resetMessage, data.message, false);
            codeInput.focus();
        }
        startResendCooldown(RESEND_COOLDOWN_SECONDS);
    });

    codeInput.addEventListener("input", () => {
        codeInput.value = codeInput.value.replace(/\D/g, "").slice(0, 6);
    });

    resetForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const code = codeInput.value.trim();
        const newPassword = newPwInput.value;

        if (!/^\d{6}$/.test(code)) { showMsg(resetMessage, "Enter the 6-digit code from the email.", true); return; }
        if (!newPassword) { showMsg(resetMessage, "Enter a new password.", true); return; }
        if (newPassword !== confirmInput.value) { showMsg(resetMessage, "The two passwords don't match.", true); return; }

        hideMsg(resetMessage);
        resetSubmit.disabled = true;
        resetSubmit.textContent = "Resetting...";
        try {
            const data = await apiFetch("/password/reset", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: new URLSearchParams({ email: pendingEmail, otp: code, newPassword })
            });
            if (data.success) {
                goToLogin(data.message || "Password reset. Log in with your new password.");
                return;
            }
            showMsg(resetMessage, data.message || "Could not reset the password.", true);
        } catch (err) {
            showMsg(resetMessage, err.message || "Could not reset the password. Please try again.", true);
        }
        resetSubmit.disabled = false;
        resetSubmit.textContent = "Reset password";
    });
});
