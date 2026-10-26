/**
 * Question-set view for the user drawer (People > Users > :id).
 *
 * Layout, in reading order:
 *   1. Summary   - totals and a difficulty-mix bar, visible without any interaction.
 *   2. Toolbar   - search, difficulty chips, type filter, archived toggle, expand/collapse, wide view.
 *                  Sticks to the top of the drawer so it stays reachable while scrolling a long set.
 *   3. Topics    - one native <details> per topic (one level only, never nested). Each header carries
 *                  its count and difficulty mix so the closed state is still informative.
 *   4. Questions - every question is a card: badges, stem, options with the correct one marked.
 *                  Nothing inside a card is collapsed except long raw data.
 *
 * All dynamic text goes in through textContent. Colour is never the only signal: every badge, chip,
 * bar segment and the correct-answer marker also carries text.
 */
import {
  DIFFICULTY_ORDER, summarize, topicBreakdown, filterTopics, isFiltering,
  optionsOf, correctOptionKey, payloadView, typeLabel, normalizeDifficulty
} from "./question-set-model.js";

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

function optionList(q) {
  const options = optionsOf(q);
  if (!options.length) return null;
  const correct = correctOptionKey(q);
  const ul = node("ul", null, "qs-options");
  for (const o of options) {
    const isCorrect = o.key === correct;
    const li = node("li", null, `qs-option${isCorrect ? " qs-option--correct" : ""}`);
    li.append(node("span", o.key, "qs-option__key"), node("span", o.text, "qs-option__text"));
    if (isCorrect) {
      li.append(node("span", "Correct", "qs-option__flag"));
    }
    ul.append(li);
  }
  return { ul, resolved: correct != null };
}

function dataBlock(q) {
  const view = payloadView(q);
  if (!view) return null;
  const box = node("div", null, "qs-data");
  if (view.kind === "list") {
    box.append(node("p", view.label, "qs-data__label"));
    const ul = node("ul", null, "qs-data__list");
    for (const item of view.items) ul.append(node("li", item));
    box.append(ul);
    return box;
  }
  box.append(node("p", "Data", "qs-data__label"));
  const pre = node("pre", view.text, "qs-data__raw qs-data__raw--clamped");
  box.append(pre);
  if (view.text.length > 240 || view.text.split("\n").length > 6) {
    const toggle = node("button", "Show all", "qs-link");
    toggle.type = "button";
    toggle.setAttribute("aria-expanded", "false");
    toggle.addEventListener("click", () => {
      const open = pre.classList.toggle("qs-data__raw--clamped") === false;
      toggle.textContent = open ? "Show less" : "Show all";
      toggle.setAttribute("aria-expanded", String(open));
    });
    box.append(toggle);
  } else {
    pre.classList.remove("qs-data__raw--clamped");
  }
  return box;
}

function questionCard(q, position) {
  const li = node("li", null, `qs-q${q.archived ? " qs-q--archived" : ""}`);
  const head = node("div", null, "qs-q__head");
  head.append(node("span", `Q${position}`, "qs-q__num"));
  head.append(badge(typeLabel(q.type), "type"));
  const diff = normalizeDifficulty(q.difficulty);
  head.append(badge(diff === "Unknown" ? "No difficulty" : diff, DIFF_CLASS[diff]));
  if (q.archived) head.append(badge("Archived", "archived"));
  if (q.id != null) head.append(node("span", `ID ${q.id}`, "qs-q__id"));
  li.append(head);

  li.append(node("p", q.question || "(empty question text)", "qs-q__stem"));

  const options = optionList(q);
  if (options) li.append(options.ul);
  // Answer text is shown only when it could not be matched to an option above.
  if (q.correctAnswer && !(options && options.resolved)) {
    const p = node("p", null, "qs-q__answer");
    p.append(node("strong", "Answer: "), document.createTextNode(q.correctAnswer));
    li.append(p);
  }
  const data = dataBlock(q);
  if (data) li.append(data);
  return li;
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
    return details;
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
