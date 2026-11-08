package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.ConceptKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Owns the identity of "which handout is this student studying". Everything the AI remembers is keyed by the scope
 * returned here, never by the topic name alone.
 */
@Service
public class ScopeService {

    public record ConceptDef(String key, String term, String definition) {}

    /** Share of concept keys two versions of a handout must have in common for evidence to carry over. */
    static final double CARRY_OVER_MIN_OVERLAP = 0.60;

    private final StudyScopeRepository scopes;
    private final FirstQuizResultRepository firstQuizResults;
    private final AttemptRepository attempts;
    private final QuestionResponseRepository responses;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScopeService(StudyScopeRepository scopes, FirstQuizResultRepository firstQuizResults,
                        AttemptRepository attempts, QuestionResponseRepository responses) {
        this.scopes = scopes;
        this.firstQuizResults = firstQuizResults;
        this.attempts = attempts;
        this.responses = responses;
    }

    public Optional<StudyScope> active(String studentId, String topic) {
        return scopes.findFirstByStudentIdAndTopicKeyAndStatusOrderByIdDesc(studentId, StudyScope.keyOf(topic), StudyScope.ACTIVE);
    }

    /**
     * Called when a handout is saved for a student. Identical content keeps the current scope (re-upload of the same
     * file changes nothing). Different content closes the old scope and opens a fresh one, so nothing is inherited
     * by topic name; concept-level evidence is carried over only when the new version still covers most of the
     * same concepts (an updated edition of the same handout).
     */
    @Transactional
    public StudyScope open(String studentId, String topic, Material material, int wordCount) {
        String hash = hashOf(material);
        List<ConceptDef> concepts = parseConcepts(material == null ? null : material.getKnowledgeExtract());
        String conceptsJson = toJson(concepts);

        Optional<StudyScope> current = active(studentId, topic);
        if (current.isPresent() && Objects.equals(current.get().getContentHash(), hash)) {
            StudyScope s = current.get();
            if ((s.getConceptsJson() == null || s.getConceptsJson().isBlank()) && !concepts.isEmpty()) {
                s.setConceptsJson(conceptsJson);
                scopes.save(s);
            }
            return s;
        }

        StudyScope fresh = new StudyScope(studentId, topic, hash, conceptsJson, wordCount);
        scopes.save(fresh);

        if (current.isPresent()) {
            StudyScope old = current.get();
            archiveBaseline(old, studentId, topic);
            old.setStatus(StudyScope.SUPERSEDED);
            old.setEndedAt(LocalDateTime.now());
            old.setSupersededBy(fresh.getId());
            scopes.save(old);
            List<String> oldKeys = parseConcepts(old.getConceptsJson()).stream().map(ConceptDef::key).toList();
            List<String> newKeys = concepts.stream().map(ConceptDef::key).toList();
            if (ConceptKey.overlap(oldKeys, newKeys) >= CARRY_OVER_MIN_OVERLAP) {
                fresh.setCarriedFromScopeId(old.getId());
                scopes.save(fresh);
            }
        }
        // A scope created by an upload starts empty: no legacy import is needed or wanted.
        fresh.setLegacyImported(true);
        return scopes.save(fresh);
    }

    /**
     * Returns the active scope, creating one for data that pre-dates scopes: the current material becomes the scope,
     * attempts made after that material was uploaded are imported as responses, and attempts/baselines from before it
     * (an older handout under the same name) are left behind instead of being inherited.
     */
    @Transactional
    public StudyScope ensure(String studentId, String topic, Material material, int wordCount) {
        Optional<StudyScope> current = active(studentId, topic);
        if (current.isPresent()) {
            // Any code path that swapped the handout without going through open() is caught here.
            if (material != null && !Objects.equals(current.get().getContentHash(), hashOf(material))) {
                return open(studentId, topic, material, wordCount);
            }
            return current.get();
        }
        StudyScope s = new StudyScope(studentId, topic, hashOf(material),
                toJson(parseConcepts(material == null ? null : material.getKnowledgeExtract())), wordCount);
        scopes.save(s);
        importLegacy(s, studentId, topic, material == null ? null : material.getUploadedAt());
        return s;
    }

    /** The student deleted the topic: close the scope so a later upload of the same name starts clean. */
    @Transactional
    public void closeOnDelete(String studentId, String topic) {
        for (StudyScope s : scopes.findByStudentIdAndTopicKeyAndStatus(studentId, StudyScope.keyOf(topic), StudyScope.ACTIVE)) {
            s.setStatus(StudyScope.DELETED);
            s.setEndedAt(LocalDateTime.now());
            scopes.save(s);
        }
    }

    /** Admin removed the topic for everyone. */
    @Transactional
    public void closeAllOnDelete(String topic) {
        for (StudyScope s : scopes.findByTopicKeyAndStatus(StudyScope.keyOf(topic), StudyScope.ACTIVE)) {
            s.setStatus(StudyScope.DELETED);
            s.setEndedAt(LocalDateTime.now());
            scopes.save(s);
        }
    }

    public List<ConceptDef> concepts(StudyScope scope) {
        return parseConcepts(scope.getConceptsJson());
    }


    private void archiveBaseline(StudyScope old, String studentId, String topic) {
        firstQuizResults.findByStudentIdAndTopicIgnoreCase(studentId, topic).ifPresent(fqr -> {
            old.setBaselineScore(fqr.getGeneralScore());
            old.setBaselineTier(fqr.getGeneralDifficulty());
            // One FirstQuizResult row per (student, topic): the new handout needs its own, unlocked baseline.
            firstQuizResults.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        });
    }

    private void importLegacy(StudyScope scope, String studentId, String topic, LocalDateTime materialUploadedAt) {
        firstQuizResults.findByStudentIdAndTopicIgnoreCase(studentId, topic).ifPresent(fqr -> {
            if (materialUploadedAt != null && fqr.getAttemptedAt() != null && fqr.getAttemptedAt().isBefore(materialUploadedAt)) {
                scope.setBaselineScore(fqr.getGeneralScore());
                scope.setBaselineTier(fqr.getGeneralDifficulty());
                firstQuizResults.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
            }
        });
        List<ConceptDef> concepts = parseConcepts(scope.getConceptsJson());
        List<QuestionResponse> imported = new ArrayList<>();
        for (Attempt a : attempts.findByStudentIdAndTopicIgnoreCase(studentId, topic)) {
            if (materialUploadedAt != null && a.getTimestamp() != null && a.getTimestamp().isBefore(materialUploadedAt)) continue;
            a.setScopeId(scope.getId());
            attempts.save(a);
            imported.addAll(LegacyAttemptImporter.toResponses(mapper, a, scope, concepts));
        }
        responses.saveAll(imported);
        scope.setLegacyImported(true);
        scopes.save(scope);
    }

    private static String hashOf(Material m) {
        if (m == null) return "none";
        if (m.getContentHash() != null && !m.getContentHash().isBlank()) return m.getContentHash();
        return "material-" + m.getId();
    }

    List<ConceptDef> parseConcepts(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(json.replaceAll("(?s)```json\\s*", "").replace("```", "").trim());
            // Either a stored scope list [{key,term,definition}] or a raw knowledge extract {concepts:[{term,definition}]}
            JsonNode arr = root.isArray() ? root : root.path("concepts");
            if (!arr.isArray()) return List.of();
            List<String> terms = new ArrayList<>(), defs = new ArrayList<>(), keys = new ArrayList<>();
            boolean stored = root.isArray();
            for (JsonNode c : arr) {
                String term = c.path("term").asText("").trim();
                if (term.isBlank()) continue;
                terms.add(term);
                defs.add(c.path("definition").asText(""));
                keys.add(stored ? c.path("key").asText("") : "");
            }
            List<String> fresh = ConceptKey.uniqueKeys(terms);
            List<ConceptDef> out = new ArrayList<>();
            for (int i = 0; i < terms.size(); i++) {
                String k = stored && !keys.get(i).isBlank() ? keys.get(i) : fresh.get(i);
                out.add(new ConceptDef(k, terms.get(i), defs.get(i)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<ConceptDef> concepts) {
        try {
            List<Map<String, String>> rows = new ArrayList<>();
            for (ConceptDef c : concepts) rows.add(Map.of("key", c.key(), "term", c.term(), "definition", c.definition() == null ? "" : c.definition()));
            return mapper.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
    }

    static ZoneId zone() { return ZoneId.systemDefault(); }
}
