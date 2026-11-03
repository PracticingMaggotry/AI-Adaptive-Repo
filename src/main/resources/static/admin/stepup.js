/*
 * Step-up for admin mutations. When the server answers 403 {stepUpRequired:true}, ask for a fresh
 * authenticator (or recovery) code, POST it to /api/admin/stepup, then retry the original request once.
 * Loaded before api.js so every fetch() in the admin UI goes through this wrapper.
 */
(function () {
  const origFetch = window.fetch.bind(window);
  let pending = null;

  function csrf() {
    const m = document.cookie.split("; ").find(c => c.startsWith("XSRF-TOKEN="));
    return m ? decodeURIComponent(m.split("=")[1]) : "";
  }

  function askCode() {
    if (pending) return pending;
    pending = new Promise((resolve) => {
      const wrap = document.createElement("div");
      wrap.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;z-index:2147483647;";
      wrap.innerHTML =
        '<div style="background:#111827;color:#f9fafb;padding:24px;border-radius:12px;width:min(92vw,360px);font-family:system-ui,sans-serif;">' +
        '<h3 style="margin:0 0 8px;">Confirm it\u2019s you</h3>' +
        '<p style="margin:0 0 12px;font-size:14px;color:#9ca3af;">Enter your authenticator code (or a recovery code) to continue.</p>' +
        '<input id="suCode" autocomplete="one-time-code" maxlength="12" style="width:100%;box-sizing:border-box;padding:10px;font-size:18px;text-align:center;border-radius:8px;border:1px solid #374151;background:#0b1220;color:#f9fafb;">' +
        '<div id="suErr" style="color:#f87171;font-size:13px;min-height:18px;margin-top:8px;"></div>' +
        '<div style="display:flex;gap:8px;margin-top:8px;"><button id="suOk" style="flex:1;padding:10px;border-radius:8px;border:0;background:#2563eb;color:#fff;font-size:15px;cursor:pointer;">Confirm</button>' +
        '<button id="suNo" style="padding:10px 14px;border-radius:8px;border:1px solid #374151;background:transparent;color:#d1d5db;cursor:pointer;">Cancel</button></div></div>';
      document.body.appendChild(wrap);
      const input = wrap.querySelector("#suCode"), err = wrap.querySelector("#suErr");
      input.focus();
      const close = (v) => { wrap.remove(); pending = null; resolve(v); };
      async function submit() {
        const res = await origFetch("/api/admin/stepup", {
          method: "POST", credentials: "same-origin",
          headers: { "Content-Type": "application/x-www-form-urlencoded", "X-XSRF-TOKEN": csrf() },
          body: new URLSearchParams({ code: input.value.trim() })
        });
        let d = {}; try { d = await res.json(); } catch (e) {}
        if (d.success) close(true);
        else if (d.adminSessionEnded) { location.href = "/login.html"; }
        else err.textContent = d.message || "Invalid code.";
      }
      wrap.querySelector("#suOk").onclick = submit;
      wrap.querySelector("#suNo").onclick = () => close(false);
      input.addEventListener("keydown", (e) => { if (e.key === "Enter") submit(); });
    });
    return pending;
  }

  window.fetch = async function (input, init) {
    // Keep a copy of the request so it can be replayed after step-up.
    const retryInit = init ? Object.assign({}, init) : init;
    const res = await origFetch(input, init);
    if (res.status === 403) {
      let body = null;
      try { body = await res.clone().json(); } catch (e) {}
      if (body && body.stepUpRequired) {
        if (await askCode()) return origFetch(input, retryInit);
      }
    }
    if (res.status === 401) {
      try {
        const b = await res.clone().json();
        if (b && b.adminSessionEnded) location.href = "/login.html?admin_ended=session";
      } catch (e) {}
    }
    return res;
  };
})();
