package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.ConceptKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * One-time conversion of attempts recorded before the response log existed. Their per-question JSON is the only copy
 * of old mistakes; without this, students' existing history would vanish from the AI's view on upgrade. Concepts are
 * matched by word overlap (no AI call), so rows that cannot be matched confidently are stored without a concept.
 */
final class LegacyAttemptImporter {
    private LegacyAttemptImporter() {}

    static List<QuestionResponse> toResponses(ObjectMapper mapper, Attempt attempt, StudyScope scope,
                                              List<ScopeService.ConceptDef> concepts) {
        List<QuestionResponse> out = new ArrayList<>();
        if (attempt.getDetails() == null || attempt.getDetails().isBlank()) return out;
        try {
            JsonNode arr = mapper.readTree(attempt.getDetails());
            if (!arr.isArray()) return out;
            for (JsonNode q : arr) {
                String text = q.path("question").asText("");
                if (text.isBlank()) continue;
                QuestionResponse r = new QuestionResponse();
                r.setScopeId(scope.getId());
                r.setStudentId(attempt.getStudentId());
                r.setTopic(attempt.getTopic());
                r.setAttemptId(attempt.getId());
                long qid = q.path("questionId").asLong(-1L);
                if (qid > 0) r.setQuestionId(qid);
                r.setQuestionType(q.path("type").asText("mcq").toUpperCase(Locale.ROOT));
                r.setTier(attempt.getDifficulty());
                r.setCategory(q.path("weaknessCategory").asText(null));
                r.setResult(q.path("result").asText("wrong").toLowerCase(Locale.ROOT));
                double credit;
                if (q.has("creditFraction")) credit = q.path("creditFraction").asDouble(0);
                else if (q.has("essayScore")) credit = q.path("essayScore").asDouble(0) / 100.0;
                else credit = "correct".equals(r.getResult()) ? 1.0 : "partial".equals(r.getResult()) ? 0.5 : 0.0;
                r.setCredit(Math.max(0, Math.min(1, credit)));
                int secs = q.path("timeSpentSeconds").asInt(0);
                r.setTimeSpentSeconds(secs > 0 ? secs : null);
                r.setAnsweredAt(attempt.getTimestamp() == null ? java.time.LocalDateTime.now() : attempt.getTimestamp());
                r.setQuestionText(text);
                r.setStudentAnswer(q.path("yourAnswer").asText(""));
                String correct = q.path("correctAnswer").asText("");
                r.setCorrectAnswer(correct.isBlank() ? null : correct);
                StringBuilder missed = new StringBuilder();
                q.path("essayMissedRubricPoints").forEach(m -> { if (missed.length() > 0) missed.append("; "); missed.append(m.asText("")); });
                r.setMissedPoints(missed.length() == 0 ? null : missed.toString());
                String fb = q.path("essayFeedback").asText("");
                r.setFeedback(fb.isBlank() ? null : fb);
                String key = match(text, concepts);
                r.setConceptKey(key);
                r.setConceptSource(key == null ? null : "lexical");
                r.setPromptVersion("legacy");
                out.add(r);
            }
        } catch (Exception ignored) {
            // an unreadable legacy attempt is skipped; the attempt row itself is untouched
        }
        return out;
    }

    /** Best concept for a question by word overlap with the concept's term (weight 3) and definition (weight 1). */
    static String match(String questionText, List<ScopeService.ConceptDef> concepts) {
        Set<String> q = new HashSet<>(ConceptKey.tokens(questionText));
        String best = null;
        int bestScore = 0;
        for (ScopeService.ConceptDef c : concepts) {
            int termHits = 0, defHits = 0;
            for (String t : ConceptKey.tokens(c.term())) if (q.contains(t)) termHits++;
            for (String t : ConceptKey.tokens(c.definition())) if (q.contains(t)) defHits++;
            int score = termHits * 3 + Math.min(defHits, 3);
            if (termHits == 0) continue; // never match on definition words alone
            if (score > bestScore) { bestScore = score; best = c.key(); }
        }
        return bestScore >= 3 ? best : null;
    }
}
