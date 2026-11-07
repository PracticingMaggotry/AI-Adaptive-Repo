# Showing concept progress to students

Student pages only. Nothing on the admin side was changed.

## Problem

The AI layer tracks every concept of a handout with five internal states (`UNSEEN`, `WEAK`, `SLIP`, `SHAKY`,
`LEARNED`), a recovered flag, a staleness flag, miss/correct counts and a probability. That is the right detail for the
AI and the wrong one for a student:

- too many categories, and words like "weak", "slip" and "shaky" read as a verdict on the person;
- probabilities and priority scores look precise but are estimates;
- a handout can have 20 to 40 concepts, and a raw list buries the three that matter;
- before this change no concept-level information reached the student at all (the dashboard showed topic averages and
  the five skill categories only).

## Decision

`ConceptInsights` (pure Java, `ai` package) is the single translator. Every page shows the same four statuses in the
same words, plus two small tags. The student never sees a probability, a priority score, or the internal state names.

| Student sees | Internal state | Meaning |
|---|---|---|
| **Needs practice** | `WEAK`, or `SHAKY` with a miss | Still unresolved. The same concepts the AI is told to drill, so the screen and the next quiz agree. |
| **Almost there** | `SLIP`, or `SHAKY` with no miss | Mostly right; one more check will settle it. A one-off slip is not labelled a weakness. |
| **Got it** | `LEARNED` | Known. Concepts that were missed and then fixed carry a **Fixed** tag, which is good news and is shown first. |
| **Not tried yet** | `UNSEEN` | Not asked yet. Neutral, not a gap. |
| tag **Review soon** | `stale` | Known earlier, not practised for a while (spaced review). A tag, not a fifth status. |

Each concept gets one plain sentence built from counts the student can check against their own quizzes, for example
"Missed 3 of 3 questions · last miss yesterday" or "Fixed — missed 1 time earlier, now right 2 in a row".

### Rules

- Lead with progress ("You've got 12 of 30 concepts"), then the one thing that needs attention.
- At most **3** concepts are named on summary surfaces (`ConceptInsights.FOCUS_MAX`). The rest are one click away.
- Order is by what to do first (needs practice, almost there, got it, not tried); inside "needs practice" by urgency.
- Every concept shown has a next step: **Practice these concepts** opens the Quiz Hub on that handout with Target
  Problems focused. The link never starts an AI generation by itself.
- No red. "Needs practice" is amber: a to-do, not an error.
- Each status uses colour **and** an icon **and** its text label. The bar uses a stripe pattern for "needs practice" and
  a hollow dashed segment for "not tried", because green and amber have almost the same luminance (1.03:1).

## Where it appears

| Page | Shows | Does not show | Why |
|---|---|---|---|
| **Dashboard** | One card, "Concepts to Focus On": overall bar and counts, top 3 concepts across all handouts, each with a Practice link | The concept list | Overview page: answer "what next?" and hand off to the Learning Hub |
| **Quiz Hub** | One line per handout card: mini bar and counts ("5 need practice · 2 almost there ...") | Names of concepts | Decision point before starting; a number is enough to choose Target Problems |
| **Quiz result** | Full-width card above the question list: what you fixed, bar, top 3 to practise, one primary action | Per-concept details, probabilities | Right after a quiz is when the student wants "what now?" and some good news |
| **Learning Hub** | Per handout card: summary line, expandable full panel grouped by status, "needs practice" open by default, others collapsed; each row can show the handout definition and the last missed question | (nothing withheld) | The home of the detail. Progressive disclosure keeps the card short |
| **Quiz page (during a quiz)** | Nothing | Everything | No feedback or status while answering |
| Profile / Reports | Nothing | | Not the place for it |

## Standards followed

- **Nielsen's heuristics:** visibility of system status (bar and counts), match with the real world (plain words),
  recognition over recall (same labels and icons everywhere), user control (every group and detail collapses),
  aesthetic and minimalist design (three names at most, details on request).
- **Progressive disclosure:** summary first; groups collapsed except the urgent one; five rows then "Show N more";
  details per row on request.
- **WCAG 2.2:** 1.4.1 colour is never the only cue; 1.4.3 all text pairs at least 4.5:1 and 1.4.11 graphics at least 3:1
  (computed, listed in `concept-progress.css`); 2.4.7 visible focus ring; 2.5.3 accessible names contain the visible
  label; 4.1.2 disclosures are real `<button aria-expanded aria-controls>`; buttons and group headers are 44px or
  taller and text toggles 36px (WCAG's minimum is 24px); the bar has a
  text alternative (`role="img"` plus a spoken summary); `prefers-reduced-motion` is respected.
- **Safe rendering:** concept names, definitions and answers come from uploaded handouts, so every string is set with
  `textContent`; nothing is passed through `innerHTML`.
- **Degrades quietly:** if the endpoint fails, or a handout has no extracted concepts, the component hides itself.

## What was added

- `ai/ConceptInsights.java`: state-to-status translation, evidence sentences, ordering, focus list.
- `ConceptInsightsController.java`: `GET /api/concepts?topic=` (one handout) and `GET /api/concepts/summary` (all
  handouts, counts and focus only). Read-only and scoped to the logged-in student; no AI call.
- `static/concept-progress.js` and `.css`: one module renders every surface.
- Small mount edits in `dashboard.html`, `quizhub.html`, `quizfinish.html`, `learninghub.html`.
- `ConceptInsightsTest` (11 cases).

## Known gaps and suggested follow-ups

- The Learning Hub topic card still shows a **red "Needs Practice" badge** computed from the topic's average score. It
  now sits next to amber, concept-level "needs practice" counts. Same phrase, two levels, two colours. Best fix:
  derive the topic badge from concept counts or rename it to a topic-level wording.
- The quiz result page keeps its **"Where You Need Work"** card (the five skill categories). The name now overlaps with
  "Your Concepts"; "Thinking skills" would separate them. Its three charts of the same data are also redundant.
- The **"Weakness Score"** tile on the result page (wrong divided by total) is not the concept model and reads as a
  judgement; consider replacing it with the concept counts.
- Visual checks were done in headless Chrome with mocked API data; the Spring side was not run end to end.
