(function () {
    "use strict";
    if (window.__consentInit) return;
    window.__consentInit = true;

    const TEXT = `
<p><b>Research study.</b> You are taking part in a study of an AI-assisted learning system, conducted by the BS701 thesis group
(Barrientos, Shawn Michael P.; Miller, Christian M.; Patriarca, John David D.S.; Soriano, Ronin Isaiah V.) of STI College Muñoz-EDSA.
Because some participants are minors, your parent or guardian has given written permission, and you have agreed to take part.</p>
<p><b>What is collected:</b> your quiz answers and scores, the questions shown to you, time spent, and how the system performs.
In the research data you are identified only by a participant code, not your name.</p>
<p><b>AI service:</b> the content of handouts you upload and your answers (including written answers) are sent to an external AI service,
Anthropic's Claude, to create questions, lessons and feedback. Do not put your name or personal details in handouts or answers.</p>
<p><b>How long data is kept:</b> study data is kept until the end of the semester, December 18, 2026, and is then deleted.</p>
<p><b>Your rights:</b> taking part is voluntary and does not affect your grades. You may stop at any time by telling your teacher or the researcher,
and you may ask for your data to be removed.
Questions: Ronin Isaiah V. Soriano, soriano.357912@munoz.sti.edu.ph, 09289959812.</p>`;

    async function init() {
        if (typeof apiFetch !== "function") return;
        let st;
        try { st = await apiFetch("/api/consent/status"); } catch (e) { return; }
        if (!st || !st.required || st.accepted) return;

        const ov = document.createElement("div");
        ov.style.cssText = "position:fixed;inset:0;background:rgba(15,23,42,.75);z-index:20000;display:flex;align-items:center;justify-content:center;padding:20px;";
        ov.innerHTML = `<div style="background:#fff;border-radius:16px;max-width:560px;width:100%;max-height:90vh;overflow-y:auto;padding:24px;font-size:.88rem;line-height:1.6;color:#374151;">
            <div style="font-size:1.05rem;font-weight:800;color:#111827;margin-bottom:10px;">Study consent notice</div>
            ${TEXT}
            <label style="display:flex;gap:8px;align-items:flex-start;margin:14px 0;font-weight:600;">
              <input type="checkbox" id="consentChk" style="margin-top:4px;"> I have read this and agree to take part.</label>
            <div style="display:flex;gap:10px;">
              <button id="consentNo" style="flex:1;padding:11px;border-radius:10px;border:1px solid #e2e8f0;background:#f8fafc;font-weight:700;cursor:pointer;">Log out</button>
              <button id="consentYes" disabled style="flex:1;padding:11px;border-radius:10px;border:0;background:#0093ad;color:#fff;font-weight:700;cursor:pointer;opacity:.5;">Continue</button>
            </div>
            <div id="consentErr" style="display:none;margin-top:10px;color:#991b1b;"></div></div>`;
        document.body.appendChild(ov);
        document.body.style.overflow = "hidden";

        const chk = ov.querySelector("#consentChk"), yes = ov.querySelector("#consentYes");
        chk.onchange = () => { yes.disabled = !chk.checked; yes.style.opacity = chk.checked ? "1" : ".5"; };
        ov.querySelector("#consentNo").onclick = async () => {
            try { await apiFetch("/logout", { method: "POST" }); } catch (e) {}
            window.location.href = "/login.html";
        };
        yes.onclick = async () => {
            yes.disabled = true;
            try {
                await apiFetch("/api/consent/accept", { method: "POST" });
                window.location.reload();
            } catch (e) {
                const err = ov.querySelector("#consentErr");
                err.textContent = e.message || "Could not save. Try again.";
                err.style.display = "block";
                yes.disabled = false;
            }
        };
    }

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
})();
