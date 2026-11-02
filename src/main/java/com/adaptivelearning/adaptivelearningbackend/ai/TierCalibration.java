package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.*;

/**
 * Answers "are Medium/Hard questions actually harder than Easy ones?" from the permanent response log.
 *
 * <p>Two views are needed because the platform is adaptive: strong students are moved to Hard, so the raw share of
 * correct answers per tier mixes question difficulty with student ability. The <b>paired</b> view removes that by
 * comparing the same student on the same handout across tiers.
 */
public final class TierCalibration {
    private TierCalibration() {}

    /** One graded answer, as stored in the permanent response log. */
    public record Outcome(String studentKey, long scopeId, String tier, String type, double credit, int timeSeconds) {}

    public record TierStats(String tier, int n, double facility, double ciLow, double ciHigh,
                            double medianSeconds, String targetVerdict) {}

    public record Report(List<TierStats> tiers, Double pairedEasyToMedium, Double pairedMediumToHard,
                         int pairedStudentsEM, int pairedStudentsMH, boolean rawOrdered, boolean enoughData) {}

    private static final String[] TIERS = {"Easy", "Medium", "Hard"};

    public static Report analyse(Collection<Outcome> outcomes) {
        Map<String, List<Outcome>> byTier = new LinkedHashMap<>();
        for (String t : TIERS) byTier.put(t, new ArrayList<>());
        for (Outcome o : outcomes) {
            String t = canon(o.tier());
            if (t != null) byTier.get(t).add(o);
        }

        List<TierStats> stats = new ArrayList<>();
        int total = 0;
        for (String t : TIERS) {
            List<Outcome> l = byTier.get(t);
            total += l.size();
            stats.add(tierStats(t, l));
        }

        Map<String, double[]> perStudentTier = new HashMap<>(); // key student|scope|tier -> {sum, n}
        for (Outcome o : outcomes) {
            String t = canon(o.tier());
            if (t == null) continue;
            double[] a = perStudentTier.computeIfAbsent(o.studentKey() + "|" + o.scopeId() + "|" + t, k -> new double[2]);
            a[0] += o.credit();
            a[1] += 1;
        }
        double[] em = paired(perStudentTier, "Easy", "Medium");
        double[] mh = paired(perStudentTier, "Medium", "Hard");

        boolean ordered = stats.get(0).n() > 0 && stats.get(1).n() > 0 && stats.get(2).n() > 0
                && stats.get(0).facility() > stats.get(1).facility() && stats.get(1).facility() > stats.get(2).facility();
        return new Report(stats,
                em[1] > 0 ? em[0] : null, mh[1] > 0 ? mh[0] : null, (int) em[1], (int) mh[1],
                ordered, total >= AiPolicy.CALIBRATION_MIN_RESPONSES);
    }

    /** Mean (harder tier facility - easier tier facility) over students who answered both; negative = harder is harder. */
    private static double[] paired(Map<String, double[]> perStudentTier, String easier, String harder) {
        Map<String, Double> easy = new HashMap<>(), hard = new HashMap<>();
        for (Map.Entry<String, double[]> e : perStudentTier.entrySet()) {
            String k = e.getKey();
            int cut = k.lastIndexOf('|');
            String who = k.substring(0, cut), tier = k.substring(cut + 1);
            double mean = e.getValue()[0] / e.getValue()[1];
            if (tier.equals(easier)) easy.put(who, mean);
            else if (tier.equals(harder)) hard.put(who, mean);
        }
        double sum = 0; int n = 0;
        for (Map.Entry<String, Double> e : hard.entrySet()) {
            Double lo = easy.get(e.getKey());
            if (lo != null) { sum += e.getValue() - lo; n++; }
        }
        return new double[]{n == 0 ? 0 : sum / n, n};
    }

    private static TierStats tierStats(String tier, List<Outcome> l) {
        int n = l.size();
        if (n == 0) return new TierStats(tier, 0, 0, 0, 0, 0, "NO_DATA");
        double sum = 0;
        List<Integer> times = new ArrayList<>();
        for (Outcome o : l) { sum += o.credit(); if (o.timeSeconds() > 0) times.add(o.timeSeconds()); }
        double p = sum / n;
        // Wilson interval on the mean credit (treats credit as a proportion; adequate for monitoring).
        double z = 1.96, z2 = z * z;
        double centre = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n);
        Collections.sort(times);
        double median = times.isEmpty() ? 0 : times.size() % 2 == 1 ? times.get(times.size() / 2)
                : (times.get(times.size() / 2 - 1) + times.get(times.size() / 2)) / 2.0;
        double[] range = tier.equals("Easy") ? AiPolicy.EASY_RANGE : tier.equals("Medium") ? AiPolicy.MEDIUM_RANGE : AiPolicy.HARD_RANGE;
        String verdict;
        if (n < AiPolicy.CALIBRATION_MIN_RESPONSES) verdict = "TOO_FEW_RESPONSES";
        else if (centre + half < range[0]) verdict = "HARDER_THAN_TARGET";
        else if (centre - half > range[1]) verdict = "EASIER_THAN_TARGET";
        else verdict = "WITHIN_TARGET";
        return new TierStats(tier, n, p, Math.max(0, centre - half), Math.min(1, centre + half), median, verdict);
    }

    private static String canon(String t) {
        if (t == null) return null;
        for (String x : TIERS) if (x.equalsIgnoreCase(t.trim())) return x;
        return null;
    }
}
