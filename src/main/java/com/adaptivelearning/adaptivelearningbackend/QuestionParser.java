package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.adaptivelearning.adaptivelearningbackend.ai.TextSimilarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts a Claude-generated JSON array of question objects into saveable Question entities. */
public class QuestionParser {

    /** Parsed questions plus any rejection reasons. */
    public static class ParseResult {
        public final List<Question> questions = new ArrayList<>();
        public final List<String> droppedReasons = new ArrayList<>();
        /** Slot number of each entry of {@link #questions}, same order, -1 if unmatched. Filled by parseBlueprint only. */
        public final List<Integer> slotIndexes = new ArrayList<>();
        /**
         * Valid questions held back by the remediation checks (wrong type, near-copy), first one per slot. They are
         * the fallback when a regeneration also fails, so the checks can improve a quiz but never shorten it.
         */
        public final Map<Integer, Question> softRejected = new LinkedHashMap<>();
        public final Map<Integer, String> softRejectedReasons = new LinkedHashMap<>();
    }

    /**
     * Checks applied to REMEDIATE slots by {@link #parseBlueprint}: {@code priorSignatures} is the content signature
     * (see TextSimilarity) of every question the student already missed, per concept key.
     */
    public record Checks(Map<String, List<String>> priorSignatures) {}

    /** Parses a Claude-generated JSON array into Question entities, dropping any that fail validation. */
    public static ParseResult parse(JsonNode array, String sourceText, String ownerId,
                                    String topic, String difficulty, String dropLogPrefix) {
        ParseResult result = new ParseResult();
        if (array == null || !array.isArray()) return result;

        for (JsonNode node : array) {
            String rejection = QuestionValidator.validate(node, sourceText);
            if (rejection != null) {
                result.droppedReasons.add(rejection);
                System.out.println(dropLogPrefix + rejection);
                continue;
            }

            Question q = buildQuestion(node, ownerId, topic, difficulty);
            if (!q.getQuestionText().isBlank()) {
                result.questions.add(q);
            }
        }
        return result;
    }

    private static final java.util.Set<String> CATEGORIES = java.util.Set.of(
            "Terminology", "Computation", "Application", "Analysis", "Process Steps");

    /**
     * Parses a blueprint-driven generation. The server's slot assignment is authoritative: each question is tagged with
     * the concept and purpose of the slot it answers (matched by the model's "slot" number, else by position), never
     * with whatever concept the model claims. Questions that fail validation are dropped as in {@link #parse}.
     */
    public static ParseResult parseBlueprint(JsonNode array, String sourceText, String ownerId, String topic,
                                             String difficulty, String dropLogPrefix, Long scopeId,
                                             List<ClaudeService.SlotSpec> slots) {
        return parseBlueprint(array, sourceText, ownerId, topic, difficulty, dropLogPrefix, scopeId, slots, null);
    }

    /**
     * As above, plus (when {@code checks} is not null) the remediation checks: one question per slot, the assigned
     * type respected, no true/false where the plan forbids it, and no REMEDIATE question that is a near-copy of a
     * mistake the student already made or of another question of the same concept in this quiz.
     */
    public static ParseResult parseBlueprint(JsonNode array, String sourceText, String ownerId, String topic,
                                             String difficulty, String dropLogPrefix, Long scopeId,
                                             List<ClaudeService.SlotSpec> slots, Checks checks) {
        ParseResult result = new ParseResult();
        if (array == null || !array.isArray()) return result;
        java.util.Map<Integer, ClaudeService.SlotSpec> byIndex = new java.util.HashMap<>();
        for (ClaudeService.SlotSpec s : slots) byIndex.put(s.index(), s);
        Map<String, List<String>> acceptedSignatures = new HashMap<>();

        int position = 0;
        for (JsonNode node : array) {
            position++;
            String rejection = QuestionValidator.validate(node, sourceText);
            if (rejection != null) {
                result.droppedReasons.add(rejection);
                System.out.println(dropLogPrefix + rejection);
                continue;
            }
            Question q = buildQuestion(node, ownerId, topic, difficulty);
            if (q.getQuestionText().isBlank()) continue;

            ClaudeService.SlotSpec slot = byIndex.get(node.path("slot").asInt(-1));
            if (slot == null) slot = byIndex.get(position);
            if (checks != null && slot != null && result.slotIndexes.contains(slot.index())) {
                String reason = "slot " + slot.index() + " already has a question; extra one dropped";
                result.droppedReasons.add(reason);
                System.out.println(dropLogPrefix + reason);
                continue;
            }
            q.setScopeId(scopeId);
            if (slot != null) {
                q.setConceptKey(slot.conceptKey());
                q.setPurpose(slot.purpose());
                if (slot.demand() > 0) q.setDemandLevel(slot.demand());
            }
            int bloom = node.path("bloom").asInt(0);
            if (bloom >= 1 && bloom <= 6) q.setBloomLevel(bloom);
            String cat = node.path("category").asText("").trim();
            if (CATEGORIES.contains(cat)) q.setCategory(cat);

            if (checks != null && slot != null && "REMEDIATE".equals(slot.purpose())) {
                String reason = remediationRejection(q, slot, checks, acceptedSignatures);
                if (reason != null) {
                    result.droppedReasons.add(reason);
                    System.out.println(dropLogPrefix + reason);
                    result.softRejected.putIfAbsent(slot.index(), q);
                    result.softRejectedReasons.putIfAbsent(slot.index(), reason);
                    continue;
                }
                acceptedSignatures.computeIfAbsent(slot.conceptKey(), k -> new ArrayList<>()).add(signatureOf(q));
            }
            result.questions.add(q);
            result.slotIndexes.add(slot == null ? -1 : slot.index());
        }
        return result;
    }

    /** Content signature of a generated question, for near-copy comparison. */
    static String signatureOf(Question q) {
        return TextSimilarity.signature(q.getType(), q.getQuestionText(), q.getCorrectAnswer(), q.getPayload());
    }

    /** Question types whose signature includes the stored payload, and which therefore echo handout wording by nature. */
    private static boolean usesPayload(String type) {
        return !(type.equals("MCQ") || type.equals("TRUEFALSE") || type.equals("PRACTICAL") || type.isEmpty());
    }

    /** Why a REMEDIATE question must be regenerated, or null if it is fine. */
    private static String remediationRejection(Question q, ClaudeService.SlotSpec slot, Checks checks,
                                               Map<String, List<String>> acceptedSignatures) {
        String type = q.getType() == null ? "" : q.getType().toUpperCase(Locale.ROOT);
        if (slot.type() != null && !slot.type().equalsIgnoreCase(type)) {
            return "wrong type for slot " + slot.index() + ": wanted " + slot.type() + ", got " + type;
        }
        if (slot.role() != null && slot.type() == null && type.equals("TRUEFALSE")) {
            return "true/false is not allowed for remediation slot " + slot.index();
        }
        List<String> against = new ArrayList<>(checks.priorSignatures().getOrDefault(slot.conceptKey(), List.of()));
        against.addAll(acceptedSignatures.getOrDefault(slot.conceptKey(), List.of()));
        double best = TextSimilarity.maxSimilarity(signatureOf(q), against, TextSimilarity.tokens(slot.term()));
        double limit = usesPayload(type) ? AiPolicy.NEAR_COPY_THRESHOLD_PAYLOAD_TYPES : AiPolicy.NEAR_COPY_THRESHOLD;
        if (best >= limit) {
            return String.format(Locale.ROOT, "slot %d is a near-copy (similarity %.2f) of an earlier question on %s",
                    slot.index(), best, slot.conceptKey());
        }
        return null;
    }

    /** {@code base} plus the signatures of {@code accepted}, so a retry is also compared with what the first pass kept. */
    public static Checks checksIncluding(Checks base, List<Question> accepted) {
        Map<String, List<String>> m = new HashMap<>();
        if (base != null) base.priorSignatures().forEach((k, v) -> m.put(k, new ArrayList<>(v)));
        for (Question q : accepted) {
            if (q.getConceptKey() != null) m.computeIfAbsent(q.getConceptKey(), k -> new ArrayList<>()).add(signatureOf(q));
        }
        return new Checks(m);
    }

    /**
     * One list in slot order from one or more passes: for each slot the first clean question, else the first
     * soft-rejected one (so a failed regeneration falls back to the original rather than leaving a hole). Questions
     * the model did not tie to any slot, from the first pass, are appended as before.
     */
    public static List<Question> mergeBySlot(List<ClaudeService.SlotSpec> slots, ParseResult... passes) {
        List<Question> out = new ArrayList<>();
        for (ClaudeService.SlotSpec s : slots) {
            Question pick = null;
            for (ParseResult p : passes) {
                if (p == null) continue;
                int i = p.slotIndexes.indexOf(s.index());
                if (i >= 0) { pick = p.questions.get(i); break; }
            }
            if (pick == null) {
                for (ParseResult p : passes) {
                    if (p == null) continue;
                    Question soft = p.softRejected.get(s.index());
                    if (soft != null) { pick = soft; break; }
                }
            }
            if (pick != null) out.add(pick);
        }
        if (passes.length > 0 && passes[0] != null) {
            ParseResult first = passes[0];
            for (int i = 0; i < first.questions.size(); i++) {
                if (first.slotIndexes.get(i) < 0) out.add(first.questions.get(i));
            }
        }
        return out;
    }

    /** Builds a single Question entity from one Claude-generated JSON node. */
    public static Question buildQuestion(JsonNode node, String ownerId, String topic, String difficulty) {
        String type = node.path("type").asText("MCQ").toUpperCase();
        JsonNode payload = node.path("payload");

        Question q = new Question();
        q.setOwnerId(ownerId);
        q.setTopic(topic);
        q.setDifficulty(difficulty);
        q.setType(type);
        q.setQuestionText(node.path("questionText").asText(""));
        q.setHint(node.path("hint").asText(""));
        q.setExplanation(node.path("explanation").asText(""));

        if (type.equals("MCQ")) {
            JsonNode opts = payload.path("options");
            String optA = opts.has(0) ? opts.get(0).asText("") : "";
            String optB = opts.has(1) ? opts.get(1).asText("") : "";
            String optC = opts.has(2) ? opts.get(2).asText("") : "";
            String optD = opts.has(3) ? opts.get(3).asText("") : "";
            q.setOptionA(stripLeadingChoiceLabel(optA));
            q.setOptionB(stripLeadingChoiceLabel(optB));
            q.setOptionC(stripLeadingChoiceLabel(optC));
            q.setOptionD(stripLeadingChoiceLabel(optD));
            q.setCorrectAnswer(stripLeadingChoiceLabel(payload.path("correctAnswer").asText("")));
        } else if (type.equals("PRACTICAL")) {
            JsonNode opts = payload.path("options");
            q.setOptionA(normalizePracticalOption(opts.path(0).asText("")));
            q.setOptionB(normalizePracticalOption(opts.path(1).asText("")));
            q.setOptionC(normalizePracticalOption(opts.path(2).asText("")));
            q.setOptionD(normalizePracticalOption(opts.path(3).asText("")));
            // Stored as the option's TEXT (a bare "B" from the model is resolved here), so grading is a text match.
            int correctIdx = practicalCorrectIndex(payload);
            q.setCorrectAnswer(correctIdx >= 0 ? normalizePracticalOption(opts.path(correctIdx).asText("")) : "");
            // The worked solution is only ever shown after the student answers, as part of the explanation.
            String working = payload.path("workedSolution").asText("").trim();
            if (!working.isEmpty()) {
                String base = q.getExplanation() == null ? "" : q.getExplanation().trim();
                // Skip when the explanation already walks through the same steps (avoids a repeated "Working:" block).
                if (!explanationCoversWorking(base, working)) {
                    q.setExplanation((base.isEmpty() ? "" : base + " ") + "Working: " + working);
                }
            }
        } else if (type.equals("TRUEFALSE")) {
            q.setOptionA("True");
            q.setOptionB("False");
            q.setCorrectAnswer(payload.path("correctAnswer").asText(""));
            // The quiz UI already labels the question as True or False, so the text is only the statement.
            q.setQuestionText(QuestionTextCleaner.stripTrueFalseLabel(q.getQuestionText()));
        }

        // Fill-in-the-blank is always type-the-answer: store it as typed whatever mode the model wrote.
        if (type.equals("FILLBLANK") && payload.isObject()) {
            ((ObjectNode) payload).put("mode", "typed");
            // Each blank keeps only the accepted answers that really differ from its answer.
            for (JsonNode blank : payload.path("blanks")) {
                if (blank instanceof ObjectNode b) cleanAcceptedAnswers(b, b.path("answer").asText(""));
            }
        }
        if (type.equals("CONCEPTID") && payload instanceof ObjectNode conceptPayload) {
            cleanAcceptedAnswers(conceptPayload, conceptPayload.path("correctAnswer").asText(""));
        }

        if (!payload.isMissingNode() && !payload.isNull()) {
            q.setPayload(payload.toString());
        }

        return q;
    }


    /**
     * Rewrites {@code holder.acceptedAnswers} to the variants that are really different from {@code canonical}: a
     * variant that only differs by capitals or spacing (answer "Perspectives", accepted "perspectives") is dropped,
     * because the checker already ignores both (see {@link AnswerMatcher}). The key is removed when nothing is left.
     */
    private static void cleanAcceptedAnswers(ObjectNode holder, String canonical) {
        JsonNode raw = holder.path("acceptedAnswers");
        if (raw.isMissingNode()) return;
        List<String> variants = new ArrayList<>();
        if (raw.isArray()) {
            for (JsonNode v : raw) if (v.isTextual()) variants.add(v.asText());
        } else if (raw.isTextual()) {
            variants.add(raw.asText());
        }
        List<String> kept = AnswerMatcher.distinctVariants(canonical, variants);
        if (kept.isEmpty()) {
            holder.remove("acceptedAnswers");
            return;
        }
        ArrayNode out = holder.putArray("acceptedAnswers");
        kept.forEach(out::add);
    }

    /** True when most of the worked-solution's tokens already appear in the explanation. */
    private static boolean explanationCoversWorking(String explanation, String working) {
        if (explanation == null || explanation.isBlank()) return false;
        java.util.Set<String> have = new java.util.HashSet<>(
                java.util.Arrays.asList(explanation.toLowerCase().split("[^a-z0-9]+")));
        String[] need = working.toLowerCase().split("[^a-z0-9]+");
        int total = 0, hit = 0;
        for (String t : need) {
            if (t.isEmpty()) continue;
            total++;
            if (have.contains(t)) hit++;
        }
        return total > 0 && hit >= total * 0.6;
    }

    /** Only uppercase "A." / "A)" / "(A)" labels are stripped — "a: 1" or "b) x" can be genuine code output. */
    private static final java.util.regex.Pattern PRACTICAL_LABEL_PATTERN =
            java.util.regex.Pattern.compile("^\\(?([A-D])[.)]\\)?\\s+");

    /** Trims a PRACTICAL option and strips an accidental leading "A." / "B)" choice label. */
    public static String normalizePracticalOption(String optionText) {
        if (optionText == null) return "";
        String trimmed = optionText.trim();
        java.util.regex.Matcher m = PRACTICAL_LABEL_PATTERN.matcher(trimmed);
        return m.find() ? trimmed.substring(m.end()).trim() : trimmed;
    }

    /**
     * Index of the option that payload.correctAnswer points to, or -1 if it cannot be resolved to exactly one
     * option. Matches by text first (exact, then case-insensitive if unambiguous); a bare letter A-D is accepted
     * as a positional reference only when no option matches by text.
     */
    public static int practicalCorrectIndex(JsonNode payload) {
        JsonNode options = payload.path("options");
        if (!options.isArray() || options.size() == 0) return -1;
        String correct = normalizePracticalOption(payload.path("correctAnswer").asText(""));
        if (correct.isEmpty()) return -1;

        for (int i = 0; i < options.size(); i++) {
            if (normalizePracticalOption(options.get(i).asText("")).equals(correct)) return i;
        }
        int found = -1;
        for (int i = 0; i < options.size(); i++) {
            if (normalizePracticalOption(options.get(i).asText("")).equalsIgnoreCase(correct)) {
                if (found != -1) return -1; // ambiguous
                found = i;
            }
        }
        if (found >= 0) return found;

        if (correct.length() == 1) {
            char c = Character.toUpperCase(correct.charAt(0));
            if (c >= 'A' && c < 'A' + options.size()) return c - 'A';
        }
        return -1;
    }

    /** Strips a leading single-letter choice label (e.g. "A. ", "B) ", "(C) ") from MCQ option text. */
    private static final java.util.regex.Pattern CHOICE_LABEL_PATTERN =
            java.util.regex.Pattern.compile("^\\(?([A-Da-d])[\\.\\):]\\)?\\s+");

    static String stripLeadingChoiceLabel(String optionText) {
        if (optionText == null) return "";
        java.util.regex.Matcher m = CHOICE_LABEL_PATTERN.matcher(optionText.trim());
        return m.find() ? optionText.trim().substring(m.end()) : optionText;
    }
}