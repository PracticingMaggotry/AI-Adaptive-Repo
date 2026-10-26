/**
 * Pure helpers for the admin question-set view (no DOM, no network).
 * Input shape is exactly what GET /api/admin/users/{id}/questions returns in `topics`:
 *   [{ topic, count, difficulties, questions: [{ id, type, difficulty, question,
 *      optionA..optionD, correctAnswer, payload, archived }] }]
 */

export const DIFFICULTY_ORDER = ["Easy", "Medium", "Hard"];

const OPTION_KEYS = ["A", "B", "C", "D"];

/** "easy" / " HARD " -> "Easy" / "Hard". Anything unrecognised -> "Unknown". */
export function normalizeDifficulty(value) {
  const text = String(value ?? "").trim().toLowerCase();
  const hit = DIFFICULTY_ORDER.find(d => d.toLowerCase() === text);
  return hit || "Unknown";
}

export function typeLabel(type) {
  const labels = {
    MCQ: "Multiple choice",
    TRUEFALSE: "True / False",
    PRACTICAL: "Practical",
    MATCHING: "Matching",
    FILLBLANK: "Fill in the blank",
    ESSAY: "Essay",
    CONCEPTID: "Concept ID"
  };
  const key = String(type || "MCQ").toUpperCase();
  return labels[key] || key;
}

/** Options present on a question, in A-D order. [{ key: "A", text: "..." }] */
export function optionsOf(q) {
  const raw = { A: q?.optionA, B: q?.optionB, C: q?.optionC, D: q?.optionD };
  return OPTION_KEYS.filter(k => raw[k] != null && String(raw[k]).trim() !== "")
    .map(k => ({ key: k, text: String(raw[k]) }));
}

/**
 * Which option letter is the correct one, or null when it cannot be resolved to exactly one option.
 * The backend stores either an option's text or a bare letter, so both are accepted. Text is tried first,
 * matching how the quiz grades it.
 */
export function correctOptionKey(q) {
  const answer = String(q?.correctAnswer ?? "").trim();
  if (!answer) return null;
  const options = optionsOf(q);
  const exact = options.filter(o => o.text.trim() === answer);
  if (exact.length === 1) return exact[0].key;
  const loose = options.filter(o => o.text.trim().toLowerCase() === answer.toLowerCase());
  if (loose.length === 1) return loose[0].key;
  const letter = answer.replace(/[.)\s]/g, "").toUpperCase();
  if (OPTION_KEYS.includes(letter) && options.some(o => o.key === letter)) return letter;
  return null;
}

/** Counts for the summary strip. Archived questions are counted separately, not in difficulty/type totals. */
export function summarize(topics) {
  const out = {
    total: 0, live: 0, archived: 0, topics: Array.isArray(topics) ? topics.length : 0,
    byDifficulty: { Easy: 0, Medium: 0, Hard: 0, Unknown: 0 }, byType: {}
  };
  for (const t of Array.isArray(topics) ? topics : []) {
    for (const q of t.questions || []) {
      out.total++;
      if (q.archived) { out.archived++; continue; }
      out.live++;
      out.byDifficulty[normalizeDifficulty(q.difficulty)]++;
      const type = String(q.type || "MCQ").toUpperCase();
      out.byType[type] = (out.byType[type] || 0) + 1;
    }
  }
  return out;
}

/** Per-topic difficulty counts of live questions, used by the topic header bar. */
export function topicBreakdown(topic) {
  const counts = { Easy: 0, Medium: 0, Hard: 0, Unknown: 0 };
  let archived = 0;
  for (const q of topic.questions || []) {
    if (q.archived) archived++; else counts[normalizeDifficulty(q.difficulty)]++;
  }
  return { counts, archived, live: (topic.questions || []).length - archived };
}

function haystack(q) {
  return [q.question, q.optionA, q.optionB, q.optionC, q.optionD, q.correctAnswer, q.type]
    .filter(v => v != null).join(" \n ").toLowerCase();
}

/**
 * filters: { query: string, difficulties: Set<string>, types: Set<string>, showArchived: boolean }
 * Empty sets mean "no restriction". Topics with no remaining questions are dropped. Original order is kept.
 */
export function filterTopics(topics, filters = {}) {
  const query = String(filters.query ?? "").trim().toLowerCase();
  const diffs = filters.difficulties instanceof Set ? filters.difficulties : new Set();
  const types = filters.types instanceof Set ? filters.types : new Set();
  const showArchived = filters.showArchived !== false;
  const result = [];
  for (const t of Array.isArray(topics) ? topics : []) {
    const topicMatches = query && String(t.topic ?? "").toLowerCase().includes(query);
    const kept = (t.questions || []).filter(q => {
      if (q.archived && !showArchived) return false;
      if (diffs.size && !diffs.has(normalizeDifficulty(q.difficulty))) return false;
      if (types.size && !types.has(String(q.type || "MCQ").toUpperCase())) return false;
      if (query && !topicMatches && !haystack(q).includes(query)) return false;
      return true;
    });
    if (kept.length) result.push({ ...t, questions: kept, count: kept.length });
  }
  return result;
}

export function isFiltering(filters = {}) {
  return Boolean(String(filters.query ?? "").trim())
    || (filters.difficulties instanceof Set && filters.difficulties.size > 0)
    || (filters.types instanceof Set && filters.types.size > 0)
    || filters.showArchived === false;
}

/**
 * A readable version of a non-option question's payload.
 * Returns { kind: "list", items: string[] } for shapes we recognise, or { kind: "raw", text } otherwise.
 * The server truncates payloads over 2000 chars, so JSON.parse can fail; that falls back to raw text.
 */
export function payloadView(q) {
  const raw = q?.payload;
  if (raw == null || String(raw).trim() === "") return null;
  let data;
  try { data = JSON.parse(raw); } catch { return { kind: "raw", text: String(raw) }; }
  if (!data || typeof data !== "object") return { kind: "raw", text: String(raw) };

  const type = String(q.type || "").toUpperCase();
  const asText = v => (typeof v === "string" ? v : JSON.stringify(v));
  if (type === "ESSAY" && Array.isArray(data.rubric)) {
    return { kind: "list", label: "Rubric", items: data.rubric.map(asText) };
  }
  if (type === "MATCHING" && Array.isArray(data.correctPairs)) {
    return { kind: "list", label: "Correct pairs", items: data.correctPairs.map(p =>
      Array.isArray(p) ? p.map(asText).join(" \u2192 ")
        : (p && typeof p === "object" ? Object.values(p).map(asText).join(" \u2192 ") : asText(p))) };
  }
  if (type === "FILLBLANK" && Array.isArray(data.blanks)) {
    return { kind: "list", label: "Blanks", items: data.blanks.map(b =>
      b && typeof b === "object" ? Object.values(b).map(asText).join(" \u2014 ") : asText(b)) };
  }
  return { kind: "raw", text: JSON.stringify(data, null, 2) };
}
