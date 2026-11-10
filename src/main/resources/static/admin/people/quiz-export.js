/**
 * Quiz export entry point: builds the quiz layout for one topic, loads the Word or PDF library the first time
 * it is needed, renders the file and starts the download. Nothing is sent to the server; the question set is
 * already in the page.
 */
import { buildQuiz, fileStem } from "./quiz-export-model.js";

const scripts = new Map();

/** Loads /admin/vendor/<file> once. `ready` says whether the library's global is there. */
function loadScript(file, ready) {
  if (ready()) return Promise.resolve();
  if (!scripts.has(file)) {
    scripts.set(file, new Promise((resolve, reject) => {
      const el = document.createElement("script");
      el.src = new URL(`../vendor/${file}`, import.meta.url).href;
      el.async = true;
      el.onload = () => (ready() ? resolve() : reject(new Error(`${file} loaded but did not start.`)));
      el.onerror = () => { scripts.delete(file); reject(new Error(`Could not load ${file}.`)); };
      document.head.append(el);
    }));
  }
  return scripts.get(file);
}

function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10000);
}

export function hasExportableQuestions(topic) {
  return (topic?.questions || []).some(q => !q.archived);
}

/**
 * @param {{ topic: string, questions: object[] }} topic  the full topic (not the filtered view)
 * @param {{ format: "docx"|"pdf", withKey: boolean }} options
 * @returns {Promise<{ name: string, quiz: object }>}
 */
export async function exportTopic(topic, { format, withKey }) {
  const quiz = buildQuiz(topic);
  if (!quiz.tests.length) throw new Error("This topic has no questions that can be exported.");
  let blob;
  if (format === "pdf") {
    await loadScript("jspdf.umd.min.js", () => Boolean(globalThis.jspdf?.jsPDF));
    blob = await (await import("./quiz-export-pdf.js")).renderPdf(quiz, { withKey });
  } else {
    await loadScript("docx.umd.min.js", () => Boolean(globalThis.docx?.Document));
    blob = await (await import("./quiz-export-docx.js")).renderDocx(quiz, { withKey });
  }
  const name = `${fileStem(quiz.title)}-quiz${withKey ? "-with-key" : ""}.${format === "pdf" ? "pdf" : "docx"}`;
  download(blob, name);
  return { name, quiz };
}
