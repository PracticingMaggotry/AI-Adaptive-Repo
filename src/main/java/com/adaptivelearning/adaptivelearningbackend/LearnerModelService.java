package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ScopeService.ConceptDef;
import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;
import com.adaptivelearning.adaptivelearningbackend.ai.QuizBlueprint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Builds what the AI should know about a student on one handout: replays every stored response through the mastery
 * model, and turns the result into a prompt section, a lesson-cache fingerprint and a quiz blueprint.
 */
@Service
public class LearnerModelService {

    public record Model(StudyScope scope, List<ConceptDef> concepts, LinkedHashMap<String, ConceptState> states,
                        Map<String, List<LearnerPrompt.Example>> examples, int responseCount) {

        public List<ConceptState> orderedStates() { return new ArrayList<>(states.values()); }

        public Map<String, ConceptDef> byKey() {
            Map<String, ConceptDef> m = new HashMap<>();
            for (ConceptDef c : concepts) m.put(c.key(), c);
            return m;
        }
    }

    private final QuestionResponseRepository responses;
    private final ScopeService scopeService;

    public LearnerModelService(QuestionResponseRepository responses, ScopeService scopeService) {
        this.responses = responses;
        this.scopeService = scopeService;
    }

    public Model build(StudyScope scope) {
        return build(scope, Instant.now());
    }

    public Model build(StudyScope scope, Instant now) {
        List<ConceptDef> concepts = scopeService.concepts(scope);
        Set<String> keys = new LinkedHashSet<>();
        for (ConceptDef c : concepts) keys.add(c.key());

        List<QuestionResponse> rows = new ArrayList<>(responses.findByScopeIdOrderByAnsweredAtAsc(scope.getId()));
        if (scope.getCarriedFromScopeId() != null) {
            // Updated edition of the same handout: evidence about concepts it still covers carries over.
            for (QuestionResponse r : responses.findByScopeIdOrderByAnsweredAtAsc(scope.getCarriedFromScopeId())) {
                if (r.getConceptKey() != null && keys.contains(r.getConceptKey())) rows.add(r);
            }
        }

        List<MasteryModel.Event> events = new ArrayList<>();
        Map<String, List<LearnerPrompt.Example>> examples = new HashMap<>();
        // Newest first so the most recent mistakes are the examples shown.
        List<QuestionResponse> newestFirst = new ArrayList<>(rows);
        newestFirst.sort(Comparator.comparing(QuestionResponse::getAnsweredAt).reversed());

        for (QuestionResponse r : rows) {
            if (r.getConceptKey() == null || !keys.contains(r.getConceptKey())) continue;
            events.add(new MasteryModel.Event(r.getConceptKey(), r.getCredit(), r.getQuestionType(), r.getTier(),
                    r.getTimeSpentSeconds() == null ? 0 : r.getTimeSpentSeconds(),
                    r.getAnsweredAt().atZone(ScopeService.zone()).toInstant()));
        }
        for (QuestionResponse r : newestFirst) {
            if (r.getConceptKey() == null || !keys.contains(r.getConceptKey())) continue;
            if (r.getCredit() >= AiPolicy.CORRECT_CREDIT) continue;
            examples.computeIfAbsent(r.getConceptKey(), k -> new ArrayList<>()).add(new LearnerPrompt.Example(
                    r.getQuestionText(), r.getStudentAnswer(), r.getCorrectAnswer(), r.getMissedPoints(),
                    r.getFeedback(), r.getPayload(), r.getAnsweredAt().atZone(ScopeService.zone()).toInstant()));
        }

        LinkedHashMap<String, ConceptState> states = MasteryModel.evaluate(keys, events, now);
        return new Model(scope, concepts, states, examples, rows.size());
    }

    public LearnerPrompt.Rendered prompt(Model m, int maxEntries, Instant now) {
        List<LearnerPrompt.ConceptInfo> info = new ArrayList<>();
        for (ConceptDef c : m.concepts()) info.add(new LearnerPrompt.ConceptInfo(c.key(), c.term(), c.definition()));
        return LearnerPrompt.render(info, m.states(), m.examples(), maxEntries, now);
    }

    public List<QuizBlueprint.Slot> blueprint(Model m, QuizBlueprint.Mode mode, int n, Instant now) {
        return QuizBlueprint.build(mode, m.orderedStates(), n, AiPolicy.MAX_QUESTIONS_PER_CONCEPT, now);
    }

    /** Size of a Target Problems quiz: three questions per open weakness, within the policy range. */
    public int targetedSize(Model m) {
        int open = 0;
        for (ConceptState s : m.states().values()) {
            if (s.state == State.WEAK || (s.state == State.SHAKY && s.misses > 0) || s.state == State.SLIP) open++;
        }
        int n = open * 3;
        return Math.max(AiPolicy.TARGETED_MIN_QUESTIONS, Math.min(AiPolicy.TARGETED_DEFAULT_MAX_QUESTIONS, n));
    }

    /** True when the student has documented weaknesses or slips worth a Target Problems quiz. */
    public boolean hasDocumentedMistakes(Model m) {
        for (ConceptState s : m.states().values()) {
            if (s.misses > 0 && s.state != State.LEARNED) return true;
        }
        return false;
    }

    /** Slot descriptions handed to the generator (concept term/definition resolved from the scope). */
    public List<ClaudeService.SlotSpec> specs(Model m, List<QuizBlueprint.Slot> slots) {
        Map<String, ConceptDef> byKey = m.byKey();
        List<ClaudeService.SlotSpec> out = new ArrayList<>();
        for (QuizBlueprint.Slot sl : slots) {
            ConceptDef c = byKey.get(sl.conceptKey());
            out.add(new ClaudeService.SlotSpec(sl.index(), sl.conceptKey(), c == null ? sl.conceptKey() : c.term(),
                    c == null ? "" : c.definition(), sl.purpose().name()));
        }
        return out;
    }

    /** Distinct concepts of the slots, in slot order, for passage retrieval. */
    public List<com.adaptivelearning.adaptivelearningbackend.ai.PassageRetriever.Concept> wanted(Model m, List<QuizBlueprint.Slot> slots) {
        Map<String, ConceptDef> byKey = m.byKey();
        List<com.adaptivelearning.adaptivelearningbackend.ai.PassageRetriever.Concept> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QuizBlueprint.Slot sl : slots) {
            ConceptDef c = byKey.get(sl.conceptKey());
            if (c != null && seen.add(c.key())) {
                out.add(new com.adaptivelearning.adaptivelearningbackend.ai.PassageRetriever.Concept(c.key(), c.term(), c.definition()));
            }
        }
        return out;
    }

    public void saveAll(Collection<QuestionResponse> rows) {
        if (rows == null || rows.isEmpty()) return;
        responses.saveAll(rows);
    }
}
