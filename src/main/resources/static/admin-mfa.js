(function () {
  const $ = (id) => document.getElementById(id);
  const msg = $("msg");
  function show(text) { msg.textContent = text; msg.style.display = "block"; }
  async function post(path, params) {
    const res = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(params || {}),
      credentials: "same-origin"
    });
    return res.json();
  }
  (async () => {
    try {
      const d = await post("/admin/mfa/enroll/start");
      if (!d.success) { $("secret").textContent = "Unavailable"; show(d.message || "Could not start setup."); if (d.expired) setTimeout(() => location.href = "/login.html", 1500); return; }
      $("secret").textContent = d.secret.replace(/(.{4})/g, "$1 ").trim();
      $("uri").href = d.otpauthUri;
    } catch (e) { show("Could not start setup."); }
  })();
  $("confirm").addEventListener("click", async () => {
    msg.style.display = "none";
    const code = $("code").value.replace(/\D/g, "");
    if (code.length !== 6) { show("Enter the 6-digit code."); return; }
    try {
      const d = await post("/admin/mfa/enroll/confirm", { code });
      if (!d.success) { show(d.message || "Invalid code."); if (d.expired) setTimeout(() => location.href = "/login.html", 1500); return; }
      $("setup").style.display = "none";
      $("codes").style.display = "block";
      $("codeList").textContent = (d.recoveryCodes || []).join("\n");
      $("done").onclick = () => { location.href = d.redirect || "/admin.html#/overview"; };
    } catch (e) { show("Verification failed. Try again."); }
  });
})();
