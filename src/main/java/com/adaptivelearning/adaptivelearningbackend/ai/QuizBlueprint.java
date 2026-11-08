package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.State;

import java.time.Instant;
import java.util.*;

/**
 * Decides, on the server, which concept each generated question must test and for what reason. The
 * model is then
 * asked for exactly one question per slot. This gives three guarantees:
 * <ul>
 *   <li>coverage: unseen concepts get slots, so the quiz moves forward instead of re-drilling one area;</li>
 *   <li>one-off slips and recovered mistakes are not drilled (at most a single verification slot);</li>
 *   <li>every question carries its concept and purpose from birth, so results can be attributed per concept.</li>
 * </ul>
 */
public final class QuizBlueprint {
    private QuizBlueprint() {}

    public enum Purpose { INITIAL_COVERAGE, REMEDIATE, COVER_NEW, VERIFY, DIAGNOSTIC }

    public enum Mode { INITIAL, ADAPTED, TARGETED }

    /** The job of a REMEDIATE question within its concept (see {@link #planRemediation}). */
    public enum Role { RECALL, DISCRIMINATE, APPLY }

    /**
     * One planned question. {@code role} and {@code type} are only set for remediation slots of a Target Problems
     * quiz; null means "the generator chooses".
     * {@code demand} is the 1-5 demand level of the question, 0 when it is not planned per slot.
     */
    public record Slot(int index, String conceptKey, Purpose purpose, Role role, String type, int demand) {
        public Slot(int index, String conceptKey, Purpose purpose) { this(index, conceptKey, purpose, null, null, 0); }
        public Slot(int index, String conceptKey, Purpose purpose, Role role, String type) {
            this(index, conceptKey, purpose, role, type, 0);
        }
        /** Same slot at demand level 1-5 (see {@link AbilityModel}); 0 means the quiz-level tier applies. */
        public Slot withDemand(int level) { return new Slot(index, conceptKey, purpose, role, type, level); }
    }

    private record Step(String type, Role role) {}

    /**
     * Remediation questions of one concept, in order. True/false is deliberately absent: with a 50% guess rate a right
     * answer barely moves the mastery estimate (a correct MCQ lifts it to about 0.60, a correct T/F to about 0.43 from
     * the same start), so T/F answers slow down recovery detection and say little about whether the weakness is fixed.
     * Matching, sorting and essay are absent because they span several concepts or cannot be attributed to one.
     */
    private static final List<Step> REMEDIATION_SEQUENCE = List.of(
            new Step("MCQ", Role.RECALL),
            new Step("CONCEPTID", Role.DISCRIMINATE),
            new Step("MCQ", Role.APPLY),
            new Step("FILLBLANK", Role.RECALL),
            new Step("MCQ", Role.DISCRIMINATE));

    /**
     * Gives every REMEDIATE slot a role and a question type, decided on the server instead of left to the model, which
     * otherwise drifts to restating the handout sentence as true/false. The slots of one concept progress from
     * re-testing the idea, to telling it apart from what it is confused with, to applying it. Concepts in
     * {@code freeTypeConcepts} (their past mistakes were PRACTICAL questions) keep a role but no fixed type, so a
     * procedural skill is not forced into a recall format; the generator is still told never to use true/false there.
     * Other purposes are returned unchanged.
     */
    public static List<Slot> planRemediation(List<Slot> slots, Set<String> freeTypeConcepts) {
        Map<String, Integer> seen = new HashMap<>();
        List<Slot> out = new ArrayList<>(slots.size());
        for (Slot s : slots) {
            if (s.purpose() != Purpose.REMEDIATE) { out.add(s); continue; }
            int k = seen.merge(s.conceptKey(), 1, Integer::sum) - 1;
            Step step = REMEDIATION_SEQUENCE.get(k % REMEDIATION_SEQUENCE.size());
            boolean free = freeTypeConcepts != null && freeTypeConcepts.contains(s.conceptKey());
            out.add(new Slot(s.index(), s.conceptKey(), s.purpose(), step.role(), free ? null : step.type(), s.demand()));
        }
        return out;
    }

    public static List<Slot> build(Mode mode, List<ConceptState> concepts, int n, int maxPerConcept, Instant now) {
        if (concepts.isEmpty() || n <= 0) return List.of();
        List<String> encoded = switch (mode) {
            case INITIAL -> initial(concepts, n, maxPerConcept);
            case ADAPTED -> adapted(concepts, n, maxPerConcept, now);
            case TARGETED -> targeted(concepts, n, now);
        };
        return numbered(encoded);
    }

    // The three builders return "purpose|key" pairs encoded as a list of strings; numbered() decodes them.
    private static final String SEP = "|";

    private static List<String> initial(List<ConceptState> concepts, int n, int cap) {
        List<ConceptState> chosen = new ArrayList<>();
        if (concepts.size() > n) {
            // Cover the WHOLE handout: pick concepts evenly spaced through it.
            for (int i = 0; i < n; i++) chosen.add(concepts.get((int) Math.floor((double) i * concepts.size() / n)));
        } else {
            chosen.addAll(concepts);
        }
        List<String> out = new ArrayList<>();
        cycle(out, Purpose.INITIAL_COVERAGE, chosen, n, cap);
        return out;
    }

    private static List<String> adapted(List<ConceptState> concepts, int n, int cap, Instant now) {
        List<ConceptState> remediate = new ArrayList<>();
        List<ConceptState> fresh = new ArrayList<>();
        List<ConceptState> verify = new ArrayList<>();
        for (ConceptState c : concepts) {
            switch (c.state) {
                case WEAK -> remediate.add(c);
                case SHAKY -> { if (c.misses > 0) remediate.add(c); else verify.add(c); }
                case UNSEEN -> fresh.add(c);
                case SLIP, LEARNED -> verify.add(c);
            }
        }
        remediate.sort(Comparator.comparingDouble((ConceptState c) -> c.priority(now)).reversed());
        // Verification order: slips first, then stale/shaky clean concepts, then learned ones not asked for longest.
        verify.sort(Comparator.<ConceptState>comparingInt(c -> c.state == State.SLIP ? 0 : c.state == State.SHAKY ? 1 : 2)
                .thenComparing(c -> c.lastAt == null ? Instant.EPOCH : c.lastAt));

        int[] want = shares(n, new double[]{AiPolicy.SHARE_REMEDIATE, AiPolicy.SHARE_NEW, AiPolicy.SHARE_VERIFY});
        int[] capacity = {remediate.size() * cap, fresh.size() * cap, verify.size() * cap};
        int[] give = new int[3];
        for (int i = 0; i < 3; i++) give[i] = Math.min(want[i], capacity[i]);
        int left = n - give[0] - give[1] - give[2];
        for (int pass = 0; pass < 3 && left > 0; pass++) {
            for (int i = 0; i < 3 && left > 0; i++) {
                int spare = capacity[i] - give[i];
                int add = Math.min(spare, left);
                give[i] += add;
                left -= add;
            }
        }

        List<String> a = new ArrayList<>(), b = new ArrayList<>(), c = new ArrayList<>();
        cycle(a, Purpose.REMEDIATE, remediate, give[0], cap);
        cycle(b, Purpose.COVER_NEW, fresh, give[1], cap);
        cycle(c, Purpose.VERIFY, verify, give[2], cap);
        List<String> out = interleave(a, b, c);

        if (left > 0) {
            // Fewer concepts than slots: relax the per-concept cap rather than return a short quiz.
            List<ConceptState> all = new ArrayList<>(concepts);
            all.sort(Comparator.comparingDouble((ConceptState x) -> x.priority(now)).reversed());
            cycle(out, Purpose.VERIFY, all, left, Integer.MAX_VALUE);
        }
        return out;
    }

    private static List<String> targeted(List<ConceptState> concepts, int n, Instant now) {
        List<ConceptState> pool = new ArrayList<>();
        for (ConceptState c : concepts) {
            if (c.state == State.WEAK || (c.state == State.SHAKY && c.misses > 0) || c.state == State.SLIP) pool.add(c);
        }
        Purpose purpose = Purpose.REMEDIATE;
        if (pool.isEmpty()) {
            // Nothing documented to repair: diagnose the least-known concepts the student has actually met.
            for (ConceptState c : concepts) if (c.state != State.UNSEEN && c.state != State.LEARNED) pool.add(c);
            purpose = Purpose.DIAGNOSTIC;
        }
        if (pool.isEmpty()) {
            for (ConceptState c : concepts) if (c.state == State.UNSEEN) pool.add(c);
            purpose = Purpose.DIAGNOSTIC;
        }
        if (pool.isEmpty()) pool.addAll(concepts);

        // A slip is repeated at most once; real weaknesses are weighted by urgency. A narrow weakness may take
        // the whole quiz, which is the intended Target Problems behaviour.
        pool.sort(Comparator.comparingDouble((ConceptState c) -> c.priority(now)).reversed());
        double[] w = new double[pool.size()];
        for (int i = 0; i < w.length; i++) {
            ConceptState c = pool.get(i);
            w[i] = Math.max(0.05, c.priority(now)) * (c.state == State.SLIP ? 0.25 : 1.0);
        }
        int[] counts = shares(n, w);
        List<String> out = new ArrayList<>();
        for (int round = 0; out.size() < n; round++) {
            boolean any = false;
            for (int i = 0; i < pool.size() && out.size() < n; i++) {
                if (counts[i] > round) { out.add(purpose.name() + SEP + pool.get(i).key); any = true; }
            }
            if (!any) break;
        }
        return out;
    }

    private static List<Slot> numbered(List<String> encoded) {
        List<Slot> slots = new ArrayList<>(encoded.size());
        for (int i = 0; i < encoded.size(); i++) {
            String[] parts = encoded.get(i).split("\\|", 2);
            slots.add(new Slot(i + 1, parts[1], Purpose.valueOf(parts[0])));
        }
        return slots;
    }

    /** Largest-remainder split of {@code n} across weights (all weights > 0 get a fair share; sum is exactly n). */
    static int[] shares(int n, double[] weights) {
        double total = 0;
        for (double w : weights) total += w;
        int[] out = new int[weights.length];
        if (total <= 0) return out;
        double[] rem = new double[weights.length];
        int used = 0;
        for (int i = 0; i < weights.length; i++) {
            double exact = n * weights[i] / total;
            out[i] = (int) Math.floor(exact);
            rem[i] = exact - out[i];
            used += out[i];
        }
        while (used < n) {
            int best = 0;
            for (int i = 1; i < rem.length; i++) if (rem[i] > rem[best]) best = i;
            out[best]++;
            rem[best] = -1;
            used++;
        }
        return out;
    }

    /** Appends up to {@code count} "purpose|key" entries, one per concept per round, never exceeding cap per concept. */
    private static void cycle(List<String> out, Purpose purpose, List<ConceptState> pool, int count, int cap) {
        if (pool.isEmpty() || count <= 0) return;
        int added = 0;
        for (int round = 0; round < cap && added < count; round++) {
            for (ConceptState c : pool) {
                if (added >= count) break;
                out.add(purpose.name() + SEP + c.key);
                added++;
            }
        }
    }

    @SafeVarargs
    private static List<String> interleave(List<String>... lists) {
        List<String> out = new ArrayList<>();
        int max = 0;
        for (List<String> l : lists) max = Math.max(max, l.size());
        for (int i = 0; i < max; i++) for (List<String> l : lists) if (i < l.size()) out.add(l.get(i));
        return out;
    }
}
