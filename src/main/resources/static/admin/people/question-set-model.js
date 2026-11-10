/**
 * Pure helpers for the admin question-set view (no DOM, no network).
 * Input shape is exactly what GET /api/admin/users/{id}/questions returns in `topics`:
 *   [{ topic, count, difficulties, questions: [{ id, type, difficulty, question,
 *      optionA..optionD, correctAnswer, hint, explanation, payload, archived }] }]
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
    SORTING: "Sort & classify",
    CONCEPTID: "Four ideas \u2013 one name"
  };
  const key = String(type || "MCQ").toUpperCase();
  return labels[key] || key;
}

const PRACTICAL_KINDS = { SOLVE: "Solve it", OUTPUT: "Predict the output", ERROR_SPOT: "Spot the error" };

/** The full label for a question's badge. Practical questions name their sub-kind (Solve it, Predict the output, Spot the error). */
export function questionTypeLabel(q) {
  const base = typeLabel(q?.type);
  if (String(q?.type || "").toUpperCase() !== "PRACTICAL") return base;
  const kind = String(parsePayload(q)?.kind || "").toUpperCase();
  return PRACTICAL_KINDS[kind] ? `${base} \u00b7 ${PRACTICAL_KINDS[kind]}` : base;
}

/** The payload as an object, or null when it is missing, not JSON, or was cut off by the server. */
export function parsePayload(q) {
  const raw = q?.payload;
  if (raw == null || String(raw).trim() === "") return null;
  try {
    const data = JSON.parse(raw);
    return data && typeof data === "object" && !Array.isArray(data) ? data : null;
  } catch { return null; }
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
  return [q.question, q.optionA, q.optionB, q.optionC, q.optionD, q.correctAnswer, q.hint, q.explanation, q.payload, q.type]
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

const text = v => (v == null ? "" : typeof v === "string" ? v : JSON.stringify(v));
const clean = v => text(v).trim();
const list = v => (Array.isArray(v) ? v.map(clean).filter(Boolean) : []);

/** Splits a stem into prose and fenced code blocks: [{ kind: "text"|"code", text, lang? }]. */
export function splitStem(value) {
  const source = String(value ?? "");
  const parts = [];
  const fence = /```([A-Za-z0-9_+#.-]*)[ \t]*\r?\n?([\s\S]*?)```/g;
  let last = 0, m;
  while ((m = fence.exec(source))) {
    if (m.index > last) parts.push({ kind: "text", text: source.slice(last, m.index) });
    parts.push({ kind: "code", lang: m[1] || "", text: m[2].replace(/\s+$/, "") });
    last = fence.lastIndex;
  }
  if (last < source.length) parts.push({ kind: "text", text: source.slice(last) });
  return parts.filter(p => p.kind === "code" || p.text.trim() !== "").map(p => (p.kind === "text" ? { ...p, text: p.text.trim() } : p));
}

/** Options with the correct one flagged. [{ key, text, correct }] */
function choiceBlock(q, label) {
  const options = optionsOf(q);
  if (!options.length) return null;
  const correct = correctOptionKey(q);
  return {
    kind: "choices", label,
    options: options.map(o => ({ ...o, correct: o.key === correct })),
    resolved: correct != null
  };
}

function matchingBlock(data) {
  const left = list(data.leftItems), right = list(data.rightItems);
  const pairs = [];
  for (const p of Array.isArray(data.correctPairs) ? data.correctPairs : []) {
    const [a, b] = Array.isArray(p) ? p : [p?.left, p?.right];
    const l = left[Number(a)], r = right[Number(b)];
    if (l == null || r == null) return null;
    pairs.push({ left: l, right: r });
  }
  if (!pairs.length) return null;
  const used = new Set(pairs.map(p => p.right));
  return { kind: "matching", label: "Matching pairs", pairs, extras: right.filter(r => !used.has(r)) };
}

function fillBlankBlock(data) {
  const blanks = (Array.isArray(data.blanks) ? data.blanks : []).map(b => ({ id: String(b?.id ?? ""), answer: clean(b?.answer) }));
  const excerpt = text(data.excerpt);
  if (!excerpt && !blanks.length) return null;
  const answers = new Map(blanks.map(b => [b.id, b.answer]));
  const segments = [];
  let last = 0, m;
  const slot = /\{\{\s*(\w+)\s*\}\}/g;
  while ((m = slot.exec(excerpt))) {
    if (m.index > last) segments.push({ text: excerpt.slice(last, m.index) });
    segments.push({ blank: m[1], answer: answers.get(m[1]) ?? "?" });
    last = slot.lastIndex;
  }
  if (last < excerpt.length) segments.push({ text: excerpt.slice(last) });
  const mode = String(data.mode || "").toLowerCase();
  return {
    kind: "fillblank", label: "Fill in the blanks", segments, blanks,
    mode: mode === "dragdrop" ? "Drag and drop (word bank)" : mode === "typed" ? "Typed answer" : ""
  };
}

function sortingBlock(data) {
  const names = { A: clean(data.categoryA) || "Category A", B: clean(data.categoryB) || "Category B" };
  const buckets = { A: [], B: [] };
  for (const item of Array.isArray(data.items) ? data.items : []) {
    const key = String(item?.correctCategory ?? "").trim().toUpperCase();
    if (buckets[key]) buckets[key].push(clean(item?.text));
  }
  if (!buckets.A.length && !buckets.B.length) return null;
  return { kind: "sorting", label: "Correct sorting", categories: [{ name: names.A, items: buckets.A }, { name: names.B, items: buckets.B }] };
}

function conceptBlock(data) {
  const clues = list(data.clues), answer = clean(data.correctAnswer);
  if (!clues.length && !answer) return null;
  const accepted = [...new Set(list(data.acceptedAnswers))].filter(a => a !== answer);
  return { kind: "concept", label: "Clues and answer", clues, answer, accepted };
}

/**
 * Everything a reviewer needs to read one question, already shaped for display.
 * Returns { stem, blocks, worked, hint, feedback, fallbackAnswer }:
 *   stem      - [{ kind: "text"|"code", text, lang? }]
 *   blocks    - type-specific answer structure(s): choices | matching | fillblank | sorting | concept | list
 *   worked    - practical worked solution, or ""
 *   hint      - the stored hint, or ""
 *   feedback  - the stored explanation shown to students after answering, or ""
 *   fallbackAnswer - the stored correct answer, only when no block could present it
 */
export function describeQuestion(q) {
  const type = String(q?.type || "MCQ").toUpperCase();
  const data = parsePayload(q);
  const blocks = [];
  let worked = "";
  let feedback = clean(q?.explanation);

  if (type === "MCQ" || type === "TRUEFALSE" || type === "PRACTICAL") {
    const b = choiceBlock(q, "Choices");
    if (b) blocks.push(b);
  }
  if (type === "PRACTICAL" && data) {
    const language = clean(data.language);
    if (language) blocks.push({ kind: "meta", label: "Language", value: language });
    worked = clean(data.workedSolution);
    // The server appends the worked solution to the explanation as "Working: ...". Show it once, as its own block.
    if (worked) {
      const at = feedback.lastIndexOf("Working:");
      if (at >= 0 && feedback.slice(at + 8).trim() === worked) feedback = feedback.slice(0, at).trim();
    }
  }
  if (data) {
    const b = type === "MATCHING" ? matchingBlock(data)
      : type === "FILLBLANK" ? fillBlankBlock(data)
      : type === "SORTING" ? sortingBlock(data)
      : type === "CONCEPTID" ? conceptBlock(data)
      : null;
    if (b) blocks.push(b);
    if (type === "ESSAY" && list(data.rubric).length) blocks.push({ kind: "list", label: "Rubric (marking points)", items: list(data.rubric) });
  }

  const choices = blocks.find(b => b.kind === "choices");
  const presented = blocks.some(b => ["matching", "fillblank", "sorting", "concept"].includes(b.kind)) || (choices && choices.resolved);
  const answer = clean(q?.correctAnswer);
  return {
    stem: splitStem(q?.question),
    blocks,
    worked,
    hint: clean(q?.hint),
    feedback,
    fallbackAnswer: answer && !presented ? answer : ""
  };
}

/** The question as a readable JSON document for the "Raw data" disclosure: stored fields, payload parsed when possible. */
export function rawView(q) {
  const out = {};
  for (const [k, v] of Object.entries(q || {})) {
    if (k === "payload") {
      const parsed = parsePayload(q);
      out.payload = parsed ?? v;
    } else {
      out[k] = v;
    }
  }
  return JSON.stringify(out, null, 2);
}
