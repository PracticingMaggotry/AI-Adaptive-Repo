# AI layer redesign

Fixes the eleven problems listed against the AI layer. Everything not on that list was left alone, including the
tier rule (>=80 up, 50-79 hold, <50 down), question types, validation, daily limits, and every JSON shape the
frontend reads.

## The idea in one paragraph

The AI used to be told about mistakes by re-reading the last 8 attempts every time it was called. Now every graded
answer is stored forever in an append-only table (`question_response`), tied to the **handout** it was about (not
the topic name), the **concept** it tested, the tier, the purpose and the time. A pure-Java model (`MasteryModel`)
replays that history to decide, per concept: weakness, one-off slip, learned, or not yet asked. Quizzes are planned
by the server from that state (`QuizBlueprint`); Claude only writes one question per planned slot.

## Problem -> fix

| # | Problem | Fix | Where |
|---|---------|-----|-------|
| 1 | AI doesn't know what the student has since learned | A concept missed and then answered correctly twice in a row is `LEARNED (recovered)`. It is shown to the AI under "NOW LEARNED", never remediated, and only gets a spaced check. | `MasteryModel`, `LearnerPrompt` |
| 2 | One-off slip treated like a weakness | One miss after >=2 correct answers (or after success with the estimate still above "weak", or a very fast wrong answer) is `SLIP`: not drilled, at most one verify question. Slip probability is higher on Hard items. | `MasteryModel`, `AiPolicy.SLIP_*` |
| 3 | Older mistakes lose detail | Wrong answers are snapshotted in full (question, student answer, correct answer, missed rubric points, feedback, payload) into `question_response` at submit time. Nothing is windowed or reloaded from `Question` rows that regeneration deletes. | `QuestionResponse`, `QuizController.toResponse` |
| 4 | Code says 15, does 20 | Every number lives in `AiPolicy` and is referenced by name. The old comment/limit mismatch (5 attempts/15 entries vs 8/20) is gone; there is no attempt window at all. | `AiPolicy` |
| 5 | 8-attempt window, no sense of time | Whole history is replayed in time order. Knowledge decays toward a floor with a 21-day half-life, so a concept learned weeks ago becomes "due for a refresher" (not a mistake). Recovery is judged at the time of the evidence, so decay can't turn a fixed mistake back into a weakness. | `MasteryModel` |
| 6 | Doesn't remember what was covered | Coverage is per concept. Adapted quizzes allocate slots: remediate open weaknesses, cover not-yet-asked concepts, verify slips/stale/learned ones (50/30/20 shares, redistributed when a pool is empty). The first quiz spreads one slot per concept across the whole handout. | `QuizBlueprint` |
| 7 | New handout under same topic name inherits old state | New `StudyScope` = one student x one handout (content hash). All learner state hangs off the scope. Different content opens a new scope and closes the old one (its baseline is archived on the old scope row); identical content keeps it. If the new version still shares >=60% of concept keys, evidence for those concepts carries over. Pre-existing data is adopted by timestamp: attempts/baselines older than the current handout's upload are not inherited. Deleting a topic closes its scope. | `StudyScope`, `ScopeService` |
| 8 | Cached lessons keep the old mistake base | `LessonCache` (key: student+topic+tier) replaced by `LessonSnapshot` keyed by (handout, tier, **learner-state fingerprint**). The fingerprint is the set of open-weakness / slip / recovered concepts, so a lesson is reused until that picture changes, and not regenerated after every answer. Identical concurrent requests share one generation. | `LessonSnapshot`, `LearnerPrompt.stateHash`, `AiLessonController` |
| 9 | Can't tell whether questions got harder (questions deleted) | `question_response` keeps tier, type, concept, Bloom level claimed by the generator, credit and time for every answer, independent of the `Question` row. `GET /api/admin/ai/calibration` reports facility per tier with confidence intervals and a **within-student paired** comparison (needed because adaptive routing puts stronger students on Hard). | `TierCalibration`, `AiAdminController` |
| 10 | Only 4 concurrent Claude calls blocks users | `AiScheduler` replaces `Semaphore(4)`: lanes (lessons/grading > question generation > bulk), 3 slots reserved for interactive work, <=2 slots per student, bounded wait, concurrency starts at 10 and halves on HTTP 429/529 (honouring Retry-After) then grows back. Double-clicks on quiz generation are rejected before they cost a call. | `AiScheduler`, `AiGateway`, `ClaudeService.call` |
| 11 | Cost grows with heavy users | (a) Long handouts send only the passages for the planned concepts (BM25-style retrieval, 2,400-word budget) instead of the whole text on every call. (b) Question count is planned server-side and `max_tokens` scales with it (was flat 12,000). (c) The generator tags each question's category, removing the separate Haiku categorisation call on submit. (d) Static system prompts are marked cacheable. (e) Per-student daily cost is recorded (`ai_usage`); past the soft budget optional calls are skipped, past the hard budget new generation is refused and lessons fall back to the newest stored one. | `PassageRetriever`, `AiGateway`, `CostModel`, `ClaudeService` |

## Behaviour changes you should know about

* The first quiz after upload is now concept-planned (one slot per concept spread over the whole handout). If a
  handout has no extracted concept list, every path falls back to the previous free-form generators.
* Adapted quizzes have a deterministic size (15/20/25 by handout length) instead of the model's choice in 15-30,
  and a concept gets at most 3 slots, so an adapted quiz is a **mix** (weaknesses / new / verification). Target
  Problems is unchanged in spirit: it concentrates on documented weaknesses and a single narrow weakness can fill
  the whole quiz.
* `PROMPT_VERSION` is stored on lessons and responses so results before/after this change stay distinguishable in
  the research data.
* Quiz/lesson/upload JSON responses keep the same keys; the only new thing is an extra `plan` progress event on the
  existing SSE stream.

## Data and migration

`spring.jpa.hibernate.ddl-auto=update` is already set and there is no Flyway migration for entities, so the new
tables (`study_scope`, `question_response`, `lesson_snapshot`, `ai_usage`) and new nullable columns
(`question.scope_id/concept_key/purpose/bloom_level/category`, `attempt.scope_id`) are created on first start.

Existing students: the first time they touch a topic, `ScopeService.ensure` creates a scope for the current handout,
imports attempts made since that handout was uploaded as responses (concepts matched by word overlap, no AI call),
and drops a baseline that predates the upload. `lesson_cache` is no longer read; its table can be dropped later.

`question_response` is treated like `question_exposure_log`: topic deletion closes the scope but keeps the rows
(research log). If your consent/erasure policy requires removing them on account deletion, that is a decision for
you; nothing currently deletes them.

## Not verified here (be honest with yourself before the demo)

The sandbox cannot reach Maven Central, so the Spring layer was **not compiled or run**.

* Verified: the pure-Java core (`ai/` package) compiles and its 46 tests pass (mastery model, blueprint,
  retriever, scheduler fairness/reservation/AIMD, single-flight, calibration, cost, prompt/fingerprint, legacy
  matcher), run with a small JUnit stand-in. Every file in `src/main` passes a syntax-only parse, and the new
  Spring classes type-check against hand-written stubs of Spring/JPA/Jackson.
* Not verified: the edited controllers (`QuizController`, `MaterialController`, `AiLessonController`,
  `ClaudeService`) against the real libraries, Hibernate mapping and derived-query names at startup, the
  existing `AdaptiveLearningBackendApplicationTests` context load, and live calls to Anthropic (prompt caching
  behaviour, the new blueprint prompt's output quality).

Do first: `mvn test`, start the app on the test profile, upload a handout, take the first quiz, then an adapted
quiz, a Target Problems quiz and a lesson. Check the `plan`/`source` progress lines and the four new tables.

## Knobs (all in `AiPolicy`)

Slip/learn/forget parameters, thresholds, blueprint shares, per-concept cap, retrieval budget, scheduler sizes,
and the daily budgets. The budget dollars (`$0.40` soft / `$1.00` hard) and the price table in `CostModel` are
placeholders: set them from your real plan and the current Anthropic price list. Mastery parameters are
reasoned defaults, not fitted to your data; the calibration endpoint is how you would tune them.

## Not done (deliberately)

* Quiz generation is still a synchronous HTTP request (the scheduler bounds the wait, but a long generation holds
  a request thread). Moving it to a job + the existing SSE channel is the next step if you need more headroom.
* Essay grading is still one Sonnet call per essay.
