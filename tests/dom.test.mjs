import { JSDOM } from "jsdom";
import assert from "node:assert/strict";
const dom = new JSDOM("<!doctype html><body></body>", { pretendToBeVisual: true });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, Option: dom.window.Option });
const { buildQuestionSet } = await import("/home/claude/repo/AI-Adaptive-Repo/src/main/resources/static/admin/people/question-set-view.js");

const topics = [
  { topic: "Binary Trees", count: 3, questions: [
    { id: 1, type: "MCQ", difficulty: "Easy", question: "What is a leaf?", optionA: "A node with no children", optionB: "Root", optionC: "Edge", optionD: "Path", correctAnswer: "A node with no children", archived: false },
    { id: 2, type: "MCQ", difficulty: "Hard", question: "<img src=x onerror=alert(1)>", optionA: "0", optionB: "1", optionC: "2", optionD: "3", correctAnswer: "B", archived: false },
    { id: 3, type: "TRUEFALSE", difficulty: "Medium", question: "BSTs are always balanced", optionA: "True", optionB: "False", correctAnswer: "False", archived: true }
  ]},
  { topic: "Sorting", count: 2, questions: [
    { id: 4, type: "ESSAY", difficulty: "Hard", question: "Compare quicksort and mergesort", payload: '{"rubric":["Mentions O(n log n)","Mentions stability"]}', archived: false },
    { id: 5, type: "MATCHING", difficulty: null, question: "Match", payload: "x".repeat(500), correctAnswer: "n/a", archived: false }
  ]}
];
let wide = null;
const el = buildQuestionSet(topics, { onWideChange: w => (wide = w) });
document.body.append(el);
const $ = (s) => [...el.querySelectorAll(s)];

assert.equal($("details.qs-topic").length, 2);
assert.equal($("details.qs-topic").filter(d => d.open).length, 0);           // multi-topic: closed by default
assert.equal($(".qs-q").length, 5);
assert.equal($("img").length, 0);                                              // no HTML injection
assert.equal($(".qs-option--correct").length, 3);                              // Q1 (text), Q2 (letter), archived Q3 (True/False text)

const tick = (ms = 200) => new Promise(r => setTimeout(r, ms));
const typed = async (value) => { const i = $(".qs-search__input")[0]; i.value = value; i.dispatchEvent(new window.Event("input")); await tick(); };

// summary
assert.match($(".qs-summary__figures")[0].textContent, /4\s*questions served/);
assert.match($(".qs-summary__figures")[0].textContent, /1\s*archived/);
assert.match($(".qs-count")[0].textContent, /5 questions in 2 topics/);

// search opens matching topics and hides others
await typed("quicksort");
assert.equal($("details.qs-topic").length, 1);
assert.equal($("details.qs-topic")[0].open, true);
assert.match($(".qs-count")[0].textContent, /Showing 1 of 5/);
assert.equal($(".qs-link").find(b => b.textContent === "Clear filters").hidden, false);

// no results -> empty state, then clear restores
await typed("zzzz");
assert.equal($(".qs-empty").length, 1);
$(".qs-empty .qs-link")[0].click();
assert.equal($("details.qs-topic").length, 2);
assert.equal($(".qs-search__input")[0].value, "");

// difficulty chip: Hard => Q2 and Q4; numbering stays stable (Q2 stays Q2)
const hard = $(".qs-chip").find(b => b.textContent.startsWith("Hard"));
hard.click();
assert.equal(hard.getAttribute("aria-pressed"), "true");
assert.deepEqual($(".qs-q__num").map(n => n.textContent), ["Q2", "Q1"]);
hard.click();

// archived toggle hides Q3
const box = $(".qs-check input")[0];
box.checked = false; box.dispatchEvent(new window.Event("change"));
assert.equal($(".qs-q--archived").length, 0);
box.checked = true; box.dispatchEvent(new window.Event("change"));
assert.equal($(".qs-q--archived").length, 1);

// type filter
const sel = $(".qs-select__input")[0];
sel.value = "ESSAY"; sel.dispatchEvent(new window.Event("change"));
assert.equal($(".qs-q").length, 1);
sel.value = ""; sel.dispatchEvent(new window.Event("change"));

// expand / collapse all (details are rendered, so these must flip every <details>)
$(".qs-link").find(b => b.textContent === "Expand all").click();
assert.ok($("details.qs-topic").every(d => d.open));
$(".qs-link").find(b => b.textContent === "Collapse all").click();
assert.ok($("details.qs-topic").every(d => !d.open));

// raw data > 240 chars gets a Show all toggle; list payload does not
const showAll = $(".qs-data .qs-link")[0];
assert.ok(showAll); showAll.click();
assert.equal(showAll.getAttribute("aria-expanded"), "true");
assert.equal($(".qs-data__list li").length, 2);

// wide hook
$(".qs-link").find(b => b.textContent === "Wide view").click();
assert.equal(wide, true);

// empty / single-topic defaults
const one = buildQuestionSet([topics[1]]);
assert.equal(one.querySelector("details").open, true);
assert.equal(buildQuestionSet([]).querySelectorAll("details").length, 0);
console.log("dom OK");
