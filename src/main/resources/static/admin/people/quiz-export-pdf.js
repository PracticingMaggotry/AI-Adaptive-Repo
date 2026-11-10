/**
 * PDF renderer for the quiz export. Needs the jsPDF browser build on globalThis.jspdf
 * (loaded on demand from /admin/vendor/jspdf.umd.min.js by quiz-export.js).
 *
 * Uses jsPDF's built-in Helvetica and Courier, which only cover Western (WinAnsi) characters. Anything outside
 * that set is swapped for a plain equivalent (see `safe`) so it never prints as garbage. The Word export has
 * no such limit, so use it when a quiz contains other scripts or many symbols.
 *
 * Layout is measure-then-draw: each item is first "drawn" in dry-run mode to learn its height, and moved to the
 * next page as a whole if it would not fit, so a question never splits from its choices or answer lines.
 */
import { testHeading, pointsText } from "./quiz-export-model.js";

const PAGE_W = 612, PAGE_H = 792, MARGIN_X = 62, MARGIN_TOP = 58, MARGIN_BOTTOM = 62;
const CONTENT_W = PAGE_W - 2 * MARGIN_X;
const INDENT = 44;
const BODY = 10.5, LEAD = 14.5, CODE = 9, CODE_LEAD = 12.5;
const GREY = [89, 89, 89];

const SWAPS = [
  [/\u2192/g, "->"], [/\u2190/g, "<-"], [/\u2264/g, "<="], [/\u2265/g, ">="], [/\u2260/g, "!="], [/\u2248/g, "~"],
  [/\u221a(?=\w)/g, "sqrt "], [/\u221a/g, "sqrt"], [/\u221e/g, "infinity"], [/\u03c0/g, "pi"], [/\u2212/g, "-"], [/\u2713|\u2714/g, "(check)"],
  [/[\u2018\u2019\u201a]/g, "'"], [/[\u201c\u201d\u201e]/g, '"']
];

/** Replaces characters the built-in PDF fonts cannot draw. Latin-1 letters and symbols pass through unchanged. */
export function safe(value) {
  let s = String(value ?? "").replace(/\t/g, "    ");
  for (const [re, to] of SWAPS) s = s.replace(re, to);
  return s.replace(/[^\x09\x0a\x20-\x7e\xa0-\xff\u2013\u2014\u2022\u2026\u20ac\u2122]/g, "?");
}

export async function renderPdf(quiz, { withKey }) {
  const lib = globalThis.jspdf;
  if (!lib?.jsPDF) throw new Error("The PDF export library is not loaded.");
  const doc = new lib.jsPDF({ unit: "pt", format: "letter", compress: true });
  doc.setProperties({ title: `${quiz.title} - Quiz`, creator: "AI Adaptive Learning" });

  let y = MARGIN_TOP;
  let dry = false;
  const bottom = PAGE_H - MARGIN_BOTTOM;

  const font = (style = "normal", size = BODY, family = "helvetica", color = [0, 0, 0]) => {
    doc.setFont(family, style); doc.setFontSize(size); doc.setTextColor(...color);
  };
  const newPage = () => { if (!dry) { doc.addPage(); } y = MARGIN_TOP; };
  /** Starts a new page when `h` more points do not fit; a no-op while measuring. */
  const need = h => { if (!dry && y + h > bottom) newPage(); };
  const gap = h => { y += h; };

  /** Wrapped text; flows onto new pages line by line. Returns the number of lines. */
  function text(value, x, width, o = {}) {
    const { style = "normal", size = BODY, family = "helvetica", color = [0, 0, 0], lead = LEAD, align = "left" } = o;
    font(style, size, family, color);
    const lines = doc.splitTextToSize(safe(value), width);
    for (const line of lines) {
      need(lead);
      if (!dry) {
        const at = align === "center" ? x + width / 2 : x;
        doc.text(line, at, y + size * 0.85, { align });
      }
      y += lead;
    }
    return lines.length;
  }

  /** A run of label + body on one baseline: bold label in the number column, wrapped body beside it. */
  function numbered(label, body, o = {}) {
    const startY = y;
    need(LEAD);
    font("bold", BODY);
    if (!dry) doc.text(safe(label), MARGIN_X, y + BODY * 0.85);
    const before = y;
    if (body) text(body, MARGIN_X + INDENT, CONTENT_W - INDENT, o);
    else y = before + LEAD;
    return y - startY;
  }

  function rule(x1, x2, at = y, width = 0.6, color = [153, 153, 153]) {
    if (dry) return;
    doc.setDrawColor(...color); doc.setLineWidth(width); doc.line(x1, at, x2, at);
  }

  function code(value, x = MARGIN_X + INDENT) {
    const width = PAGE_W - MARGIN_X - x;
    font("normal", CODE, "courier");
    const lines = String(value).split("\n").flatMap(l => doc.splitTextToSize(safe(l) || " ", width - 12));
    const h = lines.length * CODE_LEAD + 8;
    need(Math.min(h, bottom - MARGIN_TOP));
    if (!dry) { doc.setFillColor(242, 242, 242); doc.rect(x, y, width, h, "F"); }
    font("normal", CODE, "courier");
    let ty = y + 4;
    for (const line of lines) {
      if (!dry) doc.text(line, x + 6, ty + CODE * 0.85);
      ty += CODE_LEAD;
    }
    y += h + 5;
  }

  function stemFlow(parts, first = true) {
    const list = parts?.length ? parts : [{ kind: "text", text: "(empty question text)" }];
    for (const p of list) {
      if (p.kind === "code") code(p.text);
      else text(p.text, MARGIN_X + INDENT, CONTENT_W - INDENT);
    }
  }

  function options(opts) {
    const x = MARGIN_X + INDENT + 16;
    for (const o of opts) {
      need(LEAD);
      font("normal", BODY);
      if (!dry) doc.text(`${o.key}.`, x, y + BODY * 0.85);
      text(o.text, x + 16, PAGE_W - MARGIN_X - x - 16);
    }
  }

  /** A numbered stem: label then the first text part beside it; remaining parts flow underneath. */
  function stemWithLabel(item, label, suffix = "") {
    const parts = item.stem?.length ? item.stem : [{ kind: "text", text: "(empty question text)" }];
    const first = parts[0].kind === "text" ? parts[0].text + suffix : suffix.trim();
    numbered(label, first);
    for (const p of parts.slice(parts[0].kind === "text" ? 1 : 0)) {
      if (p.kind === "code") code(p.text); else text(p.text, MARGIN_X + INDENT, CONTENT_W - INDENT);
    }
  }

  const answerLine = (label, indent = INDENT) => {
    need(LEAD + 6);
    gap(6);
    font("bold", BODY);
    if (!dry) doc.text(label, MARGIN_X + indent, y + BODY * 0.85);
    const w = doc.getTextWidth(label) + 4;
    rule(MARGIN_X + indent + w, MARGIN_X + indent + w + 220, y + BODY + 1);
    y += LEAD + 4;
  };

  const ruledLines = (n, step = 24) => {
    for (let i = 0; i < n; i++) { gap(step); rule(MARGIN_X + INDENT, PAGE_W - MARGIN_X, y); }
    gap(8);
  };

  function matching(item) {
    const colA = CONTENT_W * 0.5, xB = MARGIN_X + colA + 10, wB = CONTENT_W - colA - 10;
    font("bold", BODY);
    need(LEAD * 2);
    if (!dry) { doc.text("Column A", MARGIN_X, y + BODY * 0.85); doc.text("Column B", xB, y + BODY * 0.85); }
    y += LEAD + 2;
    const rows = Math.max(item.columnA.length, item.columnB.length);
    for (let i = 0; i < rows; i++) {
      const a = item.columnA[i], b = item.columnB[i];
      font("normal", BODY);
      const la = a ? doc.splitTextToSize(safe(a.text), colA - 56) : [];
      const lb = b ? doc.splitTextToSize(safe(b.text), wB - 22) : [];
      const h = Math.max(la.length, lb.length, 1) * LEAD + 3;
      need(h);
      if (!dry) {
        if (a) { font("bold", BODY); doc.text(`____ ${a.number}.`, MARGIN_X, y + BODY * 0.85); font("normal", BODY); doc.text(la, MARGIN_X + 52, y + BODY * 0.85, { lineHeightFactor: LEAD / BODY }); }
        if (b) { font("bold", BODY); doc.text(`${b.letter}.`, xB, y + BODY * 0.85); font("normal", BODY); doc.text(lb, xB + 20, y + BODY * 0.85, { lineHeightFactor: LEAD / BODY }); }
      }
      y += h;
    }
    gap(8);
  }

  function wordBank(words) {
    font("normal", BODY);
    const lines = doc.splitTextToSize(safe(words.join("      ")), CONTENT_W - 24);
    const h = 16 + LEAD + lines.length * LEAD + 12;
    need(h);
    if (!dry) {
      doc.setDrawColor(128, 128, 128); doc.setLineWidth(0.8); doc.rect(MARGIN_X, y, CONTENT_W, h);
      font("bold", 9.5); doc.text("WORD BANK", PAGE_W / 2, y + 16, { align: "center" });
      font("normal", BODY);
      lines.forEach((l, i) => doc.text(l, PAGE_W / 2, y + 16 + LEAD + 2 + i * LEAD, { align: "center" }));
    }
    y += h + 10;
  }

  function drawItem(item) {
    switch (item.kind) {
      case "choice": stemWithLabel(item, `____ ${item.number}.`); options(item.options); gap(6); break;
      case "truefalse": stemWithLabel(item, `____ ${item.number}.`); gap(6); break;
      case "matching": matching(item); break;
      case "fill": {
        const body = item.segments.map(s => (s.blank == null ? s.text : `${s.tag ? `${s.tag} ` : ""}______________`)).join("");
        numbered(`${item.number}.`, body, { lead: LEAD + 3 });
        gap(6);
        break;
      }
      case "concept":
        stemWithLabel(item, `${item.number}.`);
        item.clues.forEach((c, i) => {
          const x = MARGIN_X + INDENT + 16;
          need(LEAD); font("normal", BODY);
          if (!dry) doc.text(`(${String.fromCharCode(97 + i)})`, x, y + BODY * 0.85);
          text(c, x + 20, PAGE_W - MARGIN_X - x - 20);
        });
        answerLine("Answer:");
        break;
      case "sorting": {
        for (const p of item.stem || []) { if (p.kind === "code") code(p.text); else text(p.text, MARGIN_X, CONTENT_W); }
        gap(4);
        font("bold", BODY);
        need(LEAD);
        if (!dry) item.categories.forEach((c, i) => doc.text(safe(`${c.letter}. ${c.name}`), MARGIN_X + (i ? CONTENT_W * 0.5 : 0), y + BODY * 0.85));
        y += LEAD + 4;
        for (const i of item.items) numbered(`____ ${i.number}.`, i.text);
        gap(6);
        break;
      }
      case "solve":
        stemWithLabel(item, `${item.number}.`);
        gap(4); text("Solution:", MARGIN_X + INDENT, CONTENT_W - INDENT, { style: "italic", size: 9.5, color: GREY });
        ruledLines(4);
        answerLine("Answer:");
        gap(4);
        break;
      case "essay":
        stemWithLabel(item, `${item.number}.`, `  (${pointsText(item.points)})`);
        ruledLines(Math.max(6, item.points * 3), 25);
        break;
      default: break;
    }
  }

  /** Height of whatever `fn` draws, without drawing it. */
  function measure(fn) {
    const saved = y, wasDry = dry;
    dry = true; y = 0;
    fn();
    const h = y;
    dry = wasDry; y = saved;
    return h;
  }

  /** Draws `fn` as one unit: moves to a fresh page first when it would not fit in the space left. */
  function together(fn) {
    const h = measure(fn);
    if (y + h > bottom && h < bottom - MARGIN_TOP) newPage();
    fn();
  }

  /* ----- header ----- */
  font("bold", 19);
  text(quiz.title, MARGIN_X, CONTENT_W, { style: "bold", size: 19, lead: 24, align: "center" });
  text("Q U I Z", MARGIN_X, CONTENT_W, { size: 10, color: GREY, align: "center", lead: 16 });
  gap(10);
  const field = (label, x, w) => {
    font("bold", BODY);
    if (!dry) doc.text(label, x, y + BODY * 0.85);
    const lw = doc.getTextWidth(label) + 4;
    rule(x + lw, x + lw + w, y + BODY + 1);
  };
  field("Name:", MARGIN_X, 250); field("Section:", MARGIN_X + 340, 80);
  gap(26);
  field("Date:", MARGIN_X, 110);
  field("Score:", MARGIN_X + 170, 50);
  font("bold", BODY); doc.text(`/ ${quiz.total}`, MARGIN_X + 170 + doc.getTextWidth("Score:") + 4 + 56, y + BODY * 0.85);
  gap(24);
  rule(MARGIN_X, PAGE_W - MARGIN_X, y, 1.2, [0, 0, 0]);
  gap(12);
  text(`General directions: Read each item carefully and answer every item. Total: ${pointsText(quiz.total)}.`, MARGIN_X, CONTENT_W, { style: "italic" });

  /* ----- tests ----- */
  const testHead = test => {
    gap(14);
    font("bold", 12);
    need(LEAD * 3);
    const label = safe(testHeading(test).toUpperCase());
    const labelWidth = doc.getTextWidth(label);   // measured while the bold heading font is still set
    if (!dry) {
      doc.text(label, MARGIN_X, y + 12 * 0.85);
      font("normal", 10.5, "helvetica", GREY);
      doc.text(`(${pointsText(test.points)})`, MARGIN_X + labelWidth + 8, y + 12 * 0.85);
    }
    y += 18;
    text(test.directions, MARGIN_X, CONTENT_W, { style: "italic" });
    gap(6);
    if (test.bank) wordBank(test.bank);
  };

  for (const test of quiz.tests) {
    // A test heading is never left alone at the bottom of a page: it moves with its first item.
    together(() => { testHead(test); drawItem(test.items[0]); });
    for (const item of test.items.slice(1)) together(() => drawItem(item));
  }

  /* ----- answer key ----- */
  const keyEntry = a => {
    numbered(`${a.label}.`, a.text, { lead: LEAD - 1 });
    if (a.note) text(`Solution: ${a.note}`, MARGIN_X + INDENT, CONTENT_W - INDENT, { style: "italic", size: 9.5, color: GREY, lead: 13 });
    for (const line of a.lines || []) text(`\u2022  ${line}`, MARGIN_X + INDENT + 14, CONTENT_W - INDENT - 14, { size: 9.5, lead: 13 });
  };
  const keyHead = test => {
    gap(10);
    font("bold", 11.5);
    need(LEAD * 3);
    const label = safe(testHeading(test));
    const labelWidth = doc.getTextWidth(label);
    if (!dry) {
      doc.text(label, MARGIN_X, y + 11.5 * 0.85);
      font("normal", 10, "helvetica", GREY);
      doc.text(`(${pointsText(test.points)})`, MARGIN_X + labelWidth + 8, y + 11.5 * 0.85);
    }
    y += LEAD + 4;
  };

  if (withKey) {
    doc.addPage(); y = MARGIN_TOP;
    text("ANSWER KEY", MARGIN_X, CONTENT_W, { style: "bold", size: 17, lead: 22, align: "center" });
    text(`${quiz.title}  |  ${pointsText(quiz.total)}`, MARGIN_X, CONTENT_W, { size: 10, color: GREY, align: "center", lead: 18 });
    for (const test of quiz.tests) {
      const entries = test.items.flatMap(i => i.answers);
      together(() => { keyHead(test); keyEntry(entries[0]); });   // heading always travels with its first entry
      for (const a of entries.slice(1)) together(() => keyEntry(a));
    }
  }

  /* ----- footer on every page ----- */
  const pages = doc.getNumberOfPages();
  for (let i = 1; i <= pages; i++) {
    doc.setPage(i);
    font("normal", 8.5, "helvetica", GREY);
    doc.text(safe(`${quiz.title}  \u00b7  Page ${i} of ${pages}`), PAGE_W / 2, PAGE_H - 34, { align: "center" });
  }
  return doc.output("blob");
}
