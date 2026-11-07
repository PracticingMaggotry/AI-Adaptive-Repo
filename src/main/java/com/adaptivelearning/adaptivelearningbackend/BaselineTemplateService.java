package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lets students who send the same handout share one AI-written first quiz. Each student still gets their own copy
 * of every question (own rows, own scope), exactly like the copy made for assigned study handouts; only the AI call
 * is shared. See {@link BaselineTemplate} for when a template may be used.
 */
@Service
public class BaselineTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private BaselineTemplateRepository templates;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private StudyScopeRepository scopeRepository;
    @Autowired private QuestionSetService questionSetService;

    /** Copies the stored first quiz to this student. Returns the number of questions saved; 0 means no usable template. */
    public int apply(String contentHash, String tier, String ownerId, String topic, StudyScope scope) {
        if (!shareable(contentHash)) return 0;
        try {
            Optional<BaselineTemplate> t = templates.findByContentHashAndTierAndPromptVersion(
                    contentHash, tier, AiPolicy.PROMPT_VERSION);
            if (t.isEmpty()) return 0;
            List<Map<String, Object>> rows = MAPPER.readValue(t.get().getQuestionsJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            List<Question> copies = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Question q = new Question();
                q.setTopic(topic);
                q.setDifficulty(str(r, "difficulty"));
                q.setQuestionText(str(r, "questionText"));
                q.setOptionA(str(r, "optionA"));
                q.setOptionB(str(r, "optionB"));
                q.setOptionC(str(r, "optionC"));
                q.setOptionD(str(r, "optionD"));
                q.setCorrectAnswer(str(r, "correctAnswer"));
                q.setHint(str(r, "hint"));
                q.setExplanation(str(r, "explanation"));
                if (str(r, "type") != null) q.setType(str(r, "type"));
                q.setPayload(str(r, "payload"));
                q.setOwnerId(ownerId);
                q.setArchived(false);
                q.setScopeId(scope.getId());
                q.setConceptKey(str(r, "conceptKey"));
                q.setPurpose(str(r, "purpose"));
                Object bloom = r.get("bloomLevel");
                q.setBloomLevel(bloom instanceof Number n ? n.intValue() : null);
                q.setCategory(str(r, "category"));
                copies.add(q);
            }
            if (copies.isEmpty()) return 0;
            // Replaces the topic's current set atomically (the caller no longer clears it first), so a failed save
            // leaves the student's existing questions exactly as they were.
            return questionSetService.replaceSet(ownerId, topic, copies);
        } catch (QuestionSetService.ReplaceFailedException e) {
            throw e; // the template was fine, the save failed: let the caller stop instead of paying for a fresh AI quiz
        } catch (Exception e) {
            System.err.println("Baseline template unusable, generating instead (non-fatal): " + e.getMessage());
            return 0;
        }
    }

    /** Remembers the first quiz just written for a student who started from nothing. Never fails the upload. */
    public void save(String contentHash, String tier, List<Question> questions) {
        if (!shareable(contentHash) || questions == null || questions.isEmpty()) return;
        try {
            if (templates.findByContentHashAndTierAndPromptVersion(contentHash, tier, AiPolicy.PROMPT_VERSION).isPresent()) return;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Question q : questions) {
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("difficulty", q.getDifficulty());
                r.put("questionText", q.getQuestionText());
                r.put("optionA", q.getOptionA());
                r.put("optionB", q.getOptionB());
                r.put("optionC", q.getOptionC());
                r.put("optionD", q.getOptionD());
                r.put("correctAnswer", q.getCorrectAnswer());
                r.put("hint", q.getHint());
                r.put("explanation", q.getExplanation());
                r.put("type", q.getType());
                r.put("payload", q.getPayload());
                r.put("conceptKey", q.getConceptKey());
                r.put("purpose", q.getPurpose());
                r.put("bloomLevel", q.getBloomLevel());
                r.put("category", q.getCategory());
                rows.add(r);
            }
            templates.save(new BaselineTemplate(contentHash, tier, AiPolicy.PROMPT_VERSION, MAPPER.writeValueAsString(rows)));
        } catch (DataIntegrityViolationException raced) {
            // another upload stored the same template a moment ago: fine
        } catch (Exception e) {
            System.err.println("Baseline template not stored (non-fatal): " + e.getMessage());
        }
    }

    /** Drops every stored first quiz of a handout (its content was released, or one of its questions was reported). */
    public void dropFor(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) return;
        try {
            templates.deleteAll(templates.findByContentHash(contentHash));
        } catch (Exception e) {
            System.err.println("Could not drop baseline template (non-fatal): " + e.getMessage());
        }
    }

    /** A reported question may sit in the shared first quiz: stop handing that quiz out until it is regenerated. */
    public void dropForQuestion(Question q) {
        if (q == null || q.getScopeId() == null) return;
        scopeRepository.findById(q.getScopeId()).ifPresent(s -> dropFor(s.getContentHash()));
    }

    private static boolean shareable(String contentHash) {
        return contentHash != null && !contentHash.isBlank() && !contentHash.startsWith("unique-");
    }

    private static String str(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? null : v.toString();
    }
}
