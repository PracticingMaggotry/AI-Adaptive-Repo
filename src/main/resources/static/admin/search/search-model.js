/**
 * Navigation-search model for the admin command palette (Ctrl/Cmd+K).
 *
 * Pure data and string logic: no DOM, no window, no network. Every destination is derived from the
 * route registry that is passed in (admin/routes.js `routes`, `sections`, `aliases`); this file keeps
 * NO route list of its own. Optional per-route `keywords: string[]` are honoured if the registry ever
 * adds them.
 *
 * What it can find: registry pages, host-supplied actions, and one dynamic kind - a parameterised
 * route (e.g. "/people/users/:id") when the query carries a value for the parameter ("user 12",
 * "/people/users/12"). It does NOT search records (users, materials, questions, log rows, ...).
 *
 * Nothing here builds HTML. Callers render every string with textContent.
 */

const NON_ALNUM = /[^\p{L}\p{N}]+/gu;
const MARKS = /\p{M}/gu;

/** Lower-case, accent-stripped form used for all comparisons. */
export function normalize(value) {
  return String(value ?? "").normalize("NFKD").replace(MARKS, "").toLowerCase();
}
/** Alphanumerics only: "Banned emails" -> "bannedemails" (matches old hash aliases too). */
export function compact(value) {
  return normalize(value).replace(NON_ALNUM, "");
}
/** Alphanumeric words: "/people/banned-emails" -> ["people", "banned", "emails"]. */
export function words(value) {
  return normalize(value).split(NON_ALNUM).filter(Boolean);
}

const STATUS_TAGS = Object.freeze({
  "not-implemented": "Not built",
  partial: "Partial",
  unavailable: "Unavailable"
});

function cleanStrings(list) {
  if (!Array.isArray(list)) return [];
  return list.filter((item) => typeof item === "string" && item.trim()).map((item) => item.trim());
}

function sectionLabelMap(sections) {
  const map = new Map();
  for (const section of Array.isArray(sections) ? sections : []) {
    if (section && typeof section.id === "string") map.set(section.id, String(section.label ?? section.id));
  }
  return map;
}

/** { "/people/users": ["users"], ... } built from the registry's old-hash aliases. */
function aliasKeysByPath(aliases) {
  const out = new Map();
  if (!aliases || typeof aliases !== "object") return out;
  for (const key of Object.keys(aliases)) {
    const target = aliases[key];
    if (typeof target !== "string") continue;
    const path = "/" + target.split("/").filter(Boolean).join("/").toLowerCase();
    if (!out.has(path)) out.set(path, []);
    out.get(path).push(key);
  }
  return out;
}

function finishEntry(entry) {
  const title = String(entry.title);
  return Object.freeze({
    ...entry,
    title,
    _title: normalize(title),
    _titleWords: words(title),
    _titleCompact: compact(title),
    _sectionWords: words(entry.section || ""),
    _aliases: (entry.aliasKeys || []).map(compact).filter(Boolean),
    _keywordWords: [...new Set((entry.keywords || []).flatMap(words))]
  });
}

/**
 * Entries for every static route plus the supplied actions.
 * Parameterised routes (":id") are not listed; see resolveDynamicEntries().
 */
export function buildEntries({ routes = [], sections = [], aliases = {}, actions = [] } = {}) {
  const sectionLabels = sectionLabelMap(sections);
  const aliasMap = aliasKeysByPath(aliases);
  const entries = [];

  (Array.isArray(routes) ? routes : []).forEach((route, index) => {
    if (!route || typeof route.path !== "string" || route.path.includes(":")) return;
    const title = String(route.label || route.title || route.path);
    const section = sectionLabels.get(route.section) || "";
    const aliasKeys = aliasMap.get(route.path.toLowerCase()) || [];
    const tags = [];
    if (STATUS_TAGS[route.status]) tags.push(STATUS_TAGS[route.status]);
    if (route.after && typeof route.after.modal === "string") tags.push("Opens dialog");
    entries.push(finishEntry({
      id: "route:" + route.path,
      kind: "route",
      title,
      section,
      path: route.path,
      hash: "#" + route.path,
      status: route.status || "",
      tags,
      aliasKeys,
      keywords: [
        ...words(route.path),
        ...(section ? [section] : []),
        ...(typeof route.section === "string" ? [route.section] : []),
        ...cleanStrings(route.keywords)
      ],
      order: index
    }));
  });

  (Array.isArray(actions) ? actions : []).forEach((action, index) => {
    if (!action || typeof action.title !== "string" || !action.title.trim() || typeof action.run !== "function") return;
    entries.push(finishEntry({
      id: "action:" + String(action.id ?? action.title),
      kind: "action",
      title: action.title.trim(),
      section: "Actions",
      description: typeof action.description === "string" ? action.description : "",
      path: "",
      hash: "",
      tags: ["Action"],
      aliasKeys: [],
      keywords: ["action", ...cleanStrings(action.keywords)],
      run: action.run,
      order: 10000 + index
    }));
  });

  return entries;
}

/** Score of one query token against one entry; 0 = no match. */
function scoreToken(entry, token) {
  let best = 0;
  const bump = (n) => { if (n > best) best = n; };

  entry._titleWords.forEach((word, i) => {
    if (word === token) bump(i === 0 ? 95 : 90);
    else if (word.startsWith(token)) bump(i === 0 ? 85 : 80);
  });
  if (entry._titleCompact.startsWith(token)) bump(80);
  else if (entry._titleCompact.includes(token)) bump(55);

  for (const alias of entry._aliases) {
    if (alias === token) bump(85);
    else if (alias.startsWith(token)) bump(65);
    else if (token.length >= 3 && alias.includes(token)) bump(40);
  }
  for (const word of entry._keywordWords) {
    if (word === token) bump(50);
    else if (word.startsWith(token)) bump(40);
  }
  for (const word of entry._sectionWords) {
    if (word === token) bump(35);
    else if (word.startsWith(token)) bump(30);
  }
  return best;
}

/**
 * Rank entries for a query. Every whitespace/punctuation separated token must match somewhere
 * (title, old-hash alias, keyword, section). An empty query returns everything in registry order.
 * Returns [{ entry, score, tokens }].
 */
export function searchEntries(entries, query, { limit = 50 } = {}) {
  const tokens = words(query);
  if (tokens.length === 0) {
    return entries.slice(0, limit).map((entry) => ({ entry, score: 0, tokens }));
  }
  const whole = compact(query);
  const ranked = [];
  for (const entry of entries) {
    let sum = 0;
    let ok = true;
    for (const token of tokens) {
      const s = scoreToken(entry, token);
      if (s === 0) { ok = false; break; }
      sum += s;
    }
    if (!ok) continue;
    let score = sum / tokens.length;
    if (whole && entry._titleCompact === whole) score += 30;
    else if (whole && entry._titleCompact.startsWith(whole)) score += 15;
    ranked.push({ entry, score, tokens });
  }
  ranked.sort((a, b) => (b.score - a.score) || (a.entry.order - b.entry.order));
  return ranked.slice(0, limit);
}

const MAX_PARAM_LENGTH = 64;

/**
 * Entries for parameterised routes, built from the query itself. Two inputs are understood:
 *   "/people/users/12" or "#/people/users/12"   a typed canonical path
 *   "user 12", "users #12", "user detail 12"    words that match the route, then the value
 * Every candidate is checked with the host's resolve() (the router), so only paths the router accepts
 * and that land on the SAME route record are offered. Whether the record exists is not checked.
 */
export function resolveDynamicEntries(query, { routes = [], sections = [], resolve } = {}) {
  if (typeof resolve !== "function") return [];
  let raw = String(query ?? "").trim().replace(/^#/, "");
  if (!raw) return [];
  if (raw.includes("/") && !raw.startsWith("/") && !/\s/.test(raw)) raw = "/" + raw;   // "people/users/12"
  const sectionLabels = sectionLabelMap(sections);
  const out = [];
  const seen = new Set();

  const add = (route, resolved) => {
    if (!resolved || resolved.kind !== "ok" || resolved.route !== route || seen.has(resolved.path)) return;
    const values = Object.values(resolved.params || {});
    if (values.some((v) => String(v).length > MAX_PARAM_LENGTH)) return;
    seen.add(resolved.path);
    const base = String(route.label || route.title || route.path);
    const title = values.length ? `${base} ${values.join(" ")}` : base;
    out.push(finishEntry({
      id: "dynamic:" + resolved.path,
      kind: "dynamic",
      title,
      section: sectionLabels.get(route.section) || "",
      description: "Opens this page directly. The record is not looked up until the page loads.",
      path: resolved.path,
      hash: "#" + resolved.path,
      status: route.status || "",
      tags: STATUS_TAGS[route.status] ? [STATUS_TAGS[route.status]] : [],
      aliasKeys: [],
      keywords: [],
      order: 5000 + out.length
    }));
  };

  const paramRoutes = (Array.isArray(routes) ? routes : []).filter((r) => r && typeof r.path === "string" && (r.path.match(/:/g) || []).length === 1);
  if (paramRoutes.length === 0) return out;

  if (raw.startsWith("/")) {
    const [pathOnly] = raw.split("?");
    let resolved;
    try { resolved = resolve(pathOnly); } catch { resolved = null; }
    if (resolved && resolved.kind === "ok" && paramRoutes.includes(resolved.route)) add(resolved.route, resolved);
    return out;
  }

  const parts = raw.split(/\s+/).filter(Boolean);
  if (parts.length < 2) return out;
  const value = parts[parts.length - 1].replace(/^#/, "");
  const lead = parts.slice(0, -1).filter((p) => !/^(id|no|number|#)$/i.test(p));
  if (!value || value.length > MAX_PARAM_LENGTH || lead.length === 0) return out;

  for (const route of paramRoutes) {
    const vocabulary = [...words(route.label || ""), ...words(route.title || ""), ...words(route.path.replace(/:[^/]+/, ""))];
    const leadMatches = lead.every((part) => {
      const w = words(part);
      return w.length > 0 && w.every((token) => vocabulary.some((v) => v.startsWith(token) || token.startsWith(v)));
    });
    if (!leadMatches) continue;
    const candidate = route.path.replace(/:[^/]+/, encodeURIComponent(value));
    let resolved;
    try { resolved = resolve(candidate); } catch { resolved = null; }
    add(route, resolved);
  }
  return out;
}

/** Everything the palette shows for a query: dynamic entries first (explicit intent), then ranked matches. */
export function computeResults(entries, query, options = {}) {
  const limit = options.limit ?? 50;
  const dynamic = resolveDynamicEntries(query, options).map((entry) => ({ entry, score: 1000, tokens: words(query) }));
  const ranked = searchEntries(entries, query, { limit });
  return [...dynamic, ...ranked].slice(0, limit);
}

/**
 * Character ranges of `text` that match the query tokens, for <mark> rendering. Returns [] when the
 * lower-cased text changes length (rare Unicode cases) so indices can never be wrong.
 */
export function highlightRanges(text, tokens) {
  const source = String(text ?? "");
  const lower = source.toLowerCase();
  if (lower.length !== source.length || !tokens || tokens.length === 0) return [];
  const ranges = [];
  for (const token of tokens) {
    if (!token) continue;
    const at = lower.indexOf(token);
    if (at !== -1) ranges.push([at, at + token.length]);
  }
  ranges.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const range of ranges) {
    const last = merged[merged.length - 1];
    if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
    else merged.push([...range]);
  }
  return merged;
}
