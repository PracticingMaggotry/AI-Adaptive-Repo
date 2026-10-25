/**
 * System > Configuration  (route: /system/configuration)
 *
 * Replaces the "not implemented" placeholder. Reads and edits the existing backend configuration API only:
 *   GET /api/admin/configuration              stored rows
 *   GET /api/admin/configuration/current/all  effective snapshot (reloaded after every save)
 *   GET /api/admin/configuration/{key}        pre-save freshness check
 *   PUT /api/admin/configuration/{key}        { configValue, dataType } - never description, never create/delete
 *
 * Safety rules enforced here (rules and bounds live in configuration-model.js with their source):
 *  - Only keys in the model registry are editable; every other row is listed read-only.
 *  - Input is validated against the setting's real data type and semantics before anything is sent.
 *  - Every change goes through ctx.ui.confirm with the affected behaviour.
 *  - Before PUT the stored row is re-read; if it changed since this page loaded the save is refused.
 *  - A saved value is only shown as saved after the page reloads it from the backend. A failed save keeps the
 *    draft visible and labelled "not saved"; the displayed persisted value never changes on failure.
 *
 * Contract: default export { mount(el, ctx), unmount(ctx) }. Listeners go through a lifecycle; GETs abort on
 * unmount; a PUT in flight is never aborted (the view just stops touching the DOM).
 */
import {
  h, createLifecycle, apiCall, errorMessage, isAbortError, setStatus, loadingBlock, errorBlock
} from "./study-common.js";
import {
  buildModel, groupModel, validateInput, validatePair, warningsFor, buildUpdateRequest, getSetting, sameValue, TYPES
} from "./configuration-model.js";

const ROWS_PATH = "/api/admin/configuration";
const SNAPSHOT_PATH = "/api/admin/configuration/current/all";
const rowPath = (key) => ROWS_PATH + "/" + encodeURIComponent(key);

let active = null; // { life, root, onDispose }

function teardown() {
  if (!active) return;
  const { life, root, onDispose } = active;
  active = null;
  life.dispose();
  if (onDispose) onDispose();
  root.remove();
}

function requireConfirmUi(ctx) {
  const ui = ctx && ctx.ui;
  if (!ui || typeof ui.confirm !== "function" || typeof ui.toast !== "function" || typeof ui.empty !== "function") {
    throw new Error("Configuration needs ctx.ui (admin/ui.js) with confirm, toast and empty.");
  }
  return ui;
}

const pill = (text, style) => h("span", { style: "display:inline-block;padding:1px 8px;border-radius:999px;font-size:.72rem;font-weight:600;" + style }, text);
const GREY = "background:#f3f4f6;color:#374151;";
const AMBER = "background:#fffbeb;color:#92400e;";

export default {
  async mount(el, ctx) {
    if (!el) throw new TypeError("Configuration view requires a mount element");
    teardown();
    const ui = requireConfirmUi(ctx);
    const life = createLifecycle();

    const banner = h("div", { id: "configBanner", "aria-live": "polite", hidden: true });
    const body = h("div", { id: "configBody" });
    const root = h("div", { "data-view": "configuration" },
      h("section", { class: "admin-card", style: "margin-bottom:14px;" },
        h("div", { class: "admin-card-title" }, "⚙️ System configuration"),
        h("div", { class: "admin-card-hint" },
          "Platform settings stored in the database. Changes apply to new requests straight away (the backend reads each value when it is used). ",
          "Edits made on this page are not written to the Activity Log, because the underlying endpoint does not record them. ",
          "Per-day student limits can also be managed from ", h("a", { href: "#/system/usage-limits" }, "Usage limits"), "."),
        banner),
      body);
    el.replaceChildren(root);

    const state = {
      seq: 0, abort: null,
      model: null,           // last model built from backend data
      stale: false,          // true when a post-save reload failed: displayed values may be outdated
      editing: null,         // key being edited
      drafts: new Map(),     // key -> draft text (unsaved)
      fieldMessage: new Map(), // key -> { type, text }
      saving: null           // key being saved
    };
    active = { life, root, onDispose() { if (state.abort) state.abort.abort(); } };

    // ── data loading ─────────────────────────────────────────────────────────────────────────────
    async function fetchBoth(signal) {
      const [rows, snap] = await Promise.allSettled([apiCall(ctx, ROWS_PATH, { signal }), apiCall(ctx, SNAPSHOT_PATH, { signal })]);
      return { rows, snap };
    }

    function valid(settled, test) { return settled.status === "fulfilled" && test(settled.value); }

    /** Loads rows + snapshot. Returns { ok, rowsError, snapError }. Renders unless `silent`. */
    async function load({ silent = false } = {}) {
      const seq = ++state.seq;
      if (state.abort) state.abort.abort();
      const controller = new AbortController();
      state.abort = controller;
      if (!silent) { setBanner(null); body.replaceChildren(loadingBlock("Loading configuration…")); }
      const { rows, snap } = await fetchBoth(controller.signal);
      if (!life.alive || seq !== state.seq) return { ok: false, superseded: true };
      for (const settled of [rows, snap]) if (settled.status === "rejected" && isAbortError(settled.reason)) return { ok: false, superseded: true };

      const rowsOk = valid(rows, Array.isArray);
      const snapOk = valid(snap, (v) => v && typeof v === "object" && !Array.isArray(v));
      if (!rowsOk) {
        const reason = rows.status === "rejected" ? errorMessage(rows.reason) : "Unexpected response from the server.";
        if (!silent || !state.model) {
          body.replaceChildren(errorBlock(life, "Could not load the configuration: " + reason, () => load()));
        }
        return { ok: false, rowsError: reason };
      }
      state.model = buildModel(rows.value, snapOk ? snap.value : null);
      state.stale = false;
      render();
      if (!snapOk) {
        const reason = snap.status === "rejected" ? errorMessage(snap.reason) : "Unexpected response from the server.";
        const retry = h("button", { type: "button", class: "action-btn btn-view", style: "margin-left:10px;" }, "Retry");
        life.on(retry, "click", () => load());
        setBanner("warning", "The effective-value snapshot could not be loaded (" + reason + "). Stored values are shown, but editing is disabled until it loads.", retry);
        return { ok: false, snapError: reason };
      }
      setBanner(null);
      return { ok: true };
    }

    function setBanner(type, message, extra) {
      setStatus(banner, type || "info", message || "");
      if (message && extra) banner.append(extra);
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────────
    function canEditNow() { return Boolean(state.model && state.model.snapshotLoaded && !state.saving); }

    function render() {
      const model = state.model;
      if (!model) return;
      if (!model.rowsLoaded || (!model.supported.some((s) => s.row) && !model.unknown.length)) {
        body.replaceChildren(ui.empty({ title: "No configuration records were found." }));
        return;
      }
      const groups = groupModel(model);
      body.replaceChildren(...groups.map(renderGroup));
      if (state.editing) {
        const row = [...body.querySelectorAll("[data-key]")].find((node) => node.getAttribute("data-key") === state.editing);
        const input = row && row.querySelector('[data-role="input"]');
        if (input && state.focusEditor) { state.focusEditor = false; input.focus(); }
      }
    }

    function renderGroup(group) {
      return h("section", { class: "admin-card", "data-group": group.id, style: "margin-bottom:14px;" },
        h("div", { class: "admin-card-title" }, group.title),
        h("div", { class: "admin-card-hint" }, group.hint),
        group.id === "other" ? group.unknown.map(renderUnknown) : group.items.map(renderSetting));
    }

    function renderUnknown(rec) {
      return h("div", { "data-key": rec.key, "data-readonly": "unknown", style: "padding:10px 0;border-top:1px solid #e5e7eb;" },
        h("div", { style: "display:flex;gap:8px;align-items:center;flex-wrap:wrap;" },
          h("code", { style: "font-weight:600;overflow-wrap:anywhere;" }, rec.key),
          pill("Read-only", GREY), rec.dataType ? pill(rec.dataType, GREY) : null),
        h("div", { style: "margin-top:4px;overflow-wrap:anywhere;" }, "Value: ", h("code", {}, rec.value === "" ? "(empty)" : rec.value)),
        rec.description ? h("div", { style: "font-size:.8rem;color:#6b7280;margin-top:2px;overflow-wrap:anywhere;" }, rec.description) : null);
    }

    function displayValue(entry) {
      if (entry.hasSnapshot) return String(entry.effective);
      if (entry.row && entry.interp) return entry.interp.effective;
      return String(entry.setting.default);
    }

    function renderSetting(entry) {
      const { setting } = entry;
      const key = setting.key;
      const editing = state.editing === key;
      const message = state.fieldMessage.get(key);
      const lines = [];
      lines.push(h("div", { style: "display:flex;gap:8px;align-items:center;flex-wrap:wrap;" },
        h("strong", {}, setting.label),
        h("code", { style: "font-size:.75rem;color:#6b7280;overflow-wrap:anywhere;" }, key),
        !entry.editable ? pill("Read-only", GREY) : null,
        state.stale ? pill("May be outdated", AMBER) : null));

      const value = displayValue(entry);
      lines.push(h("div", { style: "margin-top:4px;" },
        entry.hasSnapshot || entry.row ? "Current: " : "Default (no stored row): ", h("code", { "data-role": "value" }, value),
        entry.row && entry.stored != null && !sameValue(entry.stored, value) ? h("span", { style: "color:#6b7280;font-size:.8rem;" }, "  (stored text: ", h("code", {}, entry.stored), ")") : null,
        entry.hasSnapshot || entry.row ? h("span", { style: "color:#6b7280;font-size:.8rem;" }, "  · default " + String(setting.default)) : null));

      for (const note of entry.notes) lines.push(h("div", { style: "font-size:.8rem;color:#92400e;margin-top:2px;" }, note));
      if (!entry.editable && entry.blockedReason) lines.push(h("div", { style: "font-size:.8rem;color:#6b7280;margin-top:2px;" }, entry.blockedReason));
      if (setting.rules && entry.editable) lines.push(h("div", { style: "font-size:.8rem;color:#6b7280;margin-top:2px;" }, "Accepts " + setting.rules.text + "."));
      if (setting.limitation) lines.push(h("div", { style: "font-size:.78rem;color:#6b7280;margin-top:2px;" }, "Limitation: " + setting.limitation));
      if (entry.row && entry.row.description) lines.push(h("div", { style: "font-size:.78rem;color:#9ca3af;margin-top:2px;overflow-wrap:anywhere;" }, entry.row.description));

      if (editing) lines.push(renderEditor(entry));
      else if (entry.editable) {
        const edit = h("button", { type: "button", class: "action-btn btn-view", style: "margin-top:8px;", "data-action": "edit", disabled: !canEditNow() }, "Edit");
        life.on(edit, "click", () => beginEdit(key));
        lines.push(edit);
      }

      const status = h("div", { "data-role": "message", hidden: true });
      if (message) setStatus(status, message.type, message.text);
      lines.push(status);
      return h("div", { "data-key": key, style: "padding:10px 0;border-top:1px solid #e5e7eb;" }, lines);
    }

    function renderEditor(entry) {
      const { setting } = entry;
      const key = setting.key;
      const draft = state.drafts.has(key) ? state.drafts.get(key) : currentText(entry);
      const id = "cfg-" + key.toLowerCase();
      const saving = state.saving === key;
      let input;
      if (setting.type === TYPES.BOOLEAN) {
        input = h("select", { id, "data-role": "input", disabled: saving },
          ["true", "false"].map((v) => h("option", { value: v, selected: v === draft }, v)));
        input.value = draft === "false" ? "false" : "true";
      } else {
        input = h("input", {
          id, type: "text", "data-role": "input", value: draft, autocomplete: "off", spellcheck: "false",
          inputmode: setting.type === TYPES.STRING ? "text" : "numeric", disabled: saving, style: "min-width:260px;max-width:100%;"
        });
      }
      life.on(input, "input", () => { state.drafts.set(key, input.value); });
      const save = h("button", { type: "button", class: "action-btn btn-view", "data-action": "save", disabled: saving }, saving ? "Saving…" : "Save");
      const cancel = h("button", { type: "button", class: "action-btn", "data-action": "cancel", disabled: saving }, "Cancel");
      life.on(save, "click", () => saveSetting(key));
      life.on(cancel, "click", () => cancelEdit(key));
      life.on(input, "keydown", (event) => { if (event.key === "Enter" && setting.type !== TYPES.BOOLEAN) { event.preventDefault(); saveSetting(key); } });
      const unsaved = state.drafts.has(key) && !sameValue(state.drafts.get(key), currentText(entry));
      return h("div", { style: "margin-top:8px;display:flex;gap:8px;align-items:center;flex-wrap:wrap;" },
        h("label", { for: id, style: "font-size:.8rem;color:#374151;" }, "New value"), input, save, cancel,
        unsaved ? pill("Not saved", AMBER) : null);
    }

    /** Text shown in an editor when the admin has typed nothing: the stored text if parsable, else the effective value. */
    function currentText(entry) {
      if (entry.row && entry.stored != null && entry.interp && entry.interp.parsable) {
        return entry.setting.type === TYPES.BOOLEAN ? entry.interp.effective : entry.stored;
      }
      return displayValue(entry);
    }

    // ── editing ──────────────────────────────────────────────────────────────────────────────────
    function entryFor(key) {
      return state.model && state.model.supported.find((e) => e.setting.key === key);
    }

    function beginEdit(key) {
      if (!canEditNow()) return;
      const entry = entryFor(key);
      if (!entry || !entry.editable) return;
      state.editing = key;
      state.drafts.delete(key);
      state.fieldMessage.delete(key);
      state.focusEditor = true;
      render();
    }

    function cancelEdit(key) {
      if (state.saving) return;
      if (state.editing === key) state.editing = null;
      state.drafts.delete(key);
      state.fieldMessage.delete(key);
      render();
    }

    function say(key, type, text) {
      if (text) state.fieldMessage.set(key, { type, text }); else state.fieldMessage.delete(key);
    }

    async function saveSetting(key) {
      if (state.saving) return;
      const entry = entryFor(key);
      const setting = getSetting(key);
      if (!entry || !setting || !entry.editable) return;

      const draft = state.drafts.has(key) ? state.drafts.get(key) : currentText(entry);
      const checked = validateInput(key, draft);
      if (!checked.ok) { say(key, "error", checked.message); render(); return; }

      const before = entry.stored;
      if (entry.interp && entry.interp.parsable && entry.interp.canonical !== false && sameValue(before, checked.value)) {
        say(key, "info", "No change: this is already the stored value.");
        render();
        return;
      }

      // Pair rule uses the counterpart as last loaded for the early message; it is re-checked with fresh data below.
      const pairKey = setting.rules && setting.rules.pair && setting.rules.pair.key;
      let pairEntry = pairKey ? entryFor(pairKey) : null;
      if (pairKey) {
        const early = validatePair(key, checked.value, pairEntry && pairEntry.stored);
        if (!early.ok) { say(key, "error", early.message); render(); return; }
      }

      const warnings = warningsFor(key, checked.value);
      const pairNote = pairKey ? validatePair(key, checked.value, pairEntry && pairEntry.stored).warning : null;
      if (pairNote) warnings.push(pairNote);
      const message = `Change ${setting.label} (${key}) from ${before == null ? "(empty)" : before} to ${checked.value}?`
        + (warnings.length ? " Note: " + warnings.join(" ") : "");
      const answer = await ui.confirm({ tier: "standard", title: "Change a system setting", message, impact: setting.impact || undefined });
      if (!life.alive) return;
      if (!answer || !answer.confirmed) { say(key, "info", "Cancelled. Nothing was changed."); render(); return; }

      state.saving = key;
      say(key, "info", "Checking the current stored value…");
      render();

      let wrote = false;      // true once the PUT has been sent
      let putError = null;    // set when the PUT failed in a way that does not prove it was not applied
      try {
        // 1) Freshness check: refuse if the row changed since this page loaded.
        const fresh = await apiCall(ctx, rowPath(key));
        if (!fresh || typeof fresh !== "object" || fresh.configKey !== key) throw new Error("Unexpected response while checking the current value.");
        if (!sameValue(fresh.configValue, before) || fresh.dataType !== setting.type) {
          await finishSave(key, "error", `Not saved: this setting changed since the page loaded (now ${fresh.configValue == null ? "empty" : fresh.configValue}, type ${fresh.dataType}). The page was reloaded; review it and try again.`, { keepDraft: false, reload: true });
          return;
        }
        if (pairKey) {
          const other = await apiCall(ctx, rowPath(pairKey));
          const again = validatePair(key, checked.value, other && other.configValue);
          if (!again.ok) { await finishSave(key, "error", "Not saved: " + again.message, { keepDraft: true, reload: true }); return; }
        }
        if (!life.alive) { state.saving = null; return; }

        // 2) The write. Only configValue + dataType; the key stays in the path.
        say(key, "info", "Saving…");
        render();
        const request = buildUpdateRequest(key, checked.value);
        wrote = true;
        await apiCall(ctx, request.path, request.options);
      } catch (error) {
        if (!life.alive) { state.saving = null; return; }
        const status = error && error.status;
        const rejected = typeof status === "number" && status >= 400 && status < 500;
        if (!wrote || rejected) {
          const reason = status === 404 ? "The configuration row no longer exists (this page does not create rows)." : errorMessage(error);
          await finishSave(key, "error", "Not saved: " + reason + " The value shown above is still the last one loaded from the server.", { keepDraft: true, reload: false });
          return;
        }
        putError = error; // network failure or 5xx: the server may or may not have applied it, so verify by reloading
      }

      if (!life.alive) { state.saving = null; return; }
      // 3) Reload the effective state; only now is the value shown as persisted.
      state.saving = null;
      const result = await load({ silent: true });
      if (!life.alive) return;
      if (result.rowsError || result.snapError) {
        state.stale = true;
        state.editing = putError ? key : null;
        if (!putError) state.drafts.delete(key);
        say(key, "warning", putError
          ? `The save request failed (${errorMessage(putError)}) and the page could not re-read the settings (${result.rowsError || result.snapError}). It is unknown whether the change was applied. Reload the page to check.`
          : `Saved, but the page could not reload the settings (${result.rowsError || result.snapError}). Values shown may be outdated; use Retry or reload the page to confirm.`);
        if (state.model) render();
        ui.toast(`${setting.label}: result could not be confirmed.`, { type: "error" });
        return;
      }
      const after = entryFor(key);
      const confirmedValue = Boolean(after && after.row && sameValue(after.stored, checked.value));
      if (putError && !confirmedValue) {
        state.editing = key; // keep the draft, clearly marked as not saved
        say(key, "error", `Not saved: the server did not confirm the change (${errorMessage(putError)}). The value shown is what the server currently holds.`);
        render();
        return;
      }
      state.editing = null;
      state.drafts.delete(key);
      if (!confirmedValue) {
        say(key, "warning", `The server accepted the request, but the reloaded value is ${after && after.stored != null ? after.stored : "empty"}, not ${checked.value}. Check whether another admin changed it.`);
      } else {
        say(key, "success", (putError ? "The request reported an error, but the reloaded value shows the change was applied. " : "Saved. ") + `Now ${displayValue(after)}.`);
      }
      render();
      if (confirmedValue) ui.toast(`${setting.label} updated.`, { type: "success" });
    }

    async function finishSave(key, type, text, { keepDraft, reload }) {
      state.saving = null;
      say(key, type, text);
      if (!keepDraft) { state.editing = null; state.drafts.delete(key); }
      if (reload) {
        const result = await load({ silent: true });
        if (!life.alive) return;
        if (result.rowsError || result.snapError) say(key, "error", text + " The page could not be reloaded: " + (result.rowsError || result.snapError));
      }
      if (life.alive && state.model) render();
    }

    await load();
  },

  unmount() {
    teardown();
  }
};
