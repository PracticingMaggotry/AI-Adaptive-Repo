package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;

import java.time.Instant;
import java.util.*;

/**
 * Decides, in code and not in the model, which concepts the next lesson part teaches.
 *
 * <p>A long handout can hold dozens of concepts and a student may be weak on most of them, so one lesson never
 * tries to teach everything. Each part teaches a small batch; the student moves on to the next part when done.
 * The order guarantees two things:
 * <ol>
 *   <li><b>Weak first.</b> Open weaknesses fill the part before anything else. One-off slips, spaced refreshers
 *       and not-yet-asked concepts only use the room that is left.</li>
 *   <li><b>Everything eventually.</b> Within each group, concepts taught fewer times come first. A concept taught
 *       once waits until every other concept in its group has been taught once, so no weak concept can be skipped
 *       forever, however many there are. Handout order breaks ties among not-yet-asked concepts.</li>
 * </ol>
 * Pure Java, no framework types.
 */
public final class LessonPlanner {
    private LessonPlanner() {}

    /** What one lesson part contains and where the student is in the whole plan. */
    public record Plan(List<String> keys, int partSize, int weakTotal, int weakLeftInRound, int partNumber, int partsInRound) {}

    public static int partSize(String tier) {
        if (tier == null) return AiPolicy.LESSON_PART_EASY;
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "hard" -> AiPolicy.LESSON_PART_HARD;
            case "medium" -> AiPolicy.LESSON_PART_MEDIUM;
            default -> AiPolicy.LESSON_PART_EASY;
        };
    }

    /**
     * @param states      every concept of the handout, in handout order
     * @param timesTaught how many lesson parts have already covered each concept (missing = 0)
     * @param tier        Easy / Medium / Hard; sets the part size
     */
    public static Plan next(Collection<ConceptState> states, Map<String, Integer> timesTaught, String tier, Instant now) {
        int size = partSize(tier);
        List<ConceptState> weak = new ArrayList<>(), slips = new ArrayList<>(), stale = new ArrayList<>(), unseen = new ArrayList<>();
        int order = 0;
        Map<String, Integer> handoutOrder = new HashMap<>();
        for (ConceptState s : states) {
            handoutOrder.put(s.key, order++);
            switch (s.state) {
                case WEAK -> weak.add(s);
                case SHAKY -> { if (s.misses > 0) weak.add(s); }
                case SLIP -> slips.add(s);
                case LEARNED -> { if (s.stale) stale.add(s); }
                case UNSEEN -> unseen.add(s);
            }
        }
        Comparator<ConceptState> byTaughtThenPriority = Comparator
                .comparingInt((ConceptState s) -> taught(timesTaught, s.key))
                .thenComparing(Comparator.comparingDouble((ConceptState s) -> s.priority(now)).reversed())
                .thenComparingInt(s -> handoutOrder.get(s.key));
        Comparator<ConceptState> byTaughtThenHandout = Comparator
                .comparingInt((ConceptState s) -> taught(timesTaught, s.key))
                .thenComparingInt(s -> handoutOrder.get(s.key));
        weak.sort(byTaughtThenPriority);
        slips.sort(byTaughtThenPriority);
        stale.sort(byTaughtThenHandout);
        unseen.sort(byTaughtThenHandout);

        List<String> keys = new ArrayList<>();
        for (ConceptState s : weak) { if (keys.size() >= size) break; keys.add(s.key); }
        addUpTo(keys, slips, Math.min(size, keys.size() + AiPolicy.LESSON_MAX_SLIPS), size);
        addUpTo(keys, stale, Math.min(size, keys.size() + AiPolicy.LESSON_MAX_REFRESHERS), size);
        addUpTo(keys, unseen, size, size);

        // Progress is counted over the weak group: how many have not yet had their turn in the current round.
        int weakTotal = weak.size();
        int minTaught = weak.isEmpty() ? 0 : taught(timesTaught, weak.get(0).key);
        int left = 0, doneInRound = 0;
        for (ConceptState s : weak) {
            if (taught(timesTaught, s.key) == minTaught) left++; else doneInRound++;
        }
        int parts = weakTotal == 0 ? 1 : (int) Math.ceil((double) (left + doneInRound) / size);
        int part = Math.min(parts, doneInRound / size + 1);
        return new Plan(keys, size, weakTotal, left, part, parts);
    }

    private static void addUpTo(List<String> keys, List<ConceptState> from, int limit, int size) {
        for (ConceptState s : from) {
            if (keys.size() >= limit || keys.size() >= size) break;
            keys.add(s.key);
        }
    }

    private static int taught(Map<String, Integer> m, String key) {
        Integer v = m.get(key);
        return v == null ? 0 : v;
    }
}
