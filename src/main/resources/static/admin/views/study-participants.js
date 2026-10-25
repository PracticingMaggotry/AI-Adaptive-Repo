/**
 * Study > Participants  (route: /study/participants)
 *
 * Extracted from the participants card of the legacy Admin Reports screen
 * (admin/legacy/reports.js: renderReports, loadParticipants, enrollParticipants,
 * createStudyAccounts, wipeParticipantKey). Same endpoints, same request bodies, same
 * server-side validation and authorization (every endpoint checks the admin session):
 *
 *   GET    /api/admin/participants                  list: { participants: [{ code, email, consented }] }
 *   POST   /api/admin/participants                  enrol: { emails } -> { assigned, skipped }
 *   POST   /api/admin/participants/create-accounts  { count } -> { accounts: [{ code, email, password }] }
 *   GET    /api/admin/participants/mapping.csv      identity key download (plain link)
 *   DELETE /api/admin/participants?confirm=DELETE   destroys the identity key
 *   POST   /api/admin/participants/:code/reset-consent   clears one participant's consent -> { deleted }
 *   POST   /api/admin/participants/reset-consent         { confirm: "RESET" } clears everyone's consent
 *
 * Contract: default export { mount(el, ctx), unmount(ctx) }. mount() renders immediately and loads
 * the list in the background (loading / error-with-retry / empty states are inline). unmount() removes
 * every listener and aborts the in-flight list request. Write requests are never aborted.
 *
 * The root element keeps the legacy id #participantsCard so the shell's focus target and the print
 * stylesheet (which hides it) keep working. Participant e-mail addresses are deliberately NOT shown in
 * the list - the legacy card showed codes and consent only, keeping identity out of everyday view.
 */
import {
  h, createLifecycle, apiCall, errorMessage, isAbortError, setStatus, loadingBlock, errorBlock,
  downloadTextFile, refreshActivityLogQuietly, requireUi
} from "./study-common.js";

const ENDPOINT = "/api/admin/participants";
const ENROL_SPLIT = /[\s,;]+/;            // identical to the legacy parser
const MAX_ACCOUNTS = 50;                  // server enforces 1-50; checked here only for a faster message

let active = null;                        // { life, root }

function teardown() {
  if (!active) return;
  const { life, root, onDispose } = active;
  active = null;
  life.dispose();
  if (onDispose) onDispose();
  root.remove();
}

function card(title, hint) {
  const section = h("section", { class: "admin-card", style: "margin-bottom:14px;" },
    h("div", { class: "admin-card-title" }, title));
  if (hint) section.append(h("div", { class: "admin-card-hint" }, hint));
  return section;
}

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Study participants view requires a mount element");
    teardown();                            // a repeated mount must never stack a second set of handlers
    const ui = requireUi(ctx, "Study participants");
    const life = createLifecycle();

    // ── enrol card ───────────────────────────────────────────────────────────────────────────
    const emailsInput = h("textarea", {
      id: "enrollEmails", rows: 3, placeholder: "One email per line", "aria-label": "Emails to enrol",
      style: "width:100%;padding:9px;border:1px solid #e2e8f0;border-radius:8px;font-size:.85rem;box-sizing:border-box;"
    });
    const enrolButton = h("button", { type: "button", class: "print-btn" }, "➕ Enrol");
    const enrolStatus = h("div", { id: "enrollResult", hidden: true });
    const enrolCard = card("🔑 Study Participants",
      "Exports include only enrolled students, identified by code. Enrol after the students have registered.");
    enrolCard.append(emailsInput, h("div", { style: "display:flex;gap:10px;flex-wrap:wrap;margin-top:8px;" }, enrolButton), enrolStatus);

    // ── create accounts card ─────────────────────────────────────────────────────────────────
    const countInput = h("input", {
      id: "createAccountsCount", type: "number", min: 1, max: MAX_ACCOUNTS, step: 1, value: "10",
      "aria-label": "Number of study accounts", style: "width:90px;padding:8px;border:1px solid #cbd5e1;border-radius:8px;"
    });
    const createButton = h("button", { type: "button", class: "print-btn" }, "🆕 Create accounts");
    const redownloadButton = h("button", { type: "button", class: "print-btn", style: "background:#059669;", hidden: true }, "⬇ Download credentials again");
    const createStatus = h("div", { hidden: true });
    const createCard = card("🆕 Create study accounts",
      "Creates new study accounts and enrols them. Passwords are shown once and cannot be recovered: the credentials CSV downloads automatically, so store it securely.");
    createCard.append(
      h("div", { style: "display:flex;gap:10px;flex-wrap:wrap;align-items:center;" },
        h("label", { for: "createAccountsCount", style: "font-size:.8rem;font-weight:700;color:#374151;" }, `How many accounts (1-${MAX_ACCOUNTS})?`),
        countInput, createButton, redownloadButton),
      createStatus);

    // ── enrolled list card ───────────────────────────────────────────────────────────────────
    const summary = h("div", { style: "font-size:.82rem;color:#374151;margin-bottom:8px;", "aria-live": "polite" });
    const listBox = h("div", { id: "participantList", style: "font-size:.8rem;" });
    const reloadButton = h("button", { type: "button", class: "action-btn btn-view" }, "↻ Refresh");
    const resetAllButton = h("button", { type: "button", class: "action-btn btn-block" }, "↺ Reset all consent");
    const resetStatus = h("div", { hidden: true, role: "status" });
    const listCard = card("👥 Enrolled participants",
      "Consent shows whether the participant has accepted the current consent version. Resetting consent makes the participant accept the notice again before using AI features, quizzes or the SUS survey; their data is not touched.");
    listCard.append(h("div", { style: "display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;" }, summary,
      h("div", { style: "display:flex;gap:8px;flex-wrap:wrap;" }, resetAllButton, reloadButton)), resetStatus, listBox);

    // ── identity key card ────────────────────────────────────────────────────────────────────
    const keyLink = h("a", { class: "print-btn", style: "background:#059669;text-decoration:none;", href: ENDPOINT + "/mapping.csv" }, "⬇ Identity key CSV");
    const wipeButton = h("button", { type: "button", class: "print-btn", style: "background:#ef4444;" }, "🗑 Delete identity key");
    const keyStatus = h("div", { hidden: true });
    const keyCard = card("🔐 Identity key",
      "The key links participant codes to email addresses. Store it offline, separate from the datasets. Download it before deleting it.");
    keyCard.append(h("div", { style: "display:flex;gap:10px;flex-wrap:wrap;" }, keyLink, wipeButton), keyStatus);

    const root = h("div", { id: "participantsCard" }, enrolCard, createCard, listCard, keyCard);
    el.replaceChildren(root);

    const state = { loadSeq: 0, listAbort: null, table: null, credentialsCsv: null, busy: new Set() };
    active = {
      life, root,
      onDispose() {                        // never keep generated passwords around after leaving the page
        state.credentialsCsv = null;
        if (state.listAbort) state.listAbort.abort();
        if (state.table) state.table.destroy();
      }
    };

    // ── list loading ─────────────────────────────────────────────────────────────────────────
    async function loadList() {
      const seq = ++state.loadSeq;
      if (state.listAbort) state.listAbort.abort();
      const controller = new AbortController();
      state.listAbort = controller;
      if (state.table) { state.table.destroy(); state.table = null; }
      summary.textContent = "";
      listBox.replaceChildren(loadingBlock("Loading participants…"));
      try {
        const response = await apiCall(ctx, ENDPOINT, { signal: controller.signal });
        if (!life.alive || seq !== state.loadSeq) return;
        if (!response || !Array.isArray(response.participants)) throw new Error("Unexpected response from the server.");
        const rows = response.participants;
        if (!rows.length) {
          summary.textContent = "0 enrolled";
          listBox.replaceChildren(ui.empty({ title: "No participants enrolled yet." }));
          return;
        }
        const consented = rows.filter(p => p.consented).length;
        summary.textContent = `${rows.length} enrolled · ${consented} consented`;
        const table = ui.table({
          columns: [
            { key: "code", label: "Participant code" },
            { key: "consented", label: "Consent (current version)", render: p => h("span", { class: p.consented ? "badge-active" : "badge-flagged" }, p.consented ? "✓ Consented" : "✗ Not yet") },
            { key: "actions", label: "Actions", render: p => {
              if (!p.consented) return h("span", { style: "color:#94a3b8;" }, "—");
              const button = h("button", { type: "button", class: "action-btn btn-block", "aria-label": `Reset consent for ${p.code}` }, "↺ Reset consent");
              button.addEventListener("click", () => resetConsent(p.code, button));
              return button;
            } }
          ],
          fetch: async () => rows,
          rowKey: "code",
          label: "Enrolled study participants"
        });
        await table.reload();
        if (!life.alive || seq !== state.loadSeq) { table.destroy(); return; }
        state.table = table;
        listBox.replaceChildren(table.element);
      } catch (error) {
        if (isAbortError(error) || !life.alive || seq !== state.loadSeq) return;
        summary.textContent = "";
        listBox.replaceChildren(errorBlock(life, "Could not load participants: " + errorMessage(error), loadList));
      }
    }

    // Shared guard: one in-flight write per action; the button is disabled meanwhile.
    async function guarded(key, button, task) {
      if (state.busy.has(key)) return;
      state.busy.add(key);
      button.disabled = true;
      try { await task(); }
      finally { state.busy.delete(key); if (life.alive) button.disabled = false; }
    }

    // ── enrol ────────────────────────────────────────────────────────────────────────────────
    life.on(enrolButton, "click", () => guarded("enrol", enrolButton, async () => {
      const emails = emailsInput.value.split(ENROL_SPLIT).filter(Boolean);
      if (!emails.length) { setStatus(enrolStatus, "error", "Enter at least one email address (one per line)."); emailsInput.focus(); return; }
      try {
        const response = await apiCall(ctx, ENDPOINT, { method: "POST", body: JSON.stringify({ emails }) });
        const skipped = Array.isArray(response && response.skipped) ? response.skipped : [];
        const message = "Enrolled " + (response && response.assigned) + "." + (skipped.length ? " Skipped: " + skipped.join("; ") : "");
        if (life.alive) {
          setStatus(enrolStatus, response && response.assigned > 0 ? "success" : "warning", message);
          emailsInput.value = "";
          loadList();
        } else ui.toast(message, { type: "info" });
      } catch (error) {
        if (life.alive) setStatus(enrolStatus, "error", errorMessage(error, "Failed."));
        else ui.toast(errorMessage(error, "Enrolment failed."), { type: "error" });
      }
      refreshActivityLogQuietly();
    }));

    // ── create accounts ──────────────────────────────────────────────────────────────────────
    function offerCredentials(csv) {
      state.credentialsCsv = csv;
      try { downloadTextFile("study_accounts.csv", csv, "text/csv"); } catch (error) { console.warn("[study participants] automatic download failed", error); }
    }

    life.on(redownloadButton, "click", () => {
      if (!state.credentialsCsv) return;
      try { downloadTextFile("study_accounts.csv", state.credentialsCsv, "text/csv"); }
      catch (error) { ui.toast("Download failed: " + errorMessage(error), { type: "error" }); }
    });

    life.on(createButton, "click", () => guarded("create", createButton, async () => {
      const count = Number(countInput.value);
      if (!Number.isInteger(count) || count < 1 || count > MAX_ACCOUNTS) {
        setStatus(createStatus, "error", `Enter a whole number from 1 to ${MAX_ACCOUNTS}.`);
        countInput.focus();
        return;
      }
      setStatus(createStatus, "info", "Creating accounts…");
      redownloadButton.hidden = true;
      state.credentialsCsv = null;
      try {
        const response = await apiCall(ctx, ENDPOINT + "/create-accounts", { method: "POST", body: JSON.stringify({ count }) });
        const accounts = Array.isArray(response && response.accounts) ? response.accounts : [];
        if (!accounts.length) {
          if (life.alive) setStatus(createStatus, "warning", "The server did not create any accounts.");
          else ui.toast("The server did not create any accounts.", { type: "info" });
        } else {
          // Identical CSV to the legacy screen. Passwords are returned once, so the file is generated even
          // when the admin has already navigated away.
          const csv = "participant_code,email,password\r\n" + accounts.map(a => [a.code, a.email, a.password].join(",")).join("\r\n");
          if (life.alive) {
            offerCredentials(csv);
            redownloadButton.hidden = false;
            setStatus(createStatus, "success", `Created ${accounts.length} study account(s). study_accounts.csv was downloaded. It contains the passwords, which cannot be recovered later - store it securely. If the download did not start, use “Download credentials again” before leaving this page.`);
            loadList();
          } else {
            try { downloadTextFile("study_accounts.csv", csv, "text/csv"); } catch { /* nothing more can be done */ }
            ui.toast(`Created ${accounts.length} study account(s); study_accounts.csv was downloaded.`, { type: "success" });
          }
        }
      } catch (error) {
        if (life.alive) setStatus(createStatus, "error", errorMessage(error, "Failed."));
        else ui.toast(errorMessage(error, "Creating accounts failed."), { type: "error" });
      }
      refreshActivityLogQuietly();
    }));

    // ── delete identity key ──────────────────────────────────────────────────────────────────
    life.on(wipeButton, "click", () => guarded("wipe", wipeButton, async () => {
      const { confirmed } = await ui.confirm({
        tier: "destructive",
        title: "Delete the identity key?",
        message: "Permanently delete the code-to-identity key? Download the Identity key CSV first if you need it.",
        impact: "Every participant code and its link to an email address is removed. Exports identify participants by these codes. This cannot be undone.",
        typeToConfirm: "DELETE"
      });
      if (!confirmed || !life.alive) return;
      try {
        const response = await apiCall(ctx, ENDPOINT + "?confirm=DELETE", { method: "DELETE" });
        const deleted = response && typeof response.deleted === "number" ? ` (${response.deleted} row(s))` : "";
        if (life.alive) { setStatus(keyStatus, "success", "Identity key deleted" + deleted + "."); loadList(); }
        else ui.toast("Identity key deleted" + deleted + ".", { type: "success" });
      } catch (error) {
        if (life.alive) setStatus(keyStatus, "error", errorMessage(error, "Failed."));
        else ui.toast(errorMessage(error, "Deleting the identity key failed."), { type: "error" });
      }
      refreshActivityLogQuietly();
    }));

    // ── reset consent ────────────────────────────────────────────────────────────────────────
    async function resetConsent(code, button) {
      await guarded("reset:" + code, button, async () => {
        const { confirmed } = await ui.confirm({
          tier: "standard",
          title: `Reset consent for ${code}?`,
          message: `${code} will be asked to accept the consent notice again.`,
          impact: "Until they accept, this participant is blocked from quizzes, AI features, uploads and the SUS survey. The reset is recorded in the activity log."
        });
        if (!confirmed || !life.alive) return;
        try {
          await apiCall(ctx, `${ENDPOINT}/${encodeURIComponent(code)}/reset-consent`, { method: "POST", body: JSON.stringify({}) });
          if (life.alive) { setStatus(resetStatus, "success", `Consent reset for ${code}.`); loadList(); }
          else ui.toast(`Consent reset for ${code}.`, { type: "success" });
        } catch (error) {
          if (life.alive) setStatus(resetStatus, "error", errorMessage(error, "Failed."));
          else ui.toast(errorMessage(error, "Resetting consent failed."), { type: "error" });
        }
        refreshActivityLogQuietly();
      });
    }

    life.on(resetAllButton, "click", () => guarded("reset-all", resetAllButton, async () => {
      const { confirmed } = await ui.confirm({
        tier: "destructive",
        title: "Reset consent for every participant?",
        message: "All enrolled participants will be asked to accept the consent notice again.",
        impact: "Every participant, including those mid-session, is blocked from quizzes, AI features, uploads and the SUS survey until they accept again. Use this only when the notice itself has changed and the version constant was not bumped.",
        typeToConfirm: "RESET"
      });
      if (!confirmed || !life.alive) return;
      try {
        const response = await apiCall(ctx, ENDPOINT + "/reset-consent", { method: "POST", body: JSON.stringify({ confirm: "RESET" }) });
        const n = response && typeof response.deleted === "number" ? ` (${response.deleted} record(s))` : "";
        if (life.alive) { setStatus(resetStatus, "success", "Consent reset for all participants" + n + "."); loadList(); }
        else ui.toast("Consent reset for all participants" + n + ".", { type: "success" });
      } catch (error) {
        if (life.alive) setStatus(resetStatus, "error", errorMessage(error, "Failed."));
        else ui.toast(errorMessage(error, "Resetting consent failed."), { type: "error" });
      }
      refreshActivityLogQuietly();
    }));

    life.on(reloadButton, "click", loadList);

    loadList();                            // background load; the view is already on screen
  },

  unmount() {
    teardown();
  }
};
