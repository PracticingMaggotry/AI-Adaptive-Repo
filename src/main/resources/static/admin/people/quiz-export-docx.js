/**
 * Word renderer for the quiz export. Needs the `docx` browser build on globalThis.docx
 * (loaded on demand from /admin/vendor/docx.umd.min.js by quiz-export.js).
 */
import { testHeading, pointsText } from "./quiz-export-model.js";

const FONT = "Calibri";
const CODE_FONT = "Consolas";
const BODY = 22;       // half-points: 11 pt
const CODE = 19;
const INDENT = 900;    // twips: width of the "____ 1." answer-blank + number column
const GREY = "595959";

export async function renderDocx(quiz, { withKey }) {
  const d = globalThis.docx;
  if (!d) throw new Error("The Word export library is not loaded.");
  const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, WidthType, BorderStyle, AlignmentType,
    TabStopType, Tab, ShadingType, Footer, PageNumber } = d;

  const none = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
  const noBorders = { top: none, bottom: none, left: none, right: none };
  const box = { style: BorderStyle.SINGLE, size: 6, color: "808080" };

  /** Text with "\n" turned into line breaks. */
  const runs = (text, o = {}) => String(text ?? "").split("\n").map((line, i) =>
    new TextRun({ text: line, break: i ? 1 : 0, size: BODY, font: FONT, ...o }));

  const para = (children, o = {}) => new Paragraph({ children, spacing: { after: 80 }, ...o });
  const textPara = (text, o = {}, run = {}) => para(runs(text, run), o);
  const blankLine = (gap = 300, keep = true) => new Paragraph({
    spacing: { before: gap, after: 0 }, keepNext: keep,
    border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: "999999", space: 1 } },
    children: [new TextRun({ text: "", size: BODY })]
  });

  const codeParas = text => String(text).replace(/\t/g, "    ").split("\n").map((line, i, all) => new Paragraph({
    indent: { left: INDENT },
    spacing: { before: i === 0 ? 40 : 0, after: i === all.length - 1 ? 100 : 0, line: 252 },
    shading: { type: ShadingType.CLEAR, fill: "F2F2F2", color: "auto" },
    keepNext: true,   // also keeps the snippet with the choices that follow it
    children: [new TextRun({ text: line === "" ? " " : line, font: CODE_FONT, size: CODE })]
  }));

  /** The numbered stem: blank + number + first text part, then any code/text parts underneath. */
  function stem(item, { blank = true, suffix = "" } = {}) {
    const label = `${blank ? "____ " : ""}${item.number}.`;
    const parts = item.stem?.length ? item.stem : [{ kind: "text", text: "(empty question text)" }];
    const out = [];
    const first = parts[0].kind === "text" ? parts[0].text : "";
    out.push(new Paragraph({
      spacing: { before: 140, after: 80 }, keepNext: true,
      indent: { left: INDENT, hanging: INDENT }, tabStops: [{ type: TabStopType.LEFT, position: INDENT }],
      children: [new TextRun({ children: [label, new Tab()], size: BODY, font: FONT, bold: true }),
        ...runs(first), ...(suffix ? [new TextRun({ text: suffix, size: BODY, font: FONT, italics: true, color: GREY })] : [])]
    }));
    for (const p of parts.slice(first ? 1 : 0)) {
      if (p.kind === "code") out.push(...codeParas(p.text));
      else out.push(textPara(p.text, { indent: { left: INDENT } }));
    }
    return out;
  }

  const options = opts => opts.map((o, i) => new Paragraph({
    spacing: { after: 40 }, keepNext: i < opts.length - 1, keepLines: true,
    indent: { left: INDENT + 360, hanging: 360 }, tabStops: [{ type: TabStopType.LEFT, position: INDENT + 360 }],
    children: [new TextRun({ children: [`${o.key}.`, new Tab()], size: BODY, font: FONT }), ...runs(o.text)]
  }));

  function matching(item) {
    const rows = Math.max(item.columnA.length, item.columnB.length);
    const cell = (children, w) => new TableCell({ children, borders: noBorders, width: { size: w, type: WidthType.DXA }, margins: { top: 40, bottom: 40, left: 60, right: 120 } });
    const head = (t) => new Paragraph({ spacing: { after: 40 }, children: [new TextRun({ text: t, bold: true, size: BODY, font: FONT })] });
    const trs = [new TableRow({ tableHeader: true, children: [cell([head("Column A")], 5000), cell([head("Column B")], 4360)] })];
    for (let i = 0; i < rows; i++) {
      const a = item.columnA[i], b = item.columnB[i];
      trs.push(new TableRow({ cantSplit: true, children: [
        cell([new Paragraph({ spacing: { after: 0 }, children: a ? [new TextRun({ text: `____ ${a.number}.  `, bold: true, size: BODY, font: FONT }), ...runs(a.text)] : [] })], 5000),
        cell([new Paragraph({ spacing: { after: 0 }, children: b ? [new TextRun({ text: `${b.letter}.  `, bold: true, size: BODY, font: FONT }), ...runs(b.text)] : [] })], 4360)
      ] }));
    }
    return [new Table({ width: { size: 9360, type: WidthType.DXA }, columnWidths: [5000, 4360], borders: { ...noBorders, insideHorizontal: none, insideVertical: none }, rows: trs }),
      new Paragraph({ spacing: { after: 160 }, children: [] })];
  }

  function fill(item) {
    const children = [new TextRun({ text: `${item.number}.  `, bold: true, size: BODY, font: FONT })];
    for (const s of item.segments) {
      if (s.blank == null) children.push(...runs(s.text));
      else children.push(new TextRun({ text: "______________", size: BODY, font: FONT }));
    }
    return [new Paragraph({ spacing: { before: 140, after: 100, line: 340 }, indent: { left: INDENT, hanging: INDENT }, children })];
  }

  function itemBlocks(test, item) {
    switch (item.kind) {
      case "choice": return [...stem(item), ...options(item.options)];
      case "truefalse": return stem(item);
      case "matching": return matching(item);
      case "fill": return fill(item);
      case "concept": return [
        ...stem(item, { blank: false }),
        ...item.clues.map((c, i) => textPara(`(${String.fromCharCode(97 + i)})  ${c}`, { indent: { left: INDENT + 360, hanging: 360 }, spacing: { after: 40 } })),
        new Paragraph({ spacing: { before: 120, after: 80 }, indent: { left: INDENT }, children: [new TextRun({ text: "Answer: ", bold: true, size: BODY, font: FONT }), new TextRun({ text: "________________________________", size: BODY, font: FONT })] })
      ];
      case "sorting": return [
        ...(item.stem?.length ? item.stem.flatMap(p => (p.kind === "code" ? codeParas(p.text) : [textPara(p.text, { spacing: { before: 120, after: 60 } })])) : []),
        new Paragraph({ spacing: { before: 120, after: 80 }, keepNext: true, children: item.categories.flatMap((c, i) => [
          new TextRun({ text: `${i ? "        " : ""}${c.letter}. `, bold: true, size: BODY, font: FONT }), new TextRun({ text: c.name, bold: true, size: BODY, font: FONT })]) }),
        ...item.items.map(i => new Paragraph({ spacing: { after: 60 }, indent: { left: INDENT, hanging: INDENT }, tabStops: [{ type: TabStopType.LEFT, position: INDENT }],
          children: [new TextRun({ children: [`____ ${i.number}.`, new Tab()], bold: true, size: BODY, font: FONT }), ...runs(i.text)] }))
      ];
      case "solve": return [
        ...stem(item, { blank: false }),
        new Paragraph({ spacing: { before: 120, after: 0 }, keepNext: true, indent: { left: INDENT }, children: [new TextRun({ text: "Solution:", italics: true, size: 20, font: FONT, color: GREY })] }),
        blankLine(), blankLine(), blankLine(), blankLine(),
        new Paragraph({ spacing: { before: 200, after: 120 }, indent: { left: INDENT }, children: [new TextRun({ text: "Answer: ", bold: true, size: BODY, font: FONT }), new TextRun({ text: "________________________________", size: BODY, font: FONT })] })
      ];
      case "essay": return [
        ...stem(item, { blank: false, suffix: `  (${pointsText(item.points)})` }),
        ...Array.from({ length: Math.max(6, item.points * 3) }, (_, i) => blankLine(320, i < Math.max(6, item.points * 3) - 1))
      ];
      default: return [];
    }
  }

  const children = [];
  children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 40 }, children: [new TextRun({ text: quiz.title, bold: true, size: 32, font: FONT })] }));
  children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 240 }, children: [new TextRun({ text: "QUIZ", size: 22, font: FONT, color: GREY, characterSpacing: 60 })] }));
  const field = (label, w) => [new TextRun({ text: `${label} `, bold: true, size: BODY, font: FONT }), new TextRun({ text: "_".repeat(w), size: BODY, font: FONT })];
  children.push(para([...field("Name:", 34), new TextRun({ text: "    ", size: BODY }), ...field("Section:", 14)], { spacing: { after: 160 } }));
  children.push(para([...field("Date:", 16), new TextRun({ text: "    ", size: BODY }), ...field("Score:", 6), new TextRun({ text: ` / ${quiz.total}`, bold: true, size: BODY, font: FONT })], {
    spacing: { after: 200 }, border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: "000000", space: 8 } }
  }));
  children.push(textPara(`General directions: Read each item carefully and answer every item. Total: ${pointsText(quiz.total)}.`, { spacing: { after: 120 } }, { italics: true }));

  for (const test of quiz.tests) {
    children.push(new Paragraph({
      keepNext: true, spacing: { before: 320, after: 60 },
      children: [new TextRun({ text: `${testHeading(test).toUpperCase()}  `, bold: true, size: 24, font: FONT }),
        new TextRun({ text: `(${pointsText(test.points)})`, size: 22, font: FONT, color: GREY })]
    }));
    children.push(textPara(test.directions, { keepNext: true, spacing: { after: 100 } }, { italics: true }));
    for (const item of test.items) children.push(...itemBlocks(test, item));
  }

  if (withKey) {
    children.push(new Paragraph({ pageBreakBefore: true, alignment: AlignmentType.CENTER, spacing: { after: 40 }, children: [new TextRun({ text: "ANSWER KEY", bold: true, size: 30, font: FONT })] }));
    children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 }, children: [new TextRun({ text: `${quiz.title}  \u00b7  ${pointsText(quiz.total)}`, size: 22, font: FONT, color: GREY })] }));
    for (const test of quiz.tests) {
      children.push(new Paragraph({ keepNext: true, spacing: { before: 240, after: 80 },
        children: [new TextRun({ text: `${testHeading(test)}  `, bold: true, size: 24, font: FONT }), new TextRun({ text: `(${pointsText(test.points)})`, size: 22, font: FONT, color: GREY })] }));
      for (const item of test.items) {
        for (const a of item.answers) {
          children.push(new Paragraph({ spacing: { after: 50 }, indent: { left: 540, hanging: 540 }, tabStops: [{ type: TabStopType.LEFT, position: 540 }], keepLines: true,
            children: [new TextRun({ children: [`${a.label}.`, new Tab()], bold: true, size: BODY, font: FONT }), ...runs(a.text)] }));
          if (a.note) children.push(textPara(`Solution: ${a.note}`, { indent: { left: 540 }, spacing: { after: 60 } }, { italics: true, color: GREY, size: 20 }));
          for (const line of a.lines || []) children.push(new Paragraph({ spacing: { after: 30 }, indent: { left: 900, hanging: 240 }, children: [new TextRun({ text: `\u2022  ${line}`, size: 20, font: FONT })] }));
        }
      }
    }
  }

  const doc = new Document({
    creator: "AI Adaptive Learning", title: `${quiz.title} \u2013 Quiz`,
    styles: { default: { document: { run: { font: FONT, size: BODY } } } },
    sections: [{
      properties: { page: { size: { width: 12240, height: 15840 }, margin: { top: 1260, right: 1440, bottom: 1260, left: 1440 } } },
      footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [
        new TextRun({ children: [`${quiz.title}  \u00b7  Page `, PageNumber.CURRENT, " of ", PageNumber.TOTAL_PAGES], size: 18, font: FONT, color: GREY })] })] }) },
      children
    }]
  });
  return Packer.toBlob(doc);
}
