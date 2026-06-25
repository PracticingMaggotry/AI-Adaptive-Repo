package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * ClaudeService — thin wrapper around the Anthropic Messages API.
 *
 * Every public method builds a tightly scoped, system-prompted request
 * that targets one specific adaptive-learning task. The system prompt
 * tells Claude exactly what role it is playing and what format to return,
 * so the controller can parse the result with zero ambiguity.
 *
 * MODEL ROUTING: lightweight, low-reasoning tasks (categorization, short
 * summaries, lesson-content generation, question-category tagging) use
 * the cheaper/faster Haiku model. Tasks where output quality directly
 * affects what the student is taught or graded on (quiz/question
 * generation of any kind, essay grading) use Sonnet.
 *
 * All methods return plain Java Strings. Callers that need structured data
 * (e.g. JSON) must parse the string themselves — this keeps the service
 * decoupled from any particular controller shape.
 */
@Service
public class ClaudeService {

    // ── Config ────────────────────────────────────────────────────────────
    private static final String API_URL     = "https://api.anthropic.com/v1/messages";

    // Reasoning-intensive tasks: quiz/question generation of any kind,
    // essay grading — anything where output quality directly affects what
    // the student is taught, tested on, or graded on.
    private static final String MODEL_SONNET = "claude-sonnet-4-6";

    // Lightweight tasks: categorization, short summaries, lesson content,
    // question-category tagging — no deep reasoning required.
    private static final String MODEL_HAIKU  = "claude-haiku-4-5-20251001";

    private static final String API_VERSION = "2023-06-01";
    // Raised from 4000 → 8000 so the Adapted Quiz endpoint can return up to
    // ~50 question objects (~200 tokens each) in a single response without
    // truncation. Other endpoints use far fewer tokens, so this is safe headroom.
    private static final int MAX_TOKENS = 8000;

    @Value("${anthropic.api.key}")
    private String apiKey;

    private final RestTemplate   restTemplate = new RestTemplate();
    private final ObjectMapper   mapper       = new ObjectMapper();

    // ═════════════════════════════════════════════════════════════════════
    // PUBLIC API — one method per adaptive-learning concern
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Generate an adapted quiz based on the student's best score on a topic.
     * Returns a JSON array of question objects (same shape as generateMixedQuestions).
     * The topic name is intentionally NOT sent to the AI as content context —
     * only the actual handout text is, since a student-chosen topic label may
     * be generic, unrelated, or deliberately misleading.
     *
     * @param topic      the topic name (kept for method-signature/caller compatibility
     *                   only; intentionally NOT passed to the AI as content context)
     * @param text       extracted text from the uploaded handout
     * @param bestScore  the student's best score (0-100) on this topic
     * @return JSON array string of question objects
     */
    public String generateAdaptedQuestions(String topic, String text, double bestScore) {
        String adaptationGuidance;
        String targetDifficulty;

        if (bestScore >= 80) {
            targetDifficulty = "Hard";
            adaptationGuidance = """
                    The student has scored %.0f%% on this topic — they have strong foundational knowledge.
                    Generate advanced questions that require analysis, synthesis, and evaluation.
                    Ask students to compare concepts, identify edge cases, or apply ideas to novel scenarios.
                    Distractors must be plausible and require careful thinking to eliminate.
                    Do not ask simple recall or definition questions.
                    """.formatted(bestScore);
        } else if (bestScore >= 50) {
            targetDifficulty = "Medium";
            adaptationGuidance = """
                    The student has scored %.0f%% on this topic — they understand the basics but struggle with application.
                    Generate questions that bridge understanding and application.
                    Target concepts the student likely found confusing based on the material.
                    Mix some conceptual questions with applied scenarios.
                    Avoid purely trivial recall but do not go fully advanced.
                    """.formatted(bestScore);
        } else {
            targetDifficulty = "Easy";
            adaptationGuidance = """
                    The student has scored %.0f%% on this topic — they need foundational reinforcement.
                    Generate questions that focus on core definitions, key terms, and basic concepts.
                    Questions should build confidence while still being meaningful.
                    Distractors should be clearly wrong to someone who understands the basics.
                    """.formatted(bestScore);
        }

        // Pass a much larger slice of the handout through so Claude has enough
        // source material to draw 25-50 distinct, non-repetitive questions from.
        // knowledgeContextForQuiz already caps at 6000 chars; when the raw full
        // text is passed directly (no extract), cap at 12000 for adapted quizzes.
        String passage = text.length() > 12000 ? text.substring(0, 12000) : text;

        // Difficulty-based type weighting mirrors generateMixedQuestions
        String typeGuidance = switch (targetDifficulty.toLowerCase()) {
            case "hard" -> "Lean toward ESSAY, typed FILLBLANK, MATCHING, and CONCEPTID. MCQ should be a minority. No trivial recall.";
            case "medium" -> "Mix MCQ, MATCHING, SORTING, drag-drop FILLBLANK, TRUEFALSE, and some CONCEPTID. Balance recall with application.";
            default -> "Lean toward MCQ, TRUEFALSE, MATCHING, SORTING, and drag-drop FILLBLANK. Keep questions confidence-building.";
        };

        String system = """
                You are an adaptive quiz generator for a learning system.

                IMPORTANT: Never use a topic name, title, or label as a source of facts. Topic names
                are chosen freely by the student and may be generic, unrelated, or deliberately
                misleading — they are not reliable information. Base every question strictly and
                exclusively on the handout text provided in the user message below.

                If a KNOWLEDGE GUIDE section appears above the Handout Text, use it only to know
                which concepts to prioritise — every question must still be verifiable against
                the actual Handout Text that follows it, never against the guide alone.

                Based on the LENGTH and complexity of the provided handout text, decide how many
                questions to generate — choose a number between 15 and 30:
                  - Short material (roughly under 1500 words): generate around 15 questions.
                  - Medium material (roughly 1500-4000 words): generate around 22 questions.
                  - Long material (roughly over 4000 words): generate around 30 questions.
                Never generate fewer than 15 or more than 30 questions. Do not repeat the same
                concept in near-identical phrasing across multiple questions — cover the material broadly.

                Generate a MIXED set of question types. Each question MUST have a "type" field.
                Choose types appropriate to the material — do NOT force a type the material doesn't support.
                SORTING only if the material contrasts two distinct categories.

                Return ONLY a valid JSON array. No markdown, no explanation, no preamble.

                Each object must have these COMMON fields:
                  "type": one of [MCQ, TRUEFALSE, MATCHING, FILLBLANK, ESSAY, SORTING, CONCEPTID]
                  "questionText": the question or prompt shown to the student
                  "hint": one sentence hint
                  "explanation": one sentence explanation of the correct answer

                Then type-specific fields in a "payload" object:

                MCQ:
                  payload: { "options": ["A","B","C","D"], "correctAnswer": "full text of correct option" }

                TRUEFALSE:
                  payload: { "correctAnswer": "True" or "False" }

                MATCHING:
                  payload: { "leftItems": ["term1","term2",...], "rightItems": ["def1","def2",...], "correctPairs": [[0,1],[1,0],...] }

                FILLBLANK (use exact verbatim sentence from handout, replace 2-3 key words with {{1}} {{2}}):
                  payload: { "excerpt": "exact sentence with {{1}} {{2}} placeholders", "blanks": [{"id":1,"answer":"word"},{"id":2,"answer":"word"}], "mode": "typed" for Hard or "dragdrop" for Easy/Medium }

                ESSAY:
                  payload: { "rubric": ["point1","point2","point3"] }

                SORTING (two clearly distinct categories, 3 items each):
                  payload: { "categoryA": "label A", "categoryB": "label B", "items": [{"text":"item","correctCategory":"A"},{"text":"item2","correctCategory":"B"}] }

                CONCEPTID (four clues pointing to one concept):
                  payload: { "clues": ["clue1","clue2","clue3","clue4"], "correctAnswer": "concept name" }
                """;

        String user = """
                Target difficulty: %s
                Student adaptation context: %s
                Type guidance: %s

                Handout text:
                ---
                %s
                ---

                Generate between 15 and 30 mixed-type adaptive questions (choose the count based on
                the guidance above) based strictly on the handout text above. Do not use any topic
                name or label as a source of information — rely only on the handout text shown above.
                If a KNOWLEDGE GUIDE section appears above, use it only for focus guidance;
                every question must be verifiable against the Handout Text that follows it.
                """.formatted(targetDifficulty, adaptationGuidance, typeGuidance, passage);

        return call(system, user, MODEL_SONNET);
    }

    /**
     * Generate mixed-type questions for the "Target Problems" quiz feature.
     *
     * Two modes, chosen automatically based on what's available:
     *
     *   MODE A — Wrong-answer-driven re-teaching (used whenever wrongAnswers is
     *   non-empty): each entry in wrongAnswers is a SPECIFIC question the student
     *   got wrong or partially wrong, including the exact wrong answer they gave
     *   (or, for essays, what their written answer missed). Claude is asked to
     *   infer the misconception each wrong answer reveals and generate a brand
     *   new question that re-teaches and re-tests that exact concept from a
     *   different angle — this is what makes the quiz genuinely adaptive to THIS
     *   student's real mistakes, not just a generic list of "weak" key terms.
     *
     *   MODE B — General weak-concept diagnostic (used only when the student has
     *   no wrong-answer history yet for this topic, e.g. their very first
     *   attempt): falls back to the previous behavior of generating diagnostic
     *   questions around the supplied weakConcepts list.
     *
     * The topic name is intentionally NOT sent to the AI as content context —
     * only the actual handout text is, since a student-chosen topic label may
     * be generic, unrelated, or deliberately misleading.
     *
     * @param topic        the topic name (kept for method-signature/caller compatibility
     *                     only; intentionally NOT passed to the AI as content context)
     * @param text         extracted handout text (source material)
     * @param weakConcepts list of key terms/subtopics the student struggles with — used as
     *                     MODE B's primary driver, or as supplementary filler context in
     *                     MODE A when there are fewer than 5 documented wrong answers
     * @param wrongAnswers the student's actual wrong/partial answers from recent attempts on
     *                     this topic (question text, type, their answer, the correct answer,
     *                     category, and — for essays — what their answer missed). Empty if
     *                     the student has no attempt history yet for this topic.
     * @param avgScore     the student's average score on this topic (0-100), or -1 if none
     * @return JSON array string of question objects, same shape as other generators
     */
    public String generateTargetedQuestions(String topic, String text, List<String> weakConcepts,
                                            List<Map<String, String>> wrongAnswers, double avgScore) {
        String conceptsCsv = (weakConcepts == null || weakConcepts.isEmpty())
                ? "No specific weak concepts identified — focus on the most commonly misunderstood ideas in the material."
                : String.join(", ", weakConcepts);

        // Type weighting based on student level
        String typeGuidance;
        if (avgScore >= 80) {
            typeGuidance = "Use a rich mix: ESSAY, typed FILLBLANK, MATCHING, CONCEPTID, SORTING, TRUEFALSE, and MCQ. No trivial recall.";
        } else if (avgScore >= 50) {
            typeGuidance = "Mix MCQ, MATCHING, SORTING, drag-drop FILLBLANK, TRUEFALSE, and CONCEPTID. " +
                    "Do NOT include DIAGRAM or ESSAY. Balance recall with application.";
        } else {
            typeGuidance = "Lean toward MCQ, TRUEFALSE, MATCHING, and drag-drop FILLBLANK. " +
                    "Do NOT include DIAGRAM, ESSAY, or SORTING. Keep questions confidence-building.";
        }

        boolean hasWrongAnswers = wrongAnswers != null && !wrongAnswers.isEmpty();
        int targetCount = !hasWrongAnswers ? 10 : Math.max(5, Math.min(15, wrongAnswers.size()));

        String system = """
                You are a diagnostic AND remedial quiz generator for an adaptive learning system.

                IMPORTANT: Never use a topic name, title, or label as a source of facts. Topic names
                are chosen freely by the student and may be generic, unrelated, or deliberately
                misleading. Base every question strictly and exclusively on the handout text provided.

                If the handout text is preceded by a KNOWLEDGE GUIDE section, use that guide only
                to identify which concepts to focus on — every question must still be verifiable
                against the actual Handout Text that follows, never against the guide alone.

                The user message below works in one of two modes:

                MODE A — Targeted re-teaching: you are given a list of SPECIFIC questions the
                student got wrong, each with the exact wrong answer they gave. For every entry,
                infer the misconception or knowledge gap that wrong answer reveals, then write ONE
                NEW question that re-teaches and re-tests that exact same concept from a different
                angle (different wording, example, or framing — never a copy of the original
                question). The "hint" field for these questions should briefly correct the
                misconception directly, so the student gets real corrective teaching, not just
                another guess.

                MODE B — General weak-concept diagnostic: used only when no wrong-answer history
                exists yet. Generate diagnostic questions broadly covering the listed weak concepts.

                Each question MUST have a "type" field. Choose types appropriate to the material
                content. SORTING only if the material genuinely contrasts two distinct categories.

                Return ONLY a valid JSON array. No markdown, no explanation, no preamble.

                Each object must have these COMMON fields:
                  "type": one of [MCQ, TRUEFALSE, MATCHING, FILLBLANK, ESSAY, SORTING, CONCEPTID]
                  "questionText": the question or prompt shown to the student
                  "hint": one sentence hint (in MODE A, this should directly address the misconception)
                  "explanation": one sentence explanation of the correct answer

                Then type-specific fields in a "payload" object:

                MCQ:
                  payload: { "options": ["A","B","C","D"], "correctAnswer": "full text of correct option" }

                TRUEFALSE:
                  payload: { "correctAnswer": "True" or "False" }

                MATCHING:
                  payload: { "leftItems": ["term1","term2",...], "rightItems": ["def1","def2",...], "correctPairs": [[0,1],[1,0],...] }

                FILLBLANK (use exact verbatim sentence from handout, replace 2-3 key words with {{1}} {{2}}):
                  payload: { "excerpt": "exact sentence with {{1}} {{2}} placeholders", "blanks": [{"id":1,"answer":"word"},{"id":2,"answer":"word"}], "mode": "typed" for Hard or "dragdrop" for Easy/Medium }

                ESSAY:
                  payload: { "rubric": ["point1","point2","point3"] }

                SORTING (two clearly distinct categories):
                  payload: { "categoryA": "label A", "categoryB": "label B", "items": [{"text":"item","correctCategory":"A"},{"text":"item2","correctCategory":"B"}] }

                CONCEPTID (four clues pointing to one concept):
                  payload: { "clues": ["clue1","clue2","clue3","clue4"], "correctAnswer": "concept name" }
                """;

        String user;
        if (hasWrongAnswers) {
            StringBuilder wrongList = new StringBuilder();
            int i = 1;
            for (Map<String, String> w : wrongAnswers) {
                wrongList.append(i++).append(". Question: ").append(w.getOrDefault("question", "")).append("\n");
                wrongList.append("   Type: ").append(w.getOrDefault("type", "mcq")).append("\n");
                String yourAnswer = w.getOrDefault("yourAnswer", "");
                wrongList.append("   Student's answer: ").append(yourAnswer.isBlank() ? "(no answer given)" : yourAnswer).append("\n");
                String correctAnswer = w.getOrDefault("correctAnswer", "");
                if (!correctAnswer.isBlank()) {
                    wrongList.append("   Correct answer: ").append(correctAnswer).append("\n");
                }
                String missed = w.get("missedPoints");
                if (missed != null && !missed.isBlank()) {
                    wrongList.append("   Missed in their written answer: ").append(missed).append("\n");
                }
                String feedback = w.get("feedback");
                if (feedback != null && !feedback.isBlank()) {
                    wrongList.append("   Feedback they were given: ").append(feedback).append("\n");
                }
                wrongList.append("   Category: ").append(w.getOrDefault("category", "Analysis")).append("\n\n");
            }

            int fillerNeeded = Math.max(0, targetCount - wrongAnswers.size());
            String fillerInstruction = fillerNeeded > 0
                    ? "Since there are fewer documented wrong answers than the quiz needs, also add "
                      + fillerNeeded + " more mixed-type questions covering other important concepts "
                      + "from the handout (consider these weak areas: " + conceptsCsv + ") so the quiz "
                      + "has enough breadth."
                    : "Every question in this quiz should come directly from the wrong-answer list below.";

            user = """
                    Student's average score on this topic: %s
                    Type guidance: %s

                    Below are %d SPECIFIC questions this student got wrong or partially wrong on recent
                    quizzes for this topic, including the exact answer they gave (or, for written
                    answers, what their answer missed). These are real, documented mistakes — not a
                    generic guess about what might be weak.

                    %s

                    Wrong answers to target (write one new re-teaching question per entry, in order):
                    %s

                    Handout text (source material — every question must be grounded in this, never in
                    the topic name or label; if a KNOWLEDGE GUIDE appears above the handout text use
                    it only for focus guidance, not as the source of truth):
                    ---
                    %s
                    ---

                    Generate exactly %d questions total, following MODE A: one re-teaching question per
                    wrong answer listed above. Do not use any topic name or label as a source of
                    information — rely only on the handout text shown above.
                    """.formatted(
                    avgScore < 0 ? "No quiz taken yet" : Math.round(avgScore) + "%",
                    typeGuidance,
                    wrongAnswers.size(),
                    fillerInstruction,
                    wrongList.toString(),
                    text,
                    targetCount
            );
        } else {
            user = """
                    Student's average score: %s
                    Weak concepts to target (every question must relate to one of these): %s
                    Type guidance: %s

                    Handout text (source material; if a KNOWLEDGE GUIDE appears above it, use the
                    guide only for focus guidance — every question must be verifiable against this text):
                    ---
                    %s
                    ---

                    Following MODE B, generate exactly 10 targeted, diagnostic mixed-type questions
                    based strictly on the handout text above, each one probing one of the weak concepts
                    listed. Do not use any topic name or label as a source of information — rely only
                    on the handout text above.
                    """.formatted(
                    avgScore < 0 ? "No quiz taken yet" : Math.round(avgScore) + "%",
                    conceptsCsv,
                    typeGuidance,
                    text
            );
        }

        return call(system, user, MODEL_SONNET);
    }

    // ═════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Core HTTP call to the Anthropic Messages API.
     * Uses a system prompt + single user turn.
     * Returns the first text content block, or an error message string
     * that the controller can surface gracefully.
     *
     * @param model the Anthropic model id to use for this call — callers pick
     *              MODEL_SONNET for reasoning-intensive/quality-critical tasks
     *              (quiz generation, essay grading) or MODEL_HAIKU for
     *              lightweight tasks (categorization, summaries, lesson content).
     */
    private String call(String systemPrompt, String userMessage, String model) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", API_VERSION);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                    "model",      model,
                    "max_tokens", MAX_TOKENS,
                    "system",     systemPrompt.strip(),
                    "messages",   List.of(
                            Map.of("role", "user", "content", userMessage.strip())
                    )
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(API_URL, request, String.class);

            JsonNode root    = mapper.readTree(response.getBody());
            JsonNode content = root.path("content");

            if (content.isArray() && content.size() > 0) {
                JsonNode first = content.get(0);
                if ("text".equals(first.path("type").asText())) {
                    return first.path("text").asText("").strip();
                }
            }

            return "AI response could not be parsed.";

        } catch (HttpClientErrorException e) {
            // 4xx — usually bad API key or quota
            return "AI service error (" + e.getStatusCode() + "): " + e.getResponseBodyAsString();
        } catch (Exception e) {
            return "AI service unavailable: " + e.getMessage();
        }
    }

    /**
     * Same as {@link #call(String, String, String)} but attaches a
     * base64-encoded PNG image to the user turn so Claude can actually look
     * at it (vision). Used exclusively for grounding DIAGRAM-type questions
     * in a real extracted figure instead of inferring one from nearby
     * caption text. Always uses the reasoning model since this directly
     * produces quiz content.
     */
    private String callWithImage(String systemPrompt, String userMessage, String imageBase64, String model) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", API_VERSION);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> imageBlock = Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "base64",
                            "media_type", "image/png",
                            "data", imageBase64
                    )
            );
            Map<String, Object> textBlock = Map.of(
                    "type", "text",
                    "text", userMessage.strip()
            );

            Map<String, Object> body = Map.of(
                    "model",      model,
                    "max_tokens", MAX_TOKENS,
                    "system",     systemPrompt.strip(),
                    "messages",   List.of(
                            Map.of("role", "user", "content", List.of(imageBlock, textBlock))
                    )
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(API_URL, request, String.class);

            JsonNode root    = mapper.readTree(response.getBody());
            JsonNode content = root.path("content");

            if (content.isArray() && content.size() > 0) {
                JsonNode first = content.get(0);
                if ("text".equals(first.path("type").asText())) {
                    return first.path("text").asText("").strip();
                }
            }

            return "AI response could not be parsed.";

        } catch (HttpClientErrorException e) {
            return "AI service error (" + e.getStatusCode() + "): " + e.getResponseBodyAsString();
        } catch (Exception e) {
            return "AI service unavailable: " + e.getMessage();
        }
    }

    /**
     * Generate structured lesson content for the Learning Hub, calibrated
     * to the student's current score tier on this topic. The topic name
     * itself is intentionally NOT sent to the AI — only the knowledgeCtx
     * (drawn from the actual handout) is, since a student-chosen topic
     * label may be generic, unrelated, or deliberately misleading.
     *
     * Lightweight content-generation task — routed to Haiku.
     *
     * @param topic        subject topic name (kept for method-signature/caller compatibility
     *                     only; intentionally NOT passed to the AI as content context)
     * @param knowledgeCtx extracted knowledge context from handout (~800 chars)
     * @param score        the "lesson-driving" score (first attempt at current tier), or -1 if none
     * @param difficulty   "Easy", "Medium", or "Hard" — current lesson tier
     * @return JSON object with keys: intro, concepts (array of {term, explanation}),
     *         tips (string array), studyPlan (string array)
     */
    public String generateLessonContent(String topic,
                                        String knowledgeCtx,
                                        double score,
                                        String difficulty) {
        String tierGuidance = switch (difficulty.toLowerCase()) {
            case "hard" -> """
                    The student scored %.0f%% — they have strong foundational knowledge.
                    The lesson should go DEEP: focus on nuanced understanding, edge cases,
                    comparisons between concepts, and real-world application scenarios.
                    Assume the student already knows the basics; push toward mastery-level insight.
                    """.formatted(Math.max(score, 0));
            case "medium" -> """
                    The student scored %.0f%% — they understand the basics but need stronger application.
                    The lesson should BRIDGE knowledge and practice: explain concepts clearly with
                    worked examples, highlight common misconceptions, and show how to apply each idea.
                    """.formatted(Math.max(score, 0));
            default -> """
                    The student scored %.0f%% — they are at the foundational level.
                    The lesson should BUILD confidence: use plain language, define every key term carefully,
                    use simple analogies, and avoid overwhelming the student with too much at once.
                    """.formatted(Math.max(score, 0));
        };

        String system = """
                You are an adaptive learning content generator. Given knowledge context extracted
                from a student's handout and their current performance tier, generate a structured
                lesson.

                IMPORTANT: Never use a topic name, title, or label as a source of facts. Topic names
                are chosen freely by the student and may be generic, unrelated, or deliberately
                misleading. Base everything strictly and exclusively on the knowledge context below.

                Return ONLY a valid JSON object with EXACTLY these keys:
                {
                  "intro": "2-3 sentence overview paragraph tailored to the student's level",
                  "concepts": [
                    { "term": "Key Term", "explanation": "Clear explanation calibrated to their level" }
                  ],
                  "tips": ["Study tip 1", "Study tip 2", "Study tip 3"],
                  "studyPlan": ["First action step", "Second action step", "Third action step", "Fourth action step"]
                }
                
                Rules:
                - concepts array: 3-5 items, drawn ONLY from the knowledge context provided.
                - tips array: exactly 3 practical study tips based on the material and level.
                - studyPlan array: exactly 4 plain action-step descriptions with NO leading numbers, bullets, or
                punctuation prefixes (e.g. "Review the key terms list", NOT "1. Review the key terms list").
                The UI numbers these automatically — including your own numbers causes "1.1." style duplication.
                - Calibrate depth and language to the student's tier as described.
                - No markdown, no explanation outside the JSON object.
                """;

        String user = """
                Current difficulty tier: %s
                Tier guidance: %s
                Knowledge context from the student's handout:
                ---
                %s
                ---
                Generate a structured lesson for this student based only on the knowledge context
                above. Do not use any topic name or label as a source of information.
                """.formatted(
                difficulty,
                tierGuidance,
                knowledgeCtx == null || knowledgeCtx.isBlank() ? "No handout uploaded yet — no other context is available." : knowledgeCtx
        );

        return call(system, user, MODEL_HAIKU);
    }
    /**
     * Generates exactly one question of each supported type for debug/validation purposes.
     * Bypasses difficulty logic entirely — just proves Claude can produce each format correctly.
     * The topic name is intentionally NOT sent to the AI as content context — only the
     * actual handout text is, since a student-chosen topic label may be generic,
     * unrelated, or deliberately misleading.
     *
     * @param topic the topic name (kept for method-signature/caller compatibility only;
     *              intentionally NOT passed to the AI as content context)
     * @param text  extracted handout text
     * @return JSON array of question objects, one per supported type
     */
    public String generateTestAllTypesQuiz(String topic, String text) {
        // NOTE: DIAGRAM is intentionally excluded from this method. It used to be
        // generated here from nearby caption text (unreliable — Claude would invent
        // plausible-sounding labels with no real grounding). It is now generated
        // separately by generateDiagramQuestion(), which shows Claude the ACTUAL
        // extracted image, only when one was found in the material's PDF.
        String system = """
            You are a quiz generator for an adaptive learning system running in DEBUG/TEST mode.

            IMPORTANT: Never use a topic name, title, or label as a source of facts. Topic names are
            chosen freely by the student and may be generic, unrelated, or deliberately misleading.
            Base every question strictly and exclusively on the handout text provided below.

            Your job is to produce EXACTLY ONE question of EACH of the following types to validate
            that the system can handle all question formats correctly.

            Types to generate (one each):
              MCQ,
              TRUEFALSE,
              MATCHING,
              FILLBLANK,
              ESSAY,
              SORTING,
              CONCEPTID

            Return ONLY a valid JSON array with one object per type. No markdown, no explanation, no preamble.

            Each object must have these COMMON fields:
              "type": one of the types listed above
              "questionText": the question or prompt shown to the student
              "hint": one sentence hint
              "explanation": one sentence explanation of the correct answer

            Then type-specific fields in a "payload" object:

            MCQ:
              payload: { "options": ["opt1","opt2","opt3","opt4"], "correctAnswer": "full text of correct option" }

            TRUEFALSE:
              payload: { "correctAnswer": "True" or "False" }

            MATCHING (4 pairs minimum, shuffle rightItems order so it is not 1-to-1):
              payload: { "leftItems": ["term1","term2","term3","term4"], "rightItems": ["def2","def4","def1","def3"], "correctPairs": [[0,2],[1,3],[2,0],[3,1]] }
              (correctPairs are [leftIndex, rightIndex] — rightItems must be shuffled)

            FILLBLANK (use an exact verbatim sentence from the handout, replace 2-3 key words with {{1}} {{2}} {{3}}):
              payload: { "excerpt": "exact sentence with {{1}} and {{2}} placeholders", "blanks": [{"id":1,"answer":"word1"},{"id":2,"answer":"word2"}], "mode": "dragdrop" }

            ESSAY:
              payload: { "rubric": ["point1","point2","point3"] }

            SORTING (two clearly distinct categories from the material, 3 items each):
              payload: { "categoryA": "Category Name A", "categoryB": "Category Name B", "items": [{"text":"item1","correctCategory":"A"},{"text":"item2","correctCategory":"B"},{"text":"item3","correctCategory":"A"},{"text":"item4","correctCategory":"B"},{"text":"item5","correctCategory":"A"},{"text":"item6","correctCategory":"B"}] }

            CONCEPTID (four clues pointing to one concept, two wrong options and one correct):
              payload: { "clues": ["clue1","clue2","clue3","clue4"], "options": ["wrong1","wrong2","correctAnswer"], "correctAnswer": "concept name" }
            """;

        String user = """
            Handout text:
            ---
            %s
            ---

            Generate exactly one question of each type listed. Make every question genuinely based
            on the handout content above — do not invent facts not present in the text. Ignore any
            topic name or label; it is student-provided and may be inaccurate or misleading.
            If a KNOWLEDGE GUIDE section appears above the handout text, use it only to identify
            which concepts to cover — every question must be verifiable against the Handout Text.
            """.formatted(text);

        return call(system, user, MODEL_SONNET);
    }

    /**
     * Generates a DIAGRAM-type quiz question grounded in an ACTUAL image
     * extracted from the student's uploaded PDF (see
     * MaterialController.extractDiagramImage). Claude looks at the real
     * image and identifies genuine labeled parts, instead of inventing
     * plausible-sounding labels from nearby caption text — which is what
     * made the old text-only DIAGRAM generation unreliable.
     *
     * The returned JSON deliberately does NOT include the image itself —
     * the caller already holds the base64 bytes and should combine them
     * with "labels" to build the final question payload. The topic name is
     * intentionally NOT sent to the AI — the image itself is the only
     * source of truth, since a student-chosen topic label may be generic,
     * unrelated, or deliberately misleading.
     *
     * This produces real quiz content, so it stays on the reasoning model.
     *
     * @param topic       the topic name (kept for method-signature/caller compatibility
     *                    only; intentionally NOT passed to the AI as content context)
     * @param imageBase64 base64-encoded PNG bytes of the extracted diagram
     * @return JSON object string: { "questionText": "...", "hint": "...",
     *         "explanation": "...", "labels": [{"id":1,"answer":"..."}] }
     */
    public String generateDiagramQuestion(String topic, String imageBase64) {
        String system = """
                You are a quiz generator for an adaptive learning system. You will be shown
                an image of a diagram extracted directly from a student's uploaded handout.

                Look CAREFULLY at the actual image. Identify 3-6 distinct labeled parts,
                components, or regions that are genuinely visible in the diagram — do not
                invent parts that are not actually drawn or labeled in the image.

                Return ONLY a valid JSON object. No markdown, no explanation, no preamble.
                {
                  "questionText": "instruction telling the student to label the numbered parts of the diagram shown",
                  "hint": "one sentence hint",
                  "explanation": "one sentence explanation of what the diagram shows overall",
                  "labels": [
                    {"id": 1, "answer": "name of part 1 exactly as it would appear"},
                    {"id": 2, "answer": "name of part 2"}
                  ]
                }

                Number the labels in a sensible reading order. Only include parts you can
                actually identify from the image — if you genuinely cannot make out distinct
                labeled parts, return an empty labels array rather than guessing.
                """;

        String user = """
                The attached image is a diagram extracted directly from the student's uploaded
                handout. Identify its genuinely labeled parts and generate a labeling question as
                instructed, based only on what is actually visible in the image. Do not use any
                topic name or label as a source of information.
                """;

        return callWithImage(system, user, imageBase64, MODEL_SONNET);
    }

    /**
     * Summarise the ACTUAL uploaded handout content (not just the topic name)
     * into a short 1-2 sentence blurb shown on Quiz Hub / Learning Hub cards.
     * Grounded entirely in the real handout text — the student-chosen topic
     * label is intentionally NOT sent to the AI, since it may be generic,
     * unrelated, or deliberately misleading and should never be treated as
     * a source of facts about the document's actual content.
     *
     * Lightweight summarization task — routed to Haiku.
     *
     * @param topic        the topic name (kept for method-signature/caller compatibility only;
     *                     intentionally NOT passed to the AI as content context)
     * @param materialText extracted text from the uploaded handout
     * @return 1-2 sentence plain-text summary of what the material actually covers
     */
    // Fixed, small set of broad curricular "super-categories" used to tag
    // uploaded material. See categorizeMaterial() javadoc for why this is a
    // closed list rather than ad hoc/free-form categorization.
    public static final List<String> MATERIAL_CATEGORIES = List.of(
            "Mathematics & Quantitative Reasoning",
            "Computer Science & Programming",
            "Engineering & Applied Sciences",
            "Natural Sciences",
            "Business, Economics & Management",
            "Social Sciences",
            "Humanities & Languages",
            "Health & Medical Sciences",
            "Law & Legal Studies",
            "General / Other"
    );

    /**
     * Classifies an uploaded handout into ONE of the fixed MATERIAL_CATEGORIES
     * super-categories, plus an optional short, specific sub-label.
     *
     * Deliberately a CLOSED list rather than letting Claude invent a category
     * name per upload:
     *   - The frontend renders categories as stable filter tabs (Quiz Hub's
     *     category tabs, Admin's Content Review). A free-form category per
     *     upload would create one throwaway tab per file instead of a
     *     consistent, navigable set that holds up across many uploads.
     *   - A fixed list stays stable across re-uploads of similar material —
     *     ad hoc labels can drift ("Pointers" vs "Pointers Intro") even for
     *     near-identical content, which breaks filtering over time.
     *   - 10 categories is small enough to act as tabs/chips, broad enough
     *     (mirrors standard course-catalog subject clusters) that any
     *     handout can be confidently placed in one without forcing an
     *     awkward fit.
     *
     * The sub-label is plain display text only (e.g. "Data Structures",
     * "Cellular Respiration") — it adds specificity without becoming a
     * second filterable dimension the UI has to manage, so categories don't
     * multiply uncontrollably.
     *
     * Grounded entirely in the actual handout text. The student-chosen topic
     * label is intentionally NOT sent to the AI as content context — it may
     * be generic, unrelated, or deliberately misleading.
     *
     * Lightweight classification task — routed to Haiku.
     *
     * @param materialText extracted text from the uploaded handout
     * @return JSON object string: { "category": "<one of MATERIAL_CATEGORIES exactly>",
     *         "subLabel": "<short specific label, or empty string>" }
     */
    public String categorizeMaterial(String materialText) {
        String categoryList = String.join("\n", MATERIAL_CATEGORIES.stream().map(c -> "- " + c).toList());

        String system = """
                You are a content classifier for an academic learning system.

                IMPORTANT: You are not given a topic name, and you must not assume or guess one.
                A student-provided topic label is not reliable — it may be generic, unrelated to the
                actual content, or deliberately misleading. Base classification ONLY on what is
                actually written in the handout text provided below.

                Classify the handout into EXACTLY ONE of these fixed categories (use the exact text):
                %s

                Choose "General / Other" only if the material genuinely does not fit any other
                category, or if there is not enough readable content to tell.

                Also provide a short, specific sub-label (2-4 words) naming the actual subject
                covered within that category (e.g. "Data Structures", "Cellular Respiration",
                "Contract Law Basics"). Leave it as an empty string if the content is too sparse
                or generic to name something more specific than the category itself.

                Return ONLY a valid JSON object. No markdown, no explanation, no preamble.
                { "category": "<exact category text from the list above>", "subLabel": "<short specific label or empty string>" }
                """.formatted(categoryList);

        String user = """
                Handout text:
                ---
                %s
                ---

                Classify this handout now, based solely on the text above.
                """.formatted(
                materialText == null || materialText.isBlank()
                        ? "No readable text was extracted from this file."
                        : (materialText.length() > 4000 ? materialText.substring(0, 4000) : materialText)
        );

        return call(system, user, MODEL_HAIKU);
    }

    public String summariseMaterialContent(String topic, String materialText) {
        String system = """
                You are a study assistant. Summarise the handout text below into exactly 1-2 sentences
                describing what THIS SPECIFIC document covers — its actual content, key concepts, or
                subject matter as written.

                IMPORTANT: You are not given a topic name, and you must not assume or guess one.
                A student-provided topic label is not reliable — it may be generic, unrelated to the
                actual content, or deliberately misleading. Base the summary ONLY on what is actually
                written in the handout text provided below.
                Plain English, no markdown, no bullet points, no labels or preamble.
                """;

        String user = """
                Handout text:
                ---
                %s
                ---

                Write a 1-2 sentence summary of what this handout actually covers, based solely on
                the text above.
                """.formatted(
                materialText == null || materialText.isBlank()
                        ? "No readable text was extracted from this file."
                        : (materialText.length() > 4000 ? materialText.substring(0, 4000) : materialText)
        );

        return call(system, user, MODEL_HAIKU);
    }
    public String generateMixedQuestions(String topic, String text, String difficulty) {
        String difficultyGuidance = switch (difficulty.toLowerCase()) {
            case "hard" -> "Lean toward ESSAY, typed FILLBLANK, and CONCEPTID. MCQ should be a minority. No trivial recall.";
            case "medium" -> "Mix MCQ, MATCHING, SORTING, drag-drop FILLBLANK, TRUEFALSE, and some CONCEPTID. Balance recall with application.";
            default -> "Lean toward MCQ, TRUEFALSE, MATCHING, SORTING, and drag-drop FILLBLANK. Keep questions confidence-building.";
        };

        String system = """
            You are a quiz generator for an adaptive learning system.

            IMPORTANT: Never use a topic name, title, or label as a source of facts. Topic names are
            chosen freely by the student and may be generic, unrelated, or deliberately misleading.
            Base every question strictly and exclusively on the handout text provided below.

            If the provided text begins with a KNOWLEDGE GUIDE section, use it only to identify
            which concepts to prioritise — every question must still be verifiable against the
            actual Handout Text that follows the guide, not against the guide itself.

            Generate a mixed set of 10 questions from the handout text. Each question MUST have a "type" field.
            Choose types appropriate to the material content — do NOT force a type if the material doesn't support it.
            SORTING/CLASSIFICATION only if the material genuinely contrasts two distinct categories.

            Return ONLY a valid JSON array. No markdown, no explanation, no preamble.

            Each object must have these COMMON fields:
              "type": one of [MCQ, TRUEFALSE, MATCHING, FILLBLANK, ESSAY, SORTING, CONCEPTID]
              "questionText": the question or prompt shown to the student
              "hint": one sentence hint
              "explanation": one sentence explanation of the correct answer

            Then type-specific fields in a "payload" object:

            MCQ:
              payload: { "options": ["A","B","C","D"], "correctAnswer": "full text of correct option" }

            TRUEFALSE:
              payload: { "correctAnswer": "True" or "False" }

            MATCHING:
              payload: { "leftItems": ["term1","term2",...], "rightItems": ["def1","def2",...], "correctPairs": [[0,1],[1,0],...] }
              (correctPairs are [leftIndex, rightIndex] pairs)

            FILLBLANK:
              payload: { "excerpt": "exact verbatim sentence from handout with {{1}} {{2}} placeholders", "blanks": [{"id":1,"answer":"word"},{"id":2,"answer":"word"}], "mode": "typed" for Hard or "dragdrop" for Easy/Medium }

            ESSAY:
              payload: { "rubric": ["point1","point2","point3"] }

            SORTING:
              payload: { "categoryA": "label A", "categoryB": "label B", "items": [{"text":"item","correctCategory":"A"},{"text":"item2","correctCategory":"B"}] }

            CONCEPTID (Four Ideas – One Name):
              payload: { "clues": ["clue1","clue2","clue3","clue4"], "correctAnswer": "concept name" }
            """;

        String user = """
            Difficulty: %s
            Difficulty guidance: %s

            Handout text:
            ---
            %s
            ---

            Generate 10 mixed-type questions appropriate to this material and difficulty. Do not use
            any topic name or label as a source of information — rely only on the handout text above.
            If a KNOWLEDGE GUIDE section appears above the handout text, use it only to know which
            concepts to focus on — every question must still be verifiable against the Handout Text.
            """.formatted(difficulty, difficultyGuidance, text);

        return call(system, user, MODEL_SONNET);
    }

    /**
     * Categorize a list of quiz questions into the 5 performance categories
     * used by the Quiz Results / quizfinish page:
     *   Terminology, Computation, Application, Analysis, Process Steps
     *
     * Each question is classified based on its text and type — no external
     * source text is needed; the cognitive demand is inferred from the
     * question itself.
     *
     * Lightweight tagging task — routed to Haiku.
     *
     * @param questions  list of maps, each containing "id", "questionText", "type"
     * @return JSON array: [{"id": <same id>, "category": "<one of the 5 categories>"}, ...]
     */
    public String categorizeQuestions(List<Map<String, String>> questions) {
        String system = """
                You are a quiz analyst for an adaptive learning system.
                You will receive a list of quiz questions. For each question, decide which of these
                5 performance categories it belongs to — choose the SINGLE best fit:

                  Terminology     — Tests knowledge of definitions, vocabulary, or naming.
                                    e.g. "What does X stand for?", "Define Y", "Which term means Z?"
                  Computation     — Requires numerical calculation, formula application, or step-by-step
                                    mathematical or logical working.
                                    e.g. "Calculate X", "What is the result of Y?", "How many Z?"
                  Application     — Requires using a concept in a real or novel scenario.
                                    e.g. "In situation X, what would you do?", "Which approach fits Y?"
                  Analysis        — Requires breaking down, comparing, evaluating, or interpreting.
                                    e.g. "Why does X happen?", "What is the difference between X and Y?",
                                    "What can be concluded from Z?"
                  Process Steps   — Tests knowledge of the correct order, sequence, or procedure.
                                    e.g. "What is the FIRST step?", "Which comes AFTER X?",
                                    "Put these steps in order", "What is the correct procedure for Z?"

                Categorize every question. When in doubt between two categories, pick the one that
                best reflects the PRIMARY cognitive demand of the question.

                Return ONLY a valid JSON array. No markdown, no explanation, no preamble.
                Each object: { "id": <question id exactly as given>, "category": "<category name>" }
                """;

        StringBuilder sb = new StringBuilder();
        sb.append("Categorize each of these quiz questions:\n\n");
        for (Map<String, String> q : questions) {
            sb.append("ID: ").append(q.getOrDefault("id", "?")).append("\n");
            sb.append("Type: ").append(q.getOrDefault("type", "MCQ")).append("\n");
            sb.append("Question: ").append(q.getOrDefault("questionText", "")).append("\n\n");
        }

        return call(system, sb.toString(), MODEL_HAIKU);
    }

    /**
     * Grades a single ESSAY-type quiz answer.
     *
     * Unlike every other question type, essays have no single "correct"
     * string to compare against — only a rubric of points the answer should
     * cover. The AI is given FULL authority to decide the numeric score
     * (0-100) based on how well the student's written answer addresses the
     * rubric; this is not reduced to a binary correct/wrong judgment.
     *
     * Output quality here directly affects the student's grade, so this
     * stays on the reasoning model.
     *
     * @param questionText  the essay prompt shown to the student
     * @param rubricPoints  the rubric points the answer is expected to cover
     * @param studentAnswer the student's actual written response (may be blank)
     * @return JSON object string:
     *         {
     *           "score": <integer 0-100, AI's full discretion>,
     *           "feedback": "<2-3 sentence explanation of the score>",
     *           "metRubricPoints": ["point text the answer covered well", ...],
     *           "missedRubricPoints": ["point text the answer missed or covered weakly", ...]
     *         }
     */
    public String gradeEssay(String questionText, List<String> rubricPoints, String studentAnswer) {
        String rubricText = (rubricPoints == null || rubricPoints.isEmpty())
                ? "No rubric provided — grade based on general relevance, accuracy, and depth of reasoning."
                : String.join("; ", rubricPoints);

        String system = """
                You are an expert grader for an adaptive learning system. You are grading a student's
                written short-answer / essay response.

                You have FULL AUTHORITY over the numeric score. Do NOT default to a binary 0 or 100,
                and do NOT just count rubric points mechanically. Use your own judgment as an expert
                grader would: weigh accuracy, completeness, depth of reasoning, and clarity. Partial
                credit is expected and encouraged where the answer shows partial understanding.

                Grading guidance:
                  - A blank, off-topic, or nonsensical answer should score very low (0-10).
                  - An answer that is mostly correct but shallow, incomplete, or missing nuance should
                    land in the middle range (40-70) depending on how much it actually covers.
                  - A thorough, accurate, well-reasoned answer that addresses the rubric should score
                    high (80-100), even if the wording differs from the rubric, as long as the
                    underlying understanding is correct.
                  - You decide the exact number — there is no fixed formula. Trust your judgment.

                Return ONLY a valid JSON object. No markdown, no explanation, no preamble.
                {
                  "score": <integer 0-100>,
                  "feedback": "2-3 sentences of constructive, specific feedback explaining the score, written directly to the student",
                  "metRubricPoints": ["short phrase for each rubric point the answer addressed well"],
                  "missedRubricPoints": ["short phrase for each rubric point the answer missed or addressed poorly"]
                }
                """;

        String user = """
                Essay question: %s

                Rubric (what a strong answer should cover): %s

                Student's answer:
                ---
                %s
                ---

                Grade this answer now. Assign whatever score (0-100) you judge it truly deserves.
                """.formatted(
                questionText,
                rubricText,
                (studentAnswer == null || studentAnswer.isBlank()) ? "(The student left this blank.)" : studentAnswer
        );

        return call(system, user, MODEL_SONNET);
    }

    public String extractKnowledgeRepresentation(String materialText) {
        String system = """
            You are a content-distillation engine for an adaptive learning system.

            IMPORTANT: Base everything strictly and exclusively on the handout text
            provided below. Do not invent facts not present in the text.

            Produce a compact structured knowledge representation of the handout,
            detailed enough to generate quiz questions from LATER without needing
            the original full text again.

            Return ONLY a valid JSON object. No markdown, no explanation, no preamble.
            {
              "summary": "3-5 sentence overview of what this handout covers",
              "concepts": [
                {"term": "Key term or concept", "definition": "Clear definition or explanation"}
              ],
              "learningObjectives": ["objective 1", "objective 2"],
              "relationships": ["how concept A relates to concept B"],
              "keyExcerpts": ["verbatim sentence copied exactly from the handout"]
            }

            Rules:
            - concepts: 6-15 items.
            - learningObjectives: 3-6 items.
            - relationships: 2-6 items.
            - keyExcerpts: 6-12 sentences copied VERBATIM (word-for-word) from the
              handout text below — these are reused later to build fill-in-the-blank
              and diagram-style questions, so they must match the source exactly.
            """;

        String user = """
            Handout text:
            ---
            %s
            ---

            Extract the structured knowledge representation now, based solely on the
            text above.
            """.formatted(
                materialText == null || materialText.isBlank()
                        ? "No readable text was extracted from this file."
                        : (materialText.length() > 8000 ? materialText.substring(0, 8000) : materialText)
        );

        return call(system, user, MODEL_HAIKU);
    }
}