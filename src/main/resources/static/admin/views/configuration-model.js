/**
 * System > Configuration: pure model (no DOM, import-safe in Node).
 *
 * Every rule below is traced to backend source. Nothing here invents a bound; where the source gives no safe
 * constraint the key is validated for TYPE only and the limitation is stated in `limitation`.
 *
 * Backend facts this relies on (SystemConfigurationController / ConfigurationService / SystemConfiguration):
 *  - GET /api/admin/configuration            -> raw rows [{ id, configKey, configValue, description, dataType }]
 *  - GET /api/admin/configuration/current/all-> effective snapshot (12 keys, camelCase, typed). The snapshot
 *                                               applies code defaults, so it differs from the raw row when a row is
 *                                               missing or unparsable.
 *  - GET /api/admin/configuration/{key}      -> one row, 404 (empty body) if absent
 *  - PUT /api/admin/configuration/{key}      -> merges non-null configValue / description / dataType into the row;
 *                                               404 if the row is absent (it never creates). This view sends only
 *                                               { configValue, dataType }, so description is never touched.
 *  - Values are stored as TEXT. Integer/long parse with Integer.parseInt / Long.parseLong (a bad value silently
 *    falls back to the code default), boolean with Boolean.parseBoolean (anything except "true" is false), strings
 *    are used as-is (blank falls back to the default).
 */

export const TYPES = Object.freeze({ INTEGER: "INTEGER", LONG: "LONG", BOOLEAN: "BOOLEAN", STRING: "STRING", DOUBLE: "DOUBLE" });

export const INT_MAX = 2147483647;
export const LONG_MAX = 9223372036854775807n;
/** application.properties: spring.servlet.multipart.max-file-size / max-request-size = 15MB. */
export const SERVLET_UPLOAD_CEILING_BYTES = 15 * 1024 * 1024;
/** PasswordPolicy.MAX_LENGTH: longer passwords are rejected outright. */
export const PASSWORD_MAX_LENGTH = 128;

export const GROUPS = Object.freeze([
  { id: "daily", title: "Daily limits", hint: "Per-student, per-day caps. A personal override set in Users takes precedence over these platform-wide values." },
  { id: "uploads", title: "Materials and uploads", hint: "Limits and allow-lists applied when a material is uploaded or a handout is assigned." },
  { id: "difficulty", title: "Difficulty thresholds", hint: "Quiz score percentages that move a student between Easy, Medium and Hard." },
  { id: "extraction", title: "Text extraction and preview", hint: "Settings for text extracted from uploaded files." },
  { id: "password", title: "Password policy", hint: "Rules checked when a password is set at registration." },
  { id: "other", title: "Other records (read-only)", hint: "Rows this console does not recognise. They are shown for reference and cannot be changed here." }
]);

const rangeText = (min, max) => (max == null ? `a whole number of ${min} or more` : `a whole number from ${min} to ${max}`);

/**
 * Registry of supported keys.
 *  key, snapshot (camelCase name in /current/all, or null), group, label, type (expected dataType),
 *  default (ConfigurationService fallback), editable, readOnlyReason, consumers (where the value is read),
 *  validate (type + semantic rules), impact (text for the confirmation dialog), limitation.
 */
const DAILY_NOTE = "A value of 0 blocks the action for every student who has no personal override; a personal override from Users still applies.";
const DAILY_LIMITATION = "Seeded by migration V3 only for MAX_UPLOADS_PER_DAY. The other daily-limit rows are created by the backend the first time they are saved from Usage limits; until then this page cannot create them.";

function dailyLimit(key, snapshot, label, defaultValue, action) {
  return {
    key, snapshot, group: "daily", label, type: TYPES.INTEGER, default: defaultValue, editable: true,
    consumers: "ConfigurationService.getAllDailyActionLimits -> DailyActionLimiter",
    rules: { kind: "int", min: 0, max: INT_MAX, text: rangeText(0, INT_MAX) },
    impact: `Changes how many times per day each student can ${action}. ${DAILY_NOTE}`,
    limitation: key === "MAX_UPLOADS_PER_DAY" ? null : DAILY_LIMITATION
  };
}

export const SETTINGS = Object.freeze([
  dailyLimit("MAX_UPLOADS_PER_DAY", "maxUploadsPerDay", "Material uploads per day", 4, "upload materials"),
  dailyLimit("MAX_QUIZ_SUBMISSIONS_PER_DAY", null, "Quiz submissions per day", 40, "submit quizzes"),
  dailyLimit("MAX_ADAPTED_QUIZZES_PER_DAY", null, "Adapted quizzes per day", 6, "generate adapted quizzes"),
  dailyLimit("MAX_TARGETED_QUIZZES_PER_DAY", null, "Targeted quizzes per day", 15, "generate targeted quizzes"),
  dailyLimit("MAX_REPORTS_PER_DAY", null, "Question reports per day", 20, "report questions"),
  dailyLimit("MAX_LESSON_REGENERATIONS_PER_DAY", null, "Lesson regenerations per day", 10, "regenerate lessons"),

  {
    key: "MAX_UPLOAD_BYTES", snapshot: "maxUploadBytes", group: "uploads", label: "Maximum upload size (bytes)",
    type: TYPES.LONG, default: 10 * 1024 * 1024, editable: true,
    consumers: "MaterialController, AssignedHandoutController",
    rules: { kind: "long", min: 1, max: null, text: "a whole number of bytes, 1 or more" },
    impact: "Files larger than this are rejected at upload and when assigning a handout.",
    limitation: "The server also enforces spring.servlet.multipart.max-file-size=15MB (application.properties), so values above 15728640 bytes cannot take effect until that property is changed. The upload error text in MaterialController still says \"10 MB\" regardless of this value."
  },
  {
    key: "MAX_MIXED_QUESTIONS", snapshot: "maxMixedQuestions", group: "uploads", label: "Mixed questions per upload",
    type: TYPES.INTEGER, default: 30, editable: false,
    readOnlyReason: "No code outside ConfigurationService reads this value, so changing it has no verified effect.",
    consumers: "none found",
    rules: null, impact: null, limitation: null
  },
  {
    key: "MAX_TOPIC_LENGTH", snapshot: "maxTopicLength", group: "uploads", label: "Maximum topic name length (characters)",
    type: TYPES.INTEGER, default: 100, editable: true,
    consumers: "MaterialController, AssignedHandoutController",
    rules: { kind: "int", min: 1, max: INT_MAX, text: rangeText(1, INT_MAX) },
    impact: "Topic names longer than this are rejected at upload. A value of 0 would reject every topic, so 1 is the minimum accepted here.",
    limitation: "The Material.topic column length is not declared in the entity, so no upper bound beyond the integer range could be established."
  },
  {
    key: "ALLOWED_FILE_EXTENSIONS", snapshot: null, group: "uploads", label: "Allowed file extensions",
    type: TYPES.STRING, default: "pdf,txt,csv,doc,docx", editable: true,
    consumers: "MaterialController, AssignedHandoutController",
    rules: { kind: "extensions", allowed: ["pdf", "txt", "csv", "doc", "docx"], text: "a comma-separated list drawn from pdf, txt, csv, doc, docx" },
    impact: "Controls which file types students can upload and admins can assign. Removing a type blocks new uploads of that type immediately.",
    limitation: "Restricted to the five types DocumentTextExtractor can read. The backend compares the lower-cased file name with the stored text as written, so entries must be lower-case. Not included in the /current/all snapshot."
  },
  {
    key: "ALLOWED_CONTENT_TYPES", snapshot: null, group: "uploads", label: "Allowed content-type fragments",
    type: TYPES.STRING, default: "pdf,text,csv,msword,wordprocessingml,octet-stream", editable: true,
    consumers: "MaterialController (not used when assigning handouts)",
    rules: { kind: "fragments", known: ["pdf", "text", "csv", "msword", "wordprocessingml", "octet-stream"], text: "a comma-separated list of lower-case fragments of a MIME type" },
    impact: "A browser-declared content type must contain one of these fragments for an upload to be accepted. An empty entry would match every type, so empty entries are rejected.",
    limitation: "Fragments outside the seeded set are accepted when well-formed, but only the seeded vocabulary is verified against the extractor. Not included in the /current/all snapshot."
  },

  {
    key: "HARD_DIFFICULTY_THRESHOLD", snapshot: "hardDifficultyThreshold", group: "difficulty", label: "Hard threshold (score %)",
    type: TYPES.INTEGER, default: 80, editable: true,
    consumers: "QuizController, ClaudeService (DifficultyTier)",
    rules: { kind: "int", min: 0, max: 100, text: rangeText(0, 100), pair: { key: "MEDIUM_DIFFICULTY_THRESHOLD", relation: "gte" } },
    impact: "Scores at or above this percentage move a student up one difficulty tier. Applies to quizzes submitted after the change.",
    limitation: "Range 0-100 comes from the score being a percentage and the seeded description. It must not be lower than the Medium threshold."
  },
  {
    key: "MEDIUM_DIFFICULTY_THRESHOLD", snapshot: "mediumDifficultyThreshold", group: "difficulty", label: "Medium threshold (score %)",
    type: TYPES.INTEGER, default: 50, editable: true,
    consumers: "QuizController, ClaudeService (DifficultyTier)",
    rules: { kind: "int", min: 0, max: 100, text: rangeText(0, 100), pair: { key: "HARD_DIFFICULTY_THRESHOLD", relation: "lte" } },
    impact: "Scores below this percentage move a student down one tier and are flagged as weak. Applies to quizzes submitted after the change.",
    limitation: "Range 0-100 comes from the score being a percentage and the seeded description. It must not be higher than the Hard threshold."
  },

  {
    key: "MAX_PREVIEW_LENGTH", snapshot: "maxPreviewLength", group: "extraction", label: "Extracted text preview length (characters)",
    type: TYPES.INTEGER, default: 1800, editable: false,
    readOnlyReason: "No code outside ConfigurationService reads this value, so changing it has no verified effect.",
    consumers: "none found", rules: null, impact: null, limitation: null
  },
  {
    key: "MIN_EXTRACTED_TEXT_LENGTH", snapshot: "minExtractedTextLength", group: "extraction", label: "Minimum extracted text length (characters)",
    type: TYPES.INTEGER, default: 100, editable: false,
    readOnlyReason: "No code outside ConfigurationService reads this value, so changing it has no verified effect.",
    consumers: "none found", rules: null, impact: null, limitation: null
  },

  {
    key: "MIN_PASSWORD_LENGTH", snapshot: "minPasswordLength", group: "password", label: "Minimum password length",
    type: TYPES.INTEGER, default: 8, editable: true,
    consumers: "PasswordPolicy (AuthController registration)",
    rules: { kind: "int", min: 1, max: PASSWORD_MAX_LENGTH, text: rangeText(1, PASSWORD_MAX_LENGTH) },
    impact: "New passwords shorter than this are rejected. Existing passwords are not re-checked.",
    limitation: "The maximum of 128 is PasswordPolicy.MAX_LENGTH: a higher minimum would make every new password invalid."
  },
  boolSetting("REQUIRE_PASSWORD_UPPERCASE", "requirePasswordUppercase", "Require an uppercase letter", "an uppercase letter"),
  boolSetting("REQUIRE_PASSWORD_NUMBER", "requirePasswordNumber", "Require a number", "a digit"),
  boolSetting("REQUIRE_PASSWORD_SPECIAL_CHAR", "requirePasswordSpecialChar", "Require a special character", "a special character")
]);

function boolSetting(key, snapshot, label, what) {
  return {
    key, snapshot, group: "password", label, type: TYPES.BOOLEAN, default: true, editable: true,
    consumers: "PasswordPolicy (AuthController registration)",
    rules: { kind: "boolean", text: "true or false" },
    impact: `Controls whether new passwords must contain ${what}. Existing passwords are not re-checked. Lower-case letters are always required.`,
    limitation: null
  };
}

const BY_KEY = new Map(SETTINGS.map((s) => [s.key, s]));
export const SUPPORTED_KEYS = Object.freeze(SETTINGS.map((s) => s.key));
export const EDITABLE_KEYS = Object.freeze(SETTINGS.filter((s) => s.editable).map((s) => s.key));
export const getSetting = (key) => BY_KEY.get(key) || null;
export const isEditableKey = (key) => Boolean(BY_KEY.get(key) && BY_KEY.get(key).editable);

// ── Parsing / validation ──────────────────────────────────────────────────────────────────────────

/** Strict decimal integer text. Rejects "+5", "1e3", "5.0", " 5", "" and "-0". Returns BigInt or null. */
export function parseWholeNumber(text) {
  const raw = String(text ?? "");
  if (!/^(0|-?[1-9][0-9]*)$/.test(raw)) return null;
  return BigInt(raw);
}

/**
 * Validates `input` (what the admin typed / the checkbox value as "true"|"false") for one supported setting.
 * Returns { ok: true, value } with the exact text to store, or { ok: false, message }.
 */
export function validateInput(key, input) {
  const setting = getSetting(key);
  if (!setting) return { ok: false, message: "This setting is not supported for editing." };
  if (!setting.editable) return { ok: false, message: setting.readOnlyReason || "This setting is read-only." };
  const rules = setting.rules;
  const raw = typeof input === "string" ? input : String(input ?? "");

  if (rules.kind === "boolean") {
    if (raw === "true" || raw === "false") return { ok: true, value: raw };
    return { ok: false, message: "Choose true or false." };
  }

  if (rules.kind === "int" || rules.kind === "long") {
    const trimmed = raw.trim();
    if (trimmed === "") return { ok: false, message: "Enter a value." };
    const n = parseWholeNumber(trimmed);
    if (n === null) return { ok: false, message: "Enter a whole number using digits only (no sign, decimals, spaces or exponent)." };
    const max = rules.kind === "int" ? BigInt(INT_MAX) : LONG_MAX;
    if (n < BigInt(rules.min) || (rules.max != null && n > BigInt(rules.max)) || n > max) {
      return { ok: false, message: `Must be ${rules.text}.` };
    }
    return { ok: true, value: n.toString() };
  }

  if (rules.kind === "extensions") {
    const tokens = raw.split(",").map((t) => t.trim());
    if (tokens.some((t) => t === "")) return { ok: false, message: "Remove empty entries (check for a leading, trailing or doubled comma)." };
    const bad = tokens.filter((t) => !rules.allowed.includes(t));
    if (bad.length) return { ok: false, message: `Not supported: ${bad.join(", ")}. Use lower-case entries from ${rules.allowed.join(", ")}.` };
    const unique = [...new Set(tokens)];
    return { ok: true, value: unique.join(",") };
  }

  if (rules.kind === "fragments") {
    const tokens = raw.split(",").map((t) => t.trim());
    if (tokens.some((t) => t === "")) return { ok: false, message: "Remove empty entries: an empty fragment would match every content type." };
    const bad = tokens.filter((t) => !/^[a-z0-9][a-z0-9.+_-]*$/.test(t));
    if (bad.length) return { ok: false, message: `Invalid fragment: ${bad.join(", ")}. Use lower-case letters, digits and . + _ - only.` };
    const unique = [...new Set(tokens)];
    return { ok: true, value: unique.join(",") };
  }

  return { ok: false, message: "No validation rule is defined for this setting." };
}

/**
 * Cross-field check for the two difficulty thresholds. `otherValueText` is the other key's CURRENT stored text.
 * Returns { ok, message?, warning? }. An unparsable counterpart is reported rather than ignored.
 */
export function validatePair(key, newValueText, otherValueText) {
  const setting = getSetting(key);
  const pair = setting && setting.rules && setting.rules.pair;
  if (!pair) return { ok: true };
  const other = parseWholeNumber(String(otherValueText ?? "").trim());
  if (other === null) return { ok: false, message: `${pair.key} currently holds an unreadable value, so the two thresholds cannot be compared. Fix it first.` };
  const mine = BigInt(newValueText);
  if (pair.relation === "gte" && mine < other) return { ok: false, message: `Hard threshold (${mine}) cannot be lower than the Medium threshold (${other}).` };
  if (pair.relation === "lte" && mine > other) return { ok: false, message: `Medium threshold (${mine}) cannot be higher than the Hard threshold (${other}).` };
  if (mine === other) return { ok: true, warning: "Both thresholds are equal, so no score keeps the current tier: every score either moves a student up or down." };
  return { ok: true };
}

/** Non-blocking warnings shown in the confirmation dialog. */
export function warningsFor(key, valueText) {
  const out = [];
  const setting = getSetting(key);
  if (!setting) return out;
  if (setting.rules && setting.rules.kind === "int" && setting.group === "daily" && valueText === "0") {
    out.push("0 blocks this action for every student without a personal override.");
  }
  if (key === "MAX_UPLOAD_BYTES") {
    const n = parseWholeNumber(valueText);
    if (n !== null && n > BigInt(SERVLET_UPLOAD_CEILING_BYTES)) out.push("This is above the server's 15 MB multipart limit, which will still reject larger files.");
  }
  if (key === "MIN_PASSWORD_LENGTH") {
    const n = parseWholeNumber(valueText);
    if (n !== null && n < 8n) out.push("This is below the seeded default of 8.");
  }
  if (key === "ALLOWED_FILE_EXTENSIONS" && !valueText.split(",").includes("pdf") && !valueText.split(",").includes("docx")) {
    out.push("Neither pdf nor docx is allowed, which removes the most common handout formats.");
  }
  if (key === "ALLOWED_CONTENT_TYPES") {
    const setting2 = getSetting(key);
    const unknown = valueText.split(",").filter((t) => !setting2.rules.known.includes(t));
    if (unknown.length) out.push(`Not in the seeded vocabulary: ${unknown.join(", ")}.`);
  }
  return out;
}

// ── Reading rows / building the view model ────────────────────────────────────────────────────────

export function sameValue(a, b) {
  return String(a ?? "") === String(b ?? "");
}

/** What the backend will do with a stored row of this type (mirrors the SystemConfiguration getters). */
export function interpretStored(setting, rawText) {
  if (rawText == null) return { parsable: false, effective: String(setting.default), reason: "The stored value is empty; the backend uses its default." };
  const text = String(rawText);
  if (setting.type === TYPES.BOOLEAN) {
    // Boolean.parseBoolean: only "true" (any case) is true; every other text is false.
    const lower = text.toLowerCase();
    return { parsable: true, canonical: text === "true" || text === "false", effective: String(lower === "true") };
  }
  if (setting.type === TYPES.INTEGER || setting.type === TYPES.LONG) {
    // Integer.parseInt / Long.parseLong: optional sign, digits only, no trimming, range-checked.
    if (/^[+-]?[0-9]+$/.test(text)) {
      const n = BigInt(text.replace(/^\+/, ""));
      const lo = setting.type === TYPES.INTEGER ? -2147483648n : -9223372036854775808n;
      const hi = setting.type === TYPES.INTEGER ? BigInt(INT_MAX) : LONG_MAX;
      if (n >= lo && n <= hi) return { parsable: true, canonical: parseWholeNumber(text) !== null, effective: n.toString() };
    }
    return { parsable: false, effective: String(setting.default), reason: "The stored value is not a valid number; the backend silently uses its default." };
  }
  if (!text.trim()) return { parsable: false, effective: String(setting.default), reason: "The stored value is blank; the backend uses its default." };
  return { parsable: true, canonical: true, effective: text };
}

/**
 * Builds the page model from the two GET responses. Either may be null when that request failed; the model then
 * says so per row instead of inventing values.
 *  rows      array from GET /api/admin/configuration (or null)
 *  snapshot  object from GET /api/admin/configuration/current/all (or null)
 */
export function buildModel(rows, snapshot) {
  const list = Array.isArray(rows) ? rows : [];
  const byKey = new Map(list.filter((r) => r && typeof r.configKey === "string").map((r) => [r.configKey, r]));
  const supported = SETTINGS.map((setting) => {
    const row = byKey.get(setting.key) || null;
    const hasSnap = snapshot && setting.snapshot && Object.prototype.hasOwnProperty.call(snapshot, setting.snapshot);
    const effective = hasSnap ? snapshot[setting.snapshot] : undefined;
    const notes = [];
    let editable = setting.editable;
    let blockedReason = setting.readOnlyReason || null;
    let stored = null;
    let interp = null;

    if (!Array.isArray(rows)) {
      editable = false;
      blockedReason = blockedReason || "The stored configuration could not be loaded.";
    } else if (!row) {
      editable = false;
      blockedReason = blockedReason || "No configuration row exists for this key, so the backend uses its built-in default. This page cannot create rows.";
    } else {
      stored = row.configValue == null ? null : String(row.configValue);
      interp = interpretStored(setting, stored);
      if (row.dataType !== setting.type) {
        editable = false;
        blockedReason = blockedReason || `The row is typed ${row.dataType || "(none)"} but this setting is ${setting.type}. Editing is blocked to avoid rewriting the type.`;
      }
      if (!interp.parsable && interp.reason) notes.push(interp.reason);
      else if (interp.canonical === false) notes.push("The stored text is not in canonical form; saving will normalise it.");
      if (hasSnap && setting.snapshot && interp.parsable && setting.type !== TYPES.STRING && String(interp.effective) !== String(effective)) {
        notes.push("The effective value differs from the stored row (it may have changed since this page loaded).");
      }
    }
    return { setting, row, stored, effective, hasSnapshot: Boolean(hasSnap), editable, blockedReason, notes, interp };
  });

  const known = new Set(SUPPORTED_KEYS);
  const unknown = list.filter((r) => r && !known.has(r.configKey)).map((r) => ({
    key: String(r.configKey), value: r.configValue == null ? "" : String(r.configValue), dataType: r.dataType || "", description: r.description || ""
  }));
  return { supported, unknown, rowsLoaded: Array.isArray(rows), snapshotLoaded: Boolean(snapshot && typeof snapshot === "object") };
}

export function groupModel(model) {
  return GROUPS.map((group) => ({
    ...group,
    items: group.id === "other" ? [] : model.supported.filter((entry) => entry.setting.group === group.id),
    unknown: group.id === "other" ? model.unknown : []
  })).filter((g) => g.items.length || g.unknown.length);
}

/** Request for PUT /api/admin/configuration/{key}. Key is URL-encoded; only configValue + dataType are sent. */
export function buildUpdateRequest(key, valueText) {
  const setting = getSetting(key);
  if (!setting || !setting.editable) throw new Error("Unsupported configuration key: " + key);
  return {
    path: "/api/admin/configuration/" + encodeURIComponent(key),
    options: { method: "PUT", body: JSON.stringify({ configValue: String(valueText), dataType: setting.type }) }
  };
}
