package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.SubCategoryMatcher;
import com.adaptivelearning.adaptivelearningbackend.ai.SubCategoryMatcher.Merge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the sub-categories on uploaded material from multiplying into near-identical copies.
 *
 * <p>Sub-categories are free-text labels stored on {@link Material} and {@link MaterialContent} (there is no table of
 * them), so the only way to stop bloat is to control how a label is chosen and to repair labels already stored.
 *
 * <ul>
 *   <li><b>Prevention, at upload.</b> {@link #promptLabels} gives the classifier the labels already in use so it reuses
 *       one; {@link #resolve} then folds whatever it returns into an existing label when they are the same
 *       sub-category (deterministic, see {@link SubCategoryMatcher}), so a model that ignores the instruction still
 *       cannot add a duplicate.</li>
 *   <li><b>Repair, on demand.</b> {@link #preview} finds labels that can be merged into labels that already exist,
 *       first the identical ones (no AI), then, if asked, the ones that need judgement (one AI call per main
 *       category). It writes nothing. {@link #apply} writes exactly the merges an admin approved, after validating them
 *       again against the current data, in one transaction.</li>
 * </ul>
 *
 * Merges never leave a main category and never create a label: a merge target must be a label that already exists.
 */
@Service
public class SubCategoryService {

    /** Labels per main category shown to the classifier at upload. Bounds prompt size; most-used labels first. */
    static final int PROMPT_LABELS_PER_CATEGORY = 25;

    /** Labels per main category sent to the AI in one consolidation call (most used first). */
    static final int MAX_LABELS_PER_AI_CALL = 150;

    /** Student id AI maintenance work is accounted to. */
    private static final String AI_ACCOUNT = "_subcategory-maintenance";

    private final MaterialRepository materialRepository;
    private final MaterialContentRepository contentRepository;
    private final ClaudeService claudeService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock runLock = new ReentrantLock();

    public SubCategoryService(MaterialRepository materialRepository,
                              MaterialContentRepository contentRepository,
                              ClaudeService claudeService) {
        this.materialRepository = materialRepository;
        this.contentRepository = contentRepository;
        this.claudeService = claudeService;
    }

    // ── Inventory ─────────────────────────────────────────────────────────

    /** Main category -> (sub-label -> handouts using it), labels most used first. */
    public Map<String, LinkedHashMap<String, Integer>> inventory() {
        Map<String, Map<String, Integer>> raw = new TreeMap<>();
        for (Object[] row : materialRepository.countSubCategories()) {
            String category = (String) row[0];
            String label = (String) row[1];
            int n = ((Number) row[2]).intValue();
            raw.computeIfAbsent(category, k -> new HashMap<>()).merge(label, n, Integer::sum);
        }
        // A label that only survives on shared content is still a label a re-upload would reuse.
        for (Object[] row : contentRepository.distinctSubCategories()) {
            raw.computeIfAbsent((String) row[0], k -> new HashMap<>()).putIfAbsent((String) row[1], 0);
        }
        Map<String, LinkedHashMap<String, Integer>> out = new LinkedHashMap<>();
        raw.forEach((category, labels) -> out.put(category, byUseDescending(labels)));
        return out;
    }

    private static LinkedHashMap<String, Integer> byUseDescending(Map<String, Integer> labels) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(labels.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        entries.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** The inventory entry for a main category, matching the name case-insensitively (the AI may have changed its case). */
    private static Map<String, Integer> labelsFor(Map<String, LinkedHashMap<String, Integer>> inventory, String category) {
        if (category == null) return Map.of();
        for (Map.Entry<String, LinkedHashMap<String, Integer>> e : inventory.entrySet()) {
            if (e.getKey().equalsIgnoreCase(category.trim())) return e.getValue();
        }
        return Map.of();
    }

    // ── Prevention (upload time) ──────────────────────────────────────────

    /** Existing labels per main category for the classifier prompt: sanitised, most used first, capped. */
    public Map<String, List<String>> promptLabels(Map<String, LinkedHashMap<String, Integer>> inventory) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        inventory.forEach((category, labels) -> {
            List<String> top = new ArrayList<>();
            for (String label : labels.keySet()) {
                String clean = SubCategoryMatcher.clean(label);   // stored labels came from AI over student text
                if (clean != null) top.add(clean);
                if (top.size() >= PROMPT_LABELS_PER_CATEGORY) break;
            }
            if (!top.isEmpty()) out.put(category, top);
        });
        return out;
    }

    /**
     * The label to store for what the classifier proposed: an existing label when it is the same sub-category, null
     * when it only repeats the main category or is blank, otherwise the cleaned new label.
     */
    public String resolve(Map<String, LinkedHashMap<String, Integer>> inventory, String primaryCategory, String proposed) {
        List<String> existing = new ArrayList<>(labelsFor(inventory, primaryCategory).keySet());
        SubCategoryMatcher.Resolution r = SubCategoryMatcher.resolve(proposed, primaryCategory, existing);
        if (r.reused() || (r.label() == null && r.original() != null)) {
            System.out.println("Sub-category \"" + r.original() + "\" under \"" + primaryCategory + "\" -> "
                    + (r.label() == null ? "(none, repeats the category)" : "existing \"" + r.label() + "\""));
        }
        return r.label();
    }

    // ── Repair (admin) ────────────────────────────────────────────────────

    /** One merge to review: re-tag {@code handouts} handouts from {@code from} to the existing label {@code to}. */
    public record Proposal(String category, String from, String to, int handouts, String source) {}

    public record CategoryReport(String category, int labels, List<Proposal> proposals,
                                 List<String> rejected, String error, boolean truncated) {}

    public record PreviewReport(List<CategoryReport> categories, int totalProposals, int totalHandouts, boolean usedAi) {}

    public record ApplyResult(int merges, int materialsChanged, int contentChanged, List<String> rejected) {}

    /** Thrown when a preview or apply is already running; the controller maps it to HTTP 409. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("A sub-category cleanup is already running. Try again in a moment."); }
    }

    /**
     * Finds merges, writes nothing.
     *
     * @param onlyCategory limit to one main category (case-insensitive), or null for all
     * @param useAi        also ask the AI for merges that need judgement; false = identical labels only, no AI cost
     */
    public PreviewReport preview(String onlyCategory, boolean useAi) {
        if (!runLock.tryLock()) throw new BusyException();
        try (AiCallContext.Handle ignored = AiCallContext.open(AI_ACCOUNT)) {
            List<CategoryReport> reports = new ArrayList<>();
            int proposals = 0, handouts = 0;
            for (Map.Entry<String, LinkedHashMap<String, Integer>> e : inventory().entrySet()) {
                if (onlyCategory != null && !onlyCategory.isBlank() && !e.getKey().equalsIgnoreCase(onlyCategory.trim())) continue;
                CategoryReport report = previewCategory(e.getKey(), e.getValue(), useAi);
                if (report.proposals().isEmpty() && report.error() == null) continue;
                reports.add(report);
                proposals += report.proposals().size();
                for (Proposal p : report.proposals()) handouts += p.handouts();
            }
            return new PreviewReport(reports, proposals, handouts, useAi);
        } finally {
            runLock.unlock();
        }
    }

    private CategoryReport previewCategory(String category, LinkedHashMap<String, Integer> counts, boolean useAi) {
        // Stage 1: identical labels, no AI.
        Map<String, String> identical = SubCategoryMatcher.clusterIdentical(counts);
        List<Merge> merges = new ArrayList<>();
        identical.forEach((from, to) -> merges.add(new Merge(from, to)));

        String error = null;
        boolean truncated = false;

        // Stage 2: same-subject labels that only judgement can see. Labels already folded in stage 1 are not sent;
        // their handouts are counted under the label they fold into, so the AI sees true usage.
        if (useAi) {
            LinkedHashMap<String, Integer> remaining = new LinkedHashMap<>();
            counts.forEach((label, n) -> { if (!identical.containsKey(label)) remaining.put(label, n); });
            identical.forEach((alias, target) -> remaining.merge(target, counts.getOrDefault(alias, 0), Integer::sum));
            LinkedHashMap<String, Integer> sent = byUseDescending(remaining);

            if (sent.size() >= 2) {
                if (sent.size() > MAX_LABELS_PER_AI_CALL) {
                    truncated = true;
                    LinkedHashMap<String, Integer> top = new LinkedHashMap<>();
                    for (Map.Entry<String, Integer> e : sent.entrySet()) {
                        if (top.size() >= MAX_LABELS_PER_AI_CALL) break;
                        top.put(e.getKey(), e.getValue());
                    }
                    sent = top;
                }
                try {
                    String raw = claudeService.proposeSubCategoryMerges(category, sent);
                    if (raw == null || raw.startsWith("AI service")) error = raw == null ? "No AI reply." : raw;
                    else merges.addAll(parseGroups(raw));
                } catch (Exception ex) {
                    error = "The AI reply could not be read: " + ex.getMessage();
                }
            }
        }

        // Everything goes through the same validator an apply will use, so a preview never shows a merge apply would refuse.
        SubCategoryMatcher.Validated v = SubCategoryMatcher.validateMerges(counts.keySet(), merges);
        List<Proposal> proposals = new ArrayList<>();
        v.accepted().forEach((from, to) -> {
            boolean viaIdentical = to.equals(identical.get(from));
            proposals.add(new Proposal(category, from, to, counts.getOrDefault(from, 0), viaIdentical ? "identical" : "ai"));
        });
        proposals.sort(Comparator.comparing(Proposal::to).thenComparing(Proposal::from));
        return new CategoryReport(category, counts.size(), proposals, v.rejected(), error, truncated);
    }

    /** Turns {"groups":[{"keep":K,"merge":[A,B]}]} into merges A->K, B->K. */
    private List<Merge> parseGroups(String raw) throws Exception {
        JsonNode root = mapper.readTree(ClaudeService.stripJsonFence(raw));
        List<Merge> out = new ArrayList<>();
        for (JsonNode group : root.path("groups")) {
            String keep = group.path("keep").asText("");
            for (JsonNode m : group.path("merge")) out.add(new Merge(m.asText(""), keep));
        }
        return out;
    }

    /** A merge as posted back from the review screen. */
    public record Approved(String category, String from, String to) {}

    /**
     * Writes the approved merges. Each is validated against the labels that exist right now (so a stale preview, an
     * invented label or a cross-category merge is refused, not written) and all writes share one transaction: if any
     * statement fails, none is kept.
     */
    @Transactional
    public ApplyResult apply(List<Approved> approved) {
        if (!runLock.tryLock()) throw new BusyException();
        try {
            Map<String, LinkedHashMap<String, Integer>> inventory = inventory();
            Map<String, List<Merge>> byCategory = new LinkedHashMap<>();
            List<String> rejected = new ArrayList<>();

            for (Approved a : approved == null ? List.<Approved>of() : approved) {
                String category = a == null ? null : canonicalCategory(inventory, a.category());
                if (category == null) { rejected.add((a == null ? "null" : a.category() + ": " + a.from() + " -> " + a.to()) + ": unknown category"); continue; }
                byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(new Merge(a.from(), a.to()));
            }

            int merges = 0, materials = 0, contents = 0;
            for (Map.Entry<String, List<Merge>> e : byCategory.entrySet()) {
                String category = e.getKey();
                SubCategoryMatcher.Validated v = SubCategoryMatcher.validateMerges(inventory.get(category).keySet(), e.getValue());
                v.rejected().forEach(r -> rejected.add(category + ": " + r));
                for (Map.Entry<String, String> m : v.accepted().entrySet()) {
                    materials += materialRepository.renameSubCategory(category, m.getKey(), m.getValue());
                    contents += contentRepository.renameSubCategory(category, m.getKey(), m.getValue());
                    merges++;
                }
            }
            return new ApplyResult(merges, materials, contents, rejected);
        } finally {
            runLock.unlock();
        }
    }

    private static String canonicalCategory(Map<String, LinkedHashMap<String, Integer>> inventory, String requested) {
        if (requested == null) return null;
        for (String c : inventory.keySet()) if (c.equalsIgnoreCase(requested.trim())) return c;
        return null;
    }

    // ── Health summary (no AI) ────────────────────────────────────────────

    public record Health(String category, int labels, int identicalDuplicates) {}

    /** Per main category: how many labels exist and how many of them are plain duplicates of another. */
    public List<Health> health() {
        List<Health> out = new ArrayList<>();
        inventory().forEach((category, counts) ->
                out.add(new Health(category, counts.size(), SubCategoryMatcher.clusterIdentical(counts).size())));
        return out;
    }
}
