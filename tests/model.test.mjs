import assert from "node:assert/strict";
import * as m from "/home/claude/repo/AI-Adaptive-Repo/src/main/resources/static/admin/people/question-set-model.js";

const topics = [
  { topic: "Binary Trees", count: 3, questions: [
    { id: 1, type: "MCQ", difficulty: "easy", question: "What is a leaf?", optionA: "A node with no children", optionB: "Root", optionC: "Edge", optionD: "Path", correctAnswer: "A node with no children", archived: false },
    { id: 2, type: "MCQ", difficulty: "Hard", question: "Height of a single node tree?", optionA: "0", optionB: "1", optionC: "2", optionD: "3", correctAnswer: "B", archived: false },
    { id: 3, type: "TRUEFALSE", difficulty: "Medium", question: "BSTs are always balanced", optionA: "True", optionB: "False", correctAnswer: "False", archived: true }
  ]},
  { topic: "Sorting", count: 2, questions: [
    { id: 4, type: "ESSAY", difficulty: "Hard", question: "Compare quicksort and mergesort", payload: '{"rubric":["Mentions O(n log n)","Mentions stability"]}', archived: false },
    { id: 5, type: "MATCHING", difficulty: null, question: "Match", payload: '{"correctPairs":[["a","1"],["b","2"]]}', archived: false }
  ]}
];

const s = m.summarize(topics);
assert.equal(s.total, 5); assert.equal(s.live, 4); assert.equal(s.archived, 1);
assert.deepEqual(s.byDifficulty, { Easy: 1, Medium: 0, Hard: 2, Unknown: 1 });
assert.equal(s.byType.MCQ, 2);

assert.equal(m.correctOptionKey(topics[0].questions[0]), "A");   // text match
assert.equal(m.correctOptionKey(topics[0].questions[1]), "B");   // bare letter
assert.equal(m.correctOptionKey(topics[0].questions[2]), "B");   // True/False text
assert.equal(m.correctOptionKey({ optionA: "x", optionB: "x", correctAnswer: "x" }), null); // ambiguous text, no letter
assert.equal(m.correctOptionKey({ optionA: "x", correctAnswer: "" }), null);

const f = (o) => m.filterTopics(topics, o);
assert.equal(f({}).length, 2);
assert.equal(f({ showArchived: false })[0].questions.length, 2);
assert.equal(f({ difficulties: new Set(["Hard"]) }).flatMap(t => t.questions).length, 2);
assert.equal(f({ query: "quicksort" })[0].topic, "Sorting");
assert.equal(f({ query: "sorting" })[0].questions.length, 2);           // topic-name match keeps its questions
assert.equal(f({ query: "zzz" }).length, 0);
assert.equal(f({ types: new Set(["ESSAY"]) })[0].count, 1);
assert.equal(m.isFiltering({}), false);
assert.equal(m.isFiltering({ query: " " }), false);
assert.equal(m.isFiltering({ showArchived: false }), true);

assert.deepEqual(m.payloadView(topics[1].questions[0]), { kind: "list", label: "Rubric", items: ["Mentions O(n log n)", "Mentions stability"] });
assert.equal(m.payloadView(topics[1].questions[1]).items[0], "a \u2192 1");
assert.equal(m.payloadView({ type: "ESSAY", payload: '{"rubric":["cut o…' }).kind, "raw");   // truncated JSON
assert.equal(m.payloadView({ type: "MCQ" }), null);
console.log("model OK");
