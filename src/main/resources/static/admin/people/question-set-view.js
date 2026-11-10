/**
 * Question-set view for the user drawer (People > Users > :id).
 *
 * Layout, in reading order:
 *   1. Summary   - totals and a difficulty-mix bar, visible without any interaction.
 *   2. Toolbar   - search, difficulty chips, type filter, archived toggle, expand/collapse, wide view.
 *                  Sticks to the top of the drawer so it stays reachable while scrolling a long set.
 *   3. Topics    - one native <details> per topic (one level only, never nested). Each header carries
 *                  its count and difficulty mix so the closed state is still informative.
 *   4. Questions - every question is a card with the same labelled sections, all visible by default:
 *                  Question, the type's answer structure (choices, pairs, blanks, sort buckets, clues,
 *                  rubric), Hint, Feedback (the explanation students see after answering).
 *                  The stored JSON sits under one "Raw data" disclosure, closed until clicked.
 *   Each topic header also carries an Export menu (Word or PDF quiz, optional answer key); see quiz-export.js.
 *
 * All dynamic text goes in through textContent. Colour is never the only signal: every badge, chip,
 * bar segment and the correct-answer marker also carries text.
 */
import {
  DIFFICULTY_ORDER, summarize, topicBreakdown, filterTopics, isFiltering,
  describeQuestion, rawView, typeLabel, questionTypeLabel, normalizeDifficulty
} from "./question-set-model.js";
import { exportTopic, hasExportableQuestions } from "./quiz-export.js";

function node(tag, text, className) {
  const el = document.createElement(tag);
  if (text != null) el.textContent = String(text);
  if (className) el.className = className;
  return el;
}

function plural(n, word) {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

const DIFF_CLASS = { Easy: "easy", Medium: "medium", Hard: "hard", Unknown: "unknown" };

function badge(text, modifier) {
  return node("span", text, `qs-badge qs-badge--${modifier}`);
}

function difficultyBar(counts, total, label) {
  const bar = node("div", null, "qs-bar");
  bar.setAttribute("role", "img");
  bar.setAttribute("aria-label", label);
  for (const d of [...DIFFICULTY_ORDER, "Unknown"]) {
    if (!counts[d]) continue;
    const seg = node("span", null, `qs-bar__seg qs-bar__seg--${DIFF_CLASS[d]}`);
    seg.style.flexGrow = String(counts[d]);
    seg.title = `${d}: ${counts[d]} of ${total}`;
    bar.append(seg);
  }
  if (!total) bar.classList.add("qs-bar--empty");
  return bar;
}

function mixText(counts) {
  return [...DIFFICULTY_ORDER, "Unknown"].filter(d => counts[d]).map(d => `${counts[d]} ${d}`).join(", ") || "none";
}

/* ---------- summary ---------- */

function buildSummary(stats) {
  const wrap = node("div", null, "qs-summary");
  const figures = node("dl", null, "qs-summary__figures");
  const fig = (value, label) => {
    const box = node("div", null, "qs-summary__fig");
    box.append(node("dd", value, "qs-summary__value"), node("dt", label, "qs-summary__label"));
    figures.append(box);
  };
  fig(stats.live, stats.live === 1 ? "question served" : "questions served");
  fig(stats.topics, stats.topics === 1 ? "topic" : "topics");
  if (stats.archived) fig(stats.archived, "archived");
  wrap.append(figures);

  wrap.append(difficultyBar(stats.byDifficulty, stats.live, `Difficulty mix: ${mixText(stats.byDifficulty)}`));
  const legend = node("ul", null, "qs-legend");
  for (const d of [...DIFFICULTY_ORDER, "Unknown"]) {
    if (!stats.byDifficulty[d]) continue;
    const li = node("li", null, "qs-legend__item");
    li.append(node("span", null, `qs-swatch qs-swatch--${DIFF_CLASS[d]}`), document.createTextNode(`${d} ${stats.byDifficulty[d]}`));
    legend.append(li);
  }
  wrap.append(legend);
  if (stats.archived) {
    wrap.append(node("p", "Archived questions were kept because a student report points at them. Students are no longer served these.", "qs-note"));
  }
  return wrap;
}

/* ---------- question card ---------- */

function section(label, modifier) {
  const box = node("section", null, `qs-sec qs-sec--${modifier}`);
  box.append(node("h4", label, "qs-sec__label"));
  return box;
}

function empty(text) {
  return node("p", text, "qs-none");
}

function stemBlock(parts) {
  const box = section("Question", "stem");
  if (!parts.length) { box.append(empty("(empty question text)")); return box; }
  for (const part of parts) {
    if (part.kind === "code") {
      const pre = node("pre", null, "qs-code");
      if (part.lang) pre.dataset.lang = part.lang;
      pre.append(node("code", part.text));
      box.append(pre);
    } else {
      box.append(node("p", part.text, "qs-q__stem"));
    }
  }
  return box;
}

function choicesBlock(b) {
  const box = section(b.label, "choices");
  const ul = node("ul", null, "qs-options");
  for (const o of b.options) {
    const li = node("li", null, `qs-option${o.correct ? " qs-option--correct" : ""}`);
    li.append(node("span", o.key, "qs-option__key"), node("span", o.text, "qs-option__text"));
    if (o.correct) li.append(node("span", "Correct", "qs-option__flag"));
    ul.append(li);
  }
  box.append(ul);
  if (!b.resolved) box.append(node("p", "The stored correct answer does not match exactly one choice.", "qs-note"));
  return box;
}

function matchingBlock(b) {
  const box = section(b.label, "matching");
  const ul = node("ul", null, "qs-pairs");
  for (const p of b.pairs) {
    const li = node("li", null, "qs-pair");
    li.append(node("span", p.left, "qs-pair__side"), node("span", "\u2192", "qs-pair__arrow"), node("span", p.right, "qs-pair__side qs-pair__side--right"));
    ul.append(li);
  }
  box.append(ul);
  if (b.extras.length) box.append(node("p", `Unused right-hand items: ${b.extras.join(" \u00b7 ")}`, "qs-note"));
  return box;
}

function fillBlankBlock(b) {
  const box = section(b.label, "fillblank");
  const p = node("p", null, "qs-excerpt");
  for (const seg of b.segments) {
    if (seg.blank == null) { p.append(document.createTextNode(seg.text)); continue; }
    const mark = node("mark", null, "qs-blank");
    mark.append(node("span", seg.blank, "qs-blank__id"), document.createTextNode(seg.answer));
    p.append(mark);
  }
  box.append(p);
  const meta = [b.mode, b.blanks.length ? plural(b.blanks.length, "blank") : ""].filter(Boolean).join(" \u00b7 ");
  if (meta) box.append(node("p", meta, "qs-note"));
  return box;
}

function sortingBlock(b) {
  const box = section(b.label, "sorting");
  const grid = node("div", null, "qs-buckets");
  for (const c of b.categories) {
    const col = node("div", null, "qs-bucket");
    col.append(node("p", c.name, "qs-bucket__name"));
    const ul = node("ul", null, "qs-bucket__items");
    for (const item of c.items) ul.append(node("li", item));
    col.append(ul);
    grid.append(col);
  }
  box.append(grid);
  return box;
}

function conceptBlock(b) {
  const box = section(b.label, "concept");
  if (b.clues.length) {
    const ol = node("ol", null, "qs-clues");
    for (const c of b.clues) ol.append(node("li", c));
    box.append(ol);
  }
  const answer = node("p", null, "qs-answer");
  answer.append(node("span", "Answer", "qs-answer__label"), node("span", b.answer || "(none stored)", "qs-answer__text"));
  box.append(answer);
  if (b.accepted.length) box.append(node("p", `Also accepted: ${b.accepted.join(" \u00b7 ")}`, "qs-note"));
  return box;
}

function listBlock(b) {
  const box = section(b.label, "list");
  const ul = node("ul", null, "qs-data__list");
  for (const item of b.items) ul.append(node("li", item));
  box.append(ul);
  return box;
}

function metaBlock(b) {
  const p = node("p", null, "qs-meta");
  p.append(node("span", `${b.label}: `, "qs-meta__label"), document.createTextNode(b.value));
  return p;
}

const BLOCKS = {
  choices: choicesBlock, matching: matchingBlock, fillblank: fillBlankBlock,
  sorting: sortingBlock, concept: conceptBlock, list: listBlock
};

function textSection(label, modifier, value, missing) {
  const box = section(label, modifier);
  box.append(value ? node("p", value, "qs-text") : empty(missing));
  return box;
}

function rawBlock(q) {
  const details = node("details", null, "qs-raw");
  details.append(node("summary", "Raw data", "qs-raw__summary"));
  details.append(node("pre", rawView(q), "qs-raw__code"));
  return details;
}

function questionCard(q, position) {
  const view = describeQuestion(q);
  const li = node("li", null, `qs-q${q.archived ? " qs-q--archived" : ""}`);
  const head = node("div", null, "qs-q__head");
  head.append(node("span", `Q${position}`, "qs-q__num"));
  head.append(badge(questionTypeLabel(q), "type"));
  const diff = normalizeDifficulty(q.difficulty);
  head.append(badge(diff === "Unknown" ? "No difficulty" : diff, DIFF_CLASS[diff]));
  if (q.archived) head.append(badge("Archived", "archived"));
  if (q.id != null) head.append(node("span", `ID ${q.id}`, "qs-q__id"));
  li.append(head);

  li.append(stemBlock(view.stem));

  const metas = [];
  for (const b of view.blocks) {
    if (b.kind === "meta") { metas.push(metaBlock(b)); continue; }
    const build = BLOCKS[b.kind];
    if (build) li.append(build(b));
  }
  // Stored answer text, only when no block above could present it (e.g. a choice that cannot be resolved).
  if (view.fallbackAnswer) {
    const box = section("Answer", "answer");
    box.append(node("p", view.fallbackAnswer, "qs-text"));
    li.append(box);
  }
  if (view.worked) li.append(textSection("Worked solution", "worked", view.worked, ""));
  for (const m of metas) li.append(m);

  li.append(textSection("Hint", "hint", view.hint, "No hint stored."));
  li.append(textSection("Feedback", "feedback", view.feedback, "No feedback stored."));
  li.append(rawBlock(q));
  return li;
}

/* ---------- export menu ---------- */

function exportMenu(topic) {
  const details = node("details", null, "qs-export");
  const summary = node("summary", "Export", "qs-export__btn");
  summary.setAttribute("aria-label", `Export ${topic.topic} as a quiz`);
  details.append(summary);

  const panel = node("div", null, "qs-export__panel");
  panel.append(node("p", "Export as a quiz", "qs-export__title"));

  const keyLabel = node("label", null, "qs-check");
  const keyBox = node("input");
  keyBox.type = "checkbox";
  keyBox.checked = true;
  keyLabel.append(keyBox, document.createTextNode("Include answer key (last pages)"));
  panel.append(keyLabel);

  const status = node("p", null, "qs-export__status");
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");

  const buttons = [];
  const row = node("div", null, "qs-export__row");
  const make = (label, format) => {
    const b = node("button", label, "qs-export__go");
    b.type = "button";
    b.addEventListener("click", async () => {
      buttons.forEach(x => { x.disabled = true; });
      status.classList.remove("qs-export__status--error");
      status.textContent = "Preparing file\u2026";
      try {
        const { name, quiz } = await exportTopic(topic, { format, withKey: keyBox.checked });
        status.textContent = `Downloaded ${name}` + (quiz.skipped ? ` (${plural(quiz.skipped, "question")} could not be laid out and left out)` : "");
      } catch (error) {
        status.classList.add("qs-export__status--error");
        status.textContent = `Export failed: ${error?.message || "unknown error"}`;
      } finally {
        buttons.forEach(x => { x.disabled = false; });
      }
    });
    buttons.push(b);
    row.append(b);
  };
  make("Word (.docx)", "docx");
  make("PDF (.pdf)", "pdf");
  panel.append(row, status);
  details.append(panel);
  return details;
}

/* ---------- main ---------- */

/**
 * @param {Array} topics   `topics` from GET /api/admin/users/{id}/questions
 * @param {object} [hooks]
 * @param {(wide: boolean) => void} [hooks.onWideChange]  lets the drawer widen itself
 * @returns {HTMLElement}
 */
export function buildQuestionSet(topics, { onWideChange } = {}) {
  const all = Array.isArray(topics) ? topics : [];
  const stats = summarize(all);
  const root = node("div", null, "qs");
  root.append(buildSummary(stats));

  // Stable numbering: position inside the topic's full list, so Q3 stays Q3 while filtering.
  const positions = new Map();
  for (const t of all) (t.questions || []).forEach((q, i) => positions.set(q, i + 1));

  const filters = { query: "", difficulties: new Set(), types: new Set(), showArchived: true };
  const openTopics = new Set(all.length === 1 ? [all[0].topic] : []);
  let wide = false;

  /* toolbar */
  const toolbar = node("div", null, "qs-toolbar");
  toolbar.setAttribute("role", "group");
  toolbar.setAttribute("aria-label", "Question set filters");

  const searchLabel = node("label", null, "qs-search");
  searchLabel.append(node("span", "Search questions", "qs-visually-hidden"));
  const search = node("input", null, "qs-search__input");
  search.type = "search";
  search.placeholder = "Search question, option, answer or topic";
  search.autocomplete = "off";
  searchLabel.append(search);
  toolbar.append(searchLabel);

  const filterRow = node("div", null, "qs-toolbar__row");
  const chipGroup = node("div", null, "qs-chips");
  chipGroup.setAttribute("role", "group");
  chipGroup.setAttribute("aria-label", "Filter by difficulty");
  const chipButtons = [];
  for (const d of [...DIFFICULTY_ORDER, "Unknown"]) {
    if (!stats.byDifficulty[d]) continue;
    const chip = node("button", null, `qs-chip qs-chip--${DIFF_CLASS[d]}`);
    chip.type = "button";
    chip.setAttribute("aria-pressed", "false");
    chip.append(document.createTextNode(d === "Unknown" ? "No difficulty" : d), node("span", stats.byDifficulty[d], "qs-chip__count"));
    chip.addEventListener("click", () => {
      if (filters.difficulties.has(d)) filters.difficulties.delete(d); else filters.difficulties.add(d);
      chip.setAttribute("aria-pressed", String(filters.difficulties.has(d)));
      renderList();
    });
    chipButtons.push(chip);
    chipGroup.append(chip);
  }
  filterRow.append(chipGroup);

  let typeSelect = null;
  const typeKeys = Object.keys(stats.byType);
  if (typeKeys.length > 1) {
    const label = node("label", null, "qs-select");
    label.append(node("span", "Question type", "qs-visually-hidden"));
    typeSelect = node("select", null, "qs-select__input");
    typeSelect.append(new Option("All types", ""));
    for (const key of typeKeys) typeSelect.append(new Option(`${typeLabel(key)} (${stats.byType[key]})`, key));
    typeSelect.addEventListener("change", () => {
      filters.types = typeSelect.value ? new Set([typeSelect.value]) : new Set();
      renderList();
    });
    label.append(typeSelect);
    filterRow.append(label);
  }

  let archivedBox = null;
  if (stats.archived) {
    const label = node("label", null, "qs-check");
    archivedBox = node("input");
    archivedBox.type = "checkbox";
    archivedBox.checked = true;
    archivedBox.addEventListener("change", () => { filters.showArchived = archivedBox.checked; renderList(); });
    label.append(archivedBox, document.createTextNode(`Show archived (${stats.archived})`));
    filterRow.append(label);
  }
  toolbar.append(filterRow);

  const actionRow = node("div", null, "qs-toolbar__row qs-toolbar__row--actions");
  const count = node("p", null, "qs-count");
  count.setAttribute("role", "status");
  count.setAttribute("aria-live", "polite");
  const small = (label, onClick) => {
    const b = node("button", label, "qs-link");
    b.type = "button";
    b.addEventListener("click", onClick);
    return b;
  };
  const clear = small("Clear filters", () => resetFilters());
  clear.hidden = true;
  const expandAll = small("Expand all", () => setAllOpen(true));
  const collapseAll = small("Collapse all", () => setAllOpen(false));
  const widen = small("Wide view", () => {
    wide = !wide;
    widen.textContent = wide ? "Narrow view" : "Wide view";
    widen.setAttribute("aria-pressed", String(wide));
    root.classList.toggle("qs--wide", wide);
    if (typeof onWideChange === "function") onWideChange(wide);
  });
  widen.setAttribute("aria-pressed", "false");
  actionRow.append(count, clear, expandAll, collapseAll);
  if (typeof onWideChange === "function") actionRow.append(widen);
  toolbar.append(actionRow);
  root.append(toolbar);

  const list = node("div", null, "qs-topics");
  root.append(list);

  /* behaviour */
  function resetFilters() {
    filters.query = "";
    filters.difficulties = new Set();
    filters.types = new Set();
    filters.showArchived = true;
    search.value = "";
    if (typeSelect) typeSelect.value = "";
    if (archivedBox) archivedBox.checked = true;
    chipButtons.forEach(c => c.setAttribute("aria-pressed", "false"));
    renderList();
  }

  function setAllOpen(open) {
    for (const d of list.querySelectorAll("details.qs-topic")) {
      d.open = open;
      if (open) openTopics.add(d.dataset.topic); else openTopics.delete(d.dataset.topic);
    }
  }

  function topicBlock(t, forceOpen) {
    const details = node("details", null, "qs-topic");
    details.dataset.topic = t.topic;
    details.open = forceOpen || openTopics.has(t.topic);
    const summary = node("summary", null, "qs-topic__summary");
    summary.addEventListener("click", () => {
      // The click fires before the browser toggles `open`, so the current value is the pre-click state.
      if (details.open) openTopics.delete(t.topic); else openTopics.add(t.topic);
    });

    const title = node("span", null, "qs-topic__title");
    title.append(node("span", t.topic, "qs-topic__name"), node("span", plural(t.count, "question"), "qs-topic__count"));
    summary.append(title);

    const mix = topicBreakdown(t);
    summary.append(difficultyBar(mix.counts, mix.live, `${t.topic}: ${mixText(mix.counts)}`));
    const text = [...DIFFICULTY_ORDER, "Unknown"].filter(d => mix.counts[d])
      .map(d => `${d === "Unknown" ? "No difficulty" : d} ${mix.counts[d]}`).join(" \u00b7 ");
    const meta = [text, mix.archived ? `${mix.archived} archived` : ""].filter(Boolean).join(" \u00b7 ");
    summary.append(node("span", meta, "qs-topic__mix"));
    details.append(summary);

    const ol = node("ol", null, "qs-qlist");
    for (const q of t.questions) ol.append(questionCard(q, positions.get(q) || 0));
    details.append(ol);

    // The menu sits beside the header, not inside the <summary>, so using it never opens or closes the topic.
    // It exports the whole topic, whatever the search and filters currently hide.
    const wrap = node("div", null, "qs-topicwrap");
    wrap.append(details);
    const full = all.find(x => x.topic === t.topic) || t;
    if (hasExportableQuestions(full)) wrap.append(exportMenu(full));
    return wrap;
  }

  function renderList() {
    filters.query = search.value;
    const filtering = isFiltering(filters);
    const shown = filterTopics(all, filters);
    const shownCount = shown.reduce((n, t) => n + t.questions.length, 0);
    list.replaceChildren();

    count.textContent = filtering
      ? `Showing ${shownCount} of ${stats.total} questions in ${plural(shown.length, "topic")}`
      : `${plural(stats.total, "question")} in ${plural(stats.topics, "topic")}`;
    clear.hidden = !filtering;

    if (!shown.length) {
      const empty = node("div", null, "qs-empty");
      empty.append(node("p", "No questions match these filters."));
      const again = small("Clear filters", () => resetFilters());
      empty.append(again);
      list.append(empty);
      return;
    }
    // While searching or filtering, open every matching topic so results are visible without extra clicks.
    for (const t of shown) list.append(topicBlock(t, filtering));
  }

  // Close an open export menu on an outside click or Escape. The listener removes itself once the view is gone.
  const closeMenus = e => {
    if (!root.isConnected) { document.removeEventListener("click", closeMenus); return; }
    for (const m of root.querySelectorAll("details.qs-export[open]")) if (!m.contains(e.target)) m.open = false;
  };
  document.addEventListener("click", closeMenus);
  root.addEventListener("keydown", e => {
    if (e.key !== "Escape") return;
    const open = root.querySelector("details.qs-export[open]");
    if (open) { e.stopPropagation(); open.open = false; open.querySelector("summary")?.focus(); }
  });

  let timer = null;
  search.addEventListener("input", () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(renderList, 120);
  });
  search.addEventListener("keydown", e => {
    if (e.key === "Escape" && search.value) { e.preventDefault(); e.stopPropagation(); search.value = ""; renderList(); }
  });

  renderList();
  return root;
}
