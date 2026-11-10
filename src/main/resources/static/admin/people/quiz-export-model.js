/**
 * Pure model for the quiz export (no DOM, no libraries). Turns one topic's questions into a printable quiz:
 *
 *   header -> Test I ... Test N (each with directions, points and numbered items) -> answer key
 *
 * Layout is the common "Test I, Test II ..." paper-quiz format. The order of tests is fixed by question type,
 * so a jumbled question set always prints the same way:
 *
 *   I   Multiple Choice            V    Identification (four ideas, one name)
 *   II  True or False              VI   Classification (sorting)
 *   III Matching Type              VII  Code Analysis: Predict the Output
 *   IV  Fill in the Blanks         VIII Code Analysis: Spot the Error
 *                                  IX   Problem Solving (Solve It)
 *                                  X    Essay
 *
 * Tests with no questions are skipped and the numerals close up (a quiz with no matching items has no
 * Test III). Problem Solving and Essay are always the last two tests. Archived questions are never exported.
 * Item numbers restart at 1 in every test; one point per item (a blank, a pair or a sorted item each count as an item).
 */
import { describeQuestion, normalizeDifficulty, parsePayload } from "./question-set-model.js";

const NUMERALS = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII"];
const DIFF_RANK = { Easy: 0, Medium: 1, Hard: 2, Unknown: 3 };
const LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

const SPECS = [
  { id: "MCQ", title: "Multiple Choice",
    directions: "Choose the letter of the best answer. Write your answer on the blank before each number." },
  { id: "TRUEFALSE", title: "True or False",
    directions: "Write TRUE if the statement is correct and FALSE if it is not. Write your answer on the blank before each number." },
  { id: "MATCHING", title: "Matching Type",
    directions: "Match each item in Column A with its answer in Column B. Write the letter of your answer on the blank before each number." },
  { id: "FILLBLANK", title: "Fill in the Blanks",
    directions: "Complete each sentence by writing the missing word or words on the blanks provided." },
  { id: "CONCEPTID", title: "Identification",
    directions: "Four clues are given for each item. Identify the one concept that all four clues describe, and write it on the blank." },
  { id: "SORTING", title: "Classification",
    directions: "Classify each item under the correct category. Write the letter of the category on the blank before each number." },
  { id: "OUTPUT", title: "Code Analysis: Predict the Output",
    directions: "Read each code snippet carefully. Write the letter of the choice that shows what it prints or returns." },
  { id: "ERROR_SPOT", title: "Code Analysis: Spot the Error",
    directions: "Read each snippet or worked solution carefully. Write the letter of the choice that identifies the error." },
  { id: "SOLVE", title: "Problem Solving",
    directions: "Solve each problem. Show your complete solution in the space provided and write your final answer on the answer line." },
  { id: "ESSAY", title: "Essay",
    directions: "Answer each question in complete sentences. The points for each question are shown beside it." }
];

/** Which test a question belongs to. PRACTICAL splits by its kind; an unlabelled one is judged by whether its stem has code. */
export function testIdFor(q) {
  const type = String(q?.type || "MCQ").toUpperCase();
  if (type !== "PRACTICAL") return SPECS.some(s => s.id === type) ? type : "MCQ";
  const kind = String(parsePayload(q)?.kind || "").toUpperCase();
  if (kind === "SOLVE" || kind === "OUTPUT" || kind === "ERROR_SPOT") return kind;
  return /```/.test(String(q?.question || "")) ? "OUTPUT" : "SOLVE";
}

/* ---------- text helpers ---------- */

const MATH_COMMANDS = [
  [/\\times/g, "\u00d7"], [/\\div/g, "\u00f7"], [/\\cdot/g, "\u00b7"], [/\\pm/g, "\u00b1"],
  [/\\leq?\b/g, "\u2264"], [/\\geq?\b/g, "\u2265"], [/\\neq?\b/g, "\u2260"], [/\\approx/g, "\u2248"],
  [/\\pi\b/g, "\u03c0"], [/\\infty/g, "\u221e"], [/\\rightarrow|\\to\b/g, "\u2192"], [/\\left|\\right/g, ""],
  [/\\[,;:! ]/g, " "]
];

/** Print-friendly text: LaTeX between dollar signs becomes plain symbols, since nothing here renders math. */
export function plain(value) {
  let s = String(value ?? "").replace(/\r\n?/g, "\n");
  s = s.replace(/\$\$?([^$]+?)\$\$?/g, (_, m) => {
    let t = m;
    for (const [re, to] of MATH_COMMANDS) t = t.replace(re, to);
    t = t.replace(/\\text\{([^}]*)\}/g, "$1").replace(/\\mathrm\{([^}]*)\}/g, "$1");
    t = t.replace(/\\frac\{([^{}]*)\}\{([^{}]*)\}/g, "($1)/($2)").replace(/\\sqrt\{([^{}]*)\}/g, "\u221a($1)");
    t = t.replace(/\^\{([^{}]*)\}/g, "^$1").replace(/_\{([^{}]*)\}/g, "_$1");
    return t.replace(/\s+/g, " ").trim();
  });
  return s;
}

function stemParts(q) {
  return describeQuestion(q).stem.map(p => (p.kind === "code" ? p : { ...p, text: plain(p.text) }));
}

/** mulberry32: a tiny seeded PRNG, so a given question always prints in the same order. */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function seedOf(q) {
  if (q?.id != null && Number.isFinite(Number(q.id))) return Number(q.id) * 2654435761;
  let h = 2166136261;
  for (const c of String(q?.question || "")) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return h;
}

/** Seeded shuffle of 0..n-1 that is never the identity order (rotated by one if it comes out unchanged). */
function shuffledOrder(n, seed) {
  const order = Array.from({ length: n }, (_, i) => i);
  const rand = rng(seed);
  for (let i = n - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  if (n > 1 && order.every((v, i) => v === i)) order.push(order.shift());
  return order;
}

/** Like shuffledOrder, but tries further seeds until no category appears more than twice in a row, so the order hints at nothing. */
function mixedOrder(cats, seed) {
  let best = shuffledOrder(cats.length, seed);
  if (cats.length < 4 || new Set(cats).size < 2) return best;
  for (let attempt = 0; attempt < 60; attempt++) {
    const order = shuffledOrder(cats.length, seed + attempt * 7919);
    let run = 1, longest = 1;
    for (let i = 1; i < order.length; i++) {
      run = cats[order[i]] === cats[order[i - 1]] ? run + 1 : 1;
      longest = Math.max(longest, run);
    }
    if (longest <= 2) return order;
    best = order;
  }
  return best;
}

function pointsText(n) {
  return `${n} ${n === 1 ? "point" : "points"}`;
}

/* ---------- items, by test ---------- */

function choiceItem(q, number, { showOptions }) {
  const d = describeQuestion(q);
  const choices = d.blocks.find(b => b.kind === "choices");
  const correct = choices?.options.find(o => o.correct);
  const answerText = correct ? plain(correct.text) : plain(q.correctAnswer);
  return {
    kind: "choice", number, points: 1, stem: stemParts(q),
    options: showOptions && choices ? choices.options.map(o => ({ key: o.key, text: plain(o.text) })) : [],
    answers: [{ label: String(number), text: correct && showOptions ? `${correct.key}. ${answerText}` : answerText }]
  };
}

function trueFalseItem(q, number) {
  const choices = describeQuestion(q).blocks.find(b => b.kind === "choices");
  const correct = choices?.options.find(o => o.correct)?.text ?? q.correctAnswer;
  return {
    kind: "truefalse", number, points: 1, stem: stemParts(q),
    answers: [{ label: String(number), text: String(correct ?? "").trim().toUpperCase() || "(none stored)" }]
  };
}

function matchingItem(q, start) {
  const payload = parsePayload(q) || {};
  const left = (payload.leftItems || []).map(plain);
  const right = (payload.rightItems || []).map(plain);
  const block = describeQuestion(q).blocks.find(b => b.kind === "matching");
  if (!block || !left.length) return null;
  const order = shuffledOrder(right.length, seedOf(q));          // order[position] = index into rightItems
  const letterOfRight = new Map(order.map((rightIndex, position) => [rightIndex, LETTERS[position]]));
  const columnB = order.map((rightIndex, position) => ({ letter: LETTERS[position], text: right[rightIndex] }));
  const columnA = [];
  const answers = [];
  for (const p of payload.correctPairs || []) {
    const [a, b] = Array.isArray(p) ? p : [p?.left, p?.right];
    const li = Number(a), ri = Number(b);
    if (!Number.isInteger(li) || !Number.isInteger(ri) || left[li] == null || right[ri] == null) return null;
    columnA.push({ li, text: left[li], letter: letterOfRight.get(ri), right: right[ri] });
  }
  columnA.sort((x, y) => x.li - y.li);
  columnA.forEach((row, i) => {
    row.number = start + i;
    answers.push({ label: String(row.number), text: `${row.letter}. ${row.right}` });
  });
  return {
    kind: "matching", number: start, count: columnA.length, points: columnA.length,
    columnA: columnA.map(r => ({ number: r.number, text: r.text })), columnB,
    unused: columnB.length - columnA.length, answers
  };
}

function fillItem(q, number) {
  const block = describeQuestion(q).blocks.find(b => b.kind === "fillblank");
  if (!block) return null;
  const segments = block.segments.map(s => (s.blank == null ? { text: plain(s.text) } : { blank: s.blank, answer: plain(s.answer) }));
  const blanks = segments.filter(s => s.blank != null);
  if (!blanks.length) return null;
  const labelled = blanks.length > 1;
  blanks.forEach((b, i) => { b.tag = labelled ? `(${LETTERS[i].toLowerCase()})` : ""; });
  return {
    kind: "fill", number, points: blanks.length, segments,
    dragdrop: /drag/i.test(block.mode || ""),
    answers: [{ label: String(number), text: blanks.map(b => (labelled ? `${b.tag} ${b.answer}` : b.answer)).join("   ") }]
  };
}

function conceptItem(q, number) {
  const block = describeQuestion(q).blocks.find(b => b.kind === "concept");
  if (!block) return null;
  return {
    kind: "concept", number, points: 1, stem: stemParts(q), clues: block.clues.map(plain),
    answers: [{ label: String(number), text: [plain(block.answer), ...block.accepted.map(a => plain(a))].filter(Boolean).join("  /  ") || "(none stored)" }]
  };
}

function sortingItem(q, start) {
  const payload = parsePayload(q) || {};
  const names = { A: plain(payload.categoryA) || "Category A", B: plain(payload.categoryB) || "Category B" };
  const raw = (Array.isArray(payload.items) ? payload.items : [])
    .map(i => ({ text: plain(i?.text), cat: String(i?.correctCategory ?? "").trim().toUpperCase() }))
    .filter(i => i.text && (i.cat === "A" || i.cat === "B"));
  if (!raw.length) return null;
  const order = mixedOrder(raw.map(r => r.cat), seedOf(q));
  const items = order.map((idx, i) => ({ number: start + i, text: raw[idx].text, cat: raw[idx].cat }));
  return {
    kind: "sorting", number: start, count: items.length, points: items.length, stem: stemParts(q),
    categories: [{ letter: "A", name: names.A }, { letter: "B", name: names.B }],
    items: items.map(i => ({ number: i.number, text: i.text })),
    answers: items.map(i => ({ label: String(i.number), text: `${i.cat}. ${names[i.cat]}` }))
  };
}

function solveItem(q, number) {
  const d = describeQuestion(q);
  const choices = d.blocks.find(b => b.kind === "choices");
  const correct = choices?.options.find(o => o.correct);
  const answer = plain(correct ? correct.text : q.correctAnswer) || "(none stored)";
  return {
    kind: "solve", number, points: 1, stem: stemParts(q),
    answers: [{ label: String(number), text: answer, note: d.worked ? plain(d.worked) : "" }]
  };
}

function essayItem(q, number) {
  const rubric = describeQuestion(q).blocks.find(b => b.kind === "list")?.items.map(plain) || [];
  const points = Math.max(rubric.length, 1);
  return {
    kind: "essay", number, points, stem: stemParts(q),
    answers: [{ label: String(number), text: rubric.length ? "Award one point for each:" : "No rubric stored.", lines: rubric }]
  };
}

/* ---------- quiz ---------- */

function buildItems(id, questions) {
  const items = [];
  let n = 1;
  for (const q of questions) {
    let item = null;
    switch (id) {
      case "MCQ": item = choiceItem(q, n, { showOptions: true }); break;
      case "TRUEFALSE": item = trueFalseItem(q, n); break;
      case "MATCHING": item = matchingItem(q, n); break;
      case "FILLBLANK": item = fillItem(q, n); break;
      case "CONCEPTID": item = conceptItem(q, n); break;
      case "SORTING": item = sortingItem(q, n); break;
      case "OUTPUT": case "ERROR_SPOT": item = choiceItem(q, n, { showOptions: true }); break;
      case "SOLVE": item = solveItem(q, n); break;
      case "ESSAY": item = essayItem(q, n); break;
      default: break;
    }
    if (!item) continue;
    items.push(item);
    n += item.count || 1;
  }
  return items;
}



/**
 * @param {{ topic: string, questions: object[] }} topic  one entry of `topics` from the admin questions endpoint
 * @returns {{ title: string, total: number, tests: object[], skipped: number }}
 *   tests[i] = { numeral, id, title, directions, points, bank?: string[], items: object[] }
 */
export function buildQuiz(topic) {
  const live = (topic?.questions || []).filter(q => !q.archived);
  const byTest = new Map(SPECS.map(s => [s.id, []]));
  for (const q of live) byTest.get(testIdFor(q)).push(q);

  const tests = [];
  let skipped = 0;
  for (const spec of SPECS) {
    const questions = byTest.get(spec.id)
      .map((q, i) => ({ q, i }))
      .sort((a, b) => DIFF_RANK[normalizeDifficulty(a.q.difficulty)] - DIFF_RANK[normalizeDifficulty(b.q.difficulty)] || a.i - b.i)
      .map(e => e.q);
    const items = buildItems(spec.id, questions);
    skipped += questions.length - items.length;   // one item per question; a question with a broken payload yields none
    if (!items.length) continue;
    const points = items.reduce((sum, i) => sum + i.points, 0);
    const test = { id: spec.id, title: spec.title, directions: spec.directions, points, items };
    if (spec.id === "FILLBLANK" && items.some(i => i.dragdrop)) {
      test.bank = items.flatMap(i => i.segments.filter(s => s.blank != null).map(s => s.answer))
        .sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
      test.directions += " Choose your answers from the word bank.";
    }
    if (spec.id === "MATCHING" && items.some(i => i.unused > 0)) test.directions += " Some letters will not be used.";
    tests.push(test);
  }
  tests.forEach((t, i) => { t.numeral = NUMERALS[i] || String(i + 1); });
  return {
    title: String(topic?.topic || "Quiz"),
    total: tests.reduce((sum, t) => sum + t.points, 0),
    tests, skipped
  };
}

export function testHeading(test) {
  return `Test ${test.numeral}. ${test.title}`;
}

export { pointsText };

/** A safe file name stem: letters, digits, dashes. */
export function fileStem(title) {
  const s = String(title || "quiz").normalize("NFKD").replace(/[^\w\s-]/g, "").trim().replace(/[\s_]+/g, "-").replace(/-+/g, "-").toLowerCase();
  return (s || "quiz").slice(0, 60);
}
