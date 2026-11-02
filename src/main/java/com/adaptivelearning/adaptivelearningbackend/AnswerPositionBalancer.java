package com.adaptivelearning.adaptivelearningbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Models love putting the correct MCQ option in the same slot (usually B). This reorders each generated MCQ's
 * options so the correct answer's position is spread evenly across the batch instead of following the model's habit.
 *
 * <p>Positions come from a shuffled "bag" per option count, so a batch of 8 four-option questions ends up with each
 * of A-D exactly twice, in random order, rather than whatever a fair coin happens to give. correctAnswer is always
 * rewritten to the option's TEXT, never a letter, so the reorder cannot break grading.
 *
 * <p>Questions that only make sense in their original order are left untouched: options that point at other options
 * by letter ("A and B", "option C"), or hint/explanation/question text that cites a letter. "All/None of the above"
 * style options keep their slot while the others are shuffled around them.
 */
public final class AnswerPositionBalancer {

    private AnswerPositionBalancer() {}

    /** "A and B", "Both A and C", "option D", "answer (B)" — meaningless once options move. */
    private static final Pattern LETTER_REFERENCE = Pattern.compile(
            "\\b(option|choice|answer)s?\\s+\\(?[a-d]\\b"
                    + "|\\b(both|either|neither)\\s+(of\\s+)?\\(?[a-d]\\)?\\s*(and|or|&|,)"
                    + "|^\\(?[a-d]\\)?\\s*(and|&|,|or)\\s*\\(?[a-d]\\)?$"
                    + "|\\([A-D]\\)",
            Pattern.CASE_INSENSITIVE);

    /** "All of the above", "None of these" — must stay where they are, normally last. */
    private static final Pattern PINNED_OPTION = Pattern.compile(
            "\\bof (the above|these|those|the (previous|preceding) (options|answers|choices))\\b|\\bthe above\\b",
            Pattern.CASE_INSENSITIVE);

    /** Result of a balancing pass, for logging. */
    public record Summary(int shuffled, int skipped, int[] positionCounts) {}

    /**
     * Rebalances every eligible MCQ in the array in place. Safe to call on any question array: non-MCQ types and
     * malformed MCQs are ignored.
     */
    public static Summary balanceMcq(ArrayNode questions, Random rnd) {
        Map<Integer, Deque<Integer>> bags = new HashMap<>();
        int shuffled = 0, skipped = 0;
        int[] counts = new int[4];

        for (JsonNode q : questions) {
            if (!"MCQ".equalsIgnoreCase(q.path("type").asText("MCQ"))) continue;
            if (!(q.path("payload") instanceof ObjectNode payload)) continue;
            if (!(payload.path("options") instanceof ArrayNode options)) continue;
            int n = options.size();
            if (n < 2 || n > 4) { skipped++; continue; } // the parser only stores A-D

            List<String> texts = new ArrayList<>();
            for (JsonNode o : options) texts.add(QuestionParser.stripLeadingChoiceLabel(o.asText("")).trim());

            int correct = correctIndex(texts, payload.path("correctAnswer").asText(""));
            if (correct < 0 || dependsOnOrder(q, texts)) { skipped++; continue; }

            List<Integer> pinned = new ArrayList<>();
            for (int i = 0; i < n; i++) if (PINNED_OPTION.matcher(texts.get(i)).find()) pinned.add(i);

            String[] result = new String[n];
            for (int p : pinned) result[p] = texts.get(p);

            List<String> free = new ArrayList<>();
            for (int i = 0; i < n; i++) if (!pinned.contains(i)) free.add(texts.get(i));
            int newCorrect;

            if (pinned.isEmpty()) {
                // Even spread: take the next target slot from this option-count's shuffled bag.
                Deque<Integer> bag = bags.computeIfAbsent(n, k -> new ArrayDeque<>());
                if (bag.isEmpty()) {
                    List<Integer> slots = new ArrayList<>();
                    for (int i = 0; i < n; i++) slots.add(i);
                    Collections.shuffle(slots, rnd);
                    bag.addAll(slots);
                }
                newCorrect = bag.poll();
                List<String> others = new ArrayList<>(texts);
                String correctText = others.remove(correct);
                Collections.shuffle(others, rnd);
                others.add(newCorrect, correctText);
                for (int i = 0; i < n; i++) result[i] = others.get(i);
            } else {
                // Some slots are fixed: shuffle only the free options into the free slots.
                String correctText = texts.get(correct);
                Collections.shuffle(free, rnd);
                int next = 0;
                for (int i = 0; i < n; i++) if (result[i] == null) result[i] = free.get(next++);
                newCorrect = pinned.contains(correct) ? correct : indexOf(result, correctText);
            }

            options.removeAll();
            for (String r : result) options.add(TextNode.valueOf(r));
            payload.put("correctAnswer", result[newCorrect]);
            if (newCorrect < counts.length) counts[newCorrect]++;
            shuffled++;
        }
        return new Summary(shuffled, skipped, counts);
    }

    /**
     * Index of the option correctAnswer points to, or -1 if it can't be pinned to exactly one option. Text match
     * first (exact, then case-insensitive if unambiguous); a bare letter A-D counts only when no option matches by
     * text, same rule as PRACTICAL.
     */
    static int correctIndex(List<String> texts, String correctAnswer) {
        String correct = QuestionParser.stripLeadingChoiceLabel(correctAnswer == null ? "" : correctAnswer).trim();
        if (correct.isEmpty()) return -1;

        int found = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).equals(correct)) {
                if (found != -1) return -1;
                found = i;
            }
        }
        if (found >= 0) return found;

        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).equalsIgnoreCase(correct)) {
                if (found != -1) return -1;
                found = i;
            }
        }
        if (found >= 0) return found;

        if (correct.length() == 1) {
            int idx = Character.toUpperCase(correct.charAt(0)) - 'A';
            if (idx >= 0 && idx < texts.size()) return idx;
        }
        return -1;
    }

    private static boolean dependsOnOrder(JsonNode q, List<String> optionTexts) {
        for (String t : optionTexts) if (LETTER_REFERENCE.matcher(t).find()) return true;
        for (String field : new String[]{"questionText", "hint", "explanation"}) {
            if (LETTER_REFERENCE.matcher(q.path(field).asText("")).find()) return true;
        }
        return false;
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (value.equals(arr[i])) return i;
        return -1;
    }

    /** Human-readable "A:2 B:2 C:2 D:2" for log lines. */
    public static String describe(Summary s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.positionCounts().length; i++) {
            if (i > 0) sb.append(' ');
            sb.append((char) ('A' + i)).append(':').append(s.positionCounts()[i]);
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }
}
