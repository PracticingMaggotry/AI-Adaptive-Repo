package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


@RestController
public class DashboardController {

    @Autowired
    private AttemptRepository attemptRepository;
    @Autowired
    private ClaudeService claudeService;
    @Autowired
    private QuestionPerformanceRepository questionPerformanceRepository;

    @GetMapping("/api/dashboard")
    public Map<String, Object> dashboard(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        String name = (String) session.getAttribute("loggedInUserName");

        if (email == null || email.isBlank()) {
            email = "demo";
        }
        if (name == null || name.isBlank()) {
            name = "Student";
        }

        List<Attempt> attempts = attemptRepository.findByStudentIdOrderByTimestampDesc(email);
        List<Attempt> chronological = new ArrayList<>(attempts);
        Collections.reverse(chronological);

        double avgScore = attempts.stream().mapToDouble(Attempt::getPerformanceScore).average().orElse(0);
        double latestScore = attempts.isEmpty() ? 0 : attempts.get(0).getPerformanceScore();
        String currentDiff = attempts.isEmpty() ? "Medium" : cleanDifficulty(attempts.get(0).getNextDiff(), attempts.get(0).getDifficulty());
        int quizzesTaken = attempts.size();
        int mastery = (int) Math.round(avgScore);
        int spi = calculateSpi(attempts, avgScore);

        Map<String, Object> student = new LinkedHashMap<>();
        student.put("name", name);
        student.put("email", email);
        student.put("avgScore", Math.round(avgScore));
        student.put("latestScore", Math.round(latestScore));
        student.put("mastery", mastery);
        student.put("quizzesTaken", quizzesTaken);
        Set<LocalDate> activeDates = activeStudyDates(attempts);
        int streak = calculateStreak(activeDates);
        student.put("streak", streak);
        student.put("spi", spi);
        student.put("currentDiff", currentDiff);

        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(kpi("Latest Score", attempts.isEmpty() ? "--" : Math.round(latestScore) + "%", "Most recent quiz result", "🧪", "Now", "blue"));
        kpi.add(kpi("Average Mastery", Math.round(avgScore) + "%", "Overall performance", "🧠", "Mastery", "green"));
        kpi.add(kpi("Quizzes Taken", String.valueOf(quizzesTaken), "Saved attempts", "📝", "Total", "purple"));
        kpi.add(kpi("Next Difficulty", currentDiff, "Auto-adjusted by analytics", "🎮", "Adaptive", "orange"));

        Map<String, Object> learningCurve = new LinkedHashMap<>();
        if (chronological.isEmpty()) {
            learningCurve.put("labels", Arrays.asList("Attempt 1"));
            learningCurve.put("scores", Arrays.asList(0));
            learningCurve.put("baseline", Arrays.asList(60));
        } else {
            List<String> labels = new ArrayList<>();
            List<Integer> scores = new ArrayList<>();
            List<Integer> baseline = new ArrayList<>();
            List<Attempt> recentAll = chronological.size() > 20
                    ? chronological.subList(chronological.size() - 20, chronological.size())
                    : chronological;
            for (int i = 0; i < recentAll.size(); i++) {
                labels.add("Attempt " + (i + 1));
                scores.add((int) Math.round(recentAll.get(i).getPerformanceScore()));
                baseline.add(60);
            }
            learningCurve.put("labels", labels);
            learningCurve.put("scores", scores);
            learningCurve.put("baseline", baseline);
        }

        List<Map<String, Object>> topicMastery = topicMastery(attempts);
        List<Map<String, Object>> weakTopics = topicMastery.stream()
                .map(t -> {
                    Map<String, Object> w = new LinkedHashMap<>();
                    String topic = String.valueOf(t.get("name"));
                    int score = ((Number) t.get("mastery")).intValue();
                    w.put("name", topic);
                    w.put("score", score);
                    w.put("status", score < 50 ? "High" : score < 75 ? "Medium" : "Low");
                    return w;
                })
                .sorted(Comparator.comparingInt(t -> ((Number) t.get("score")).intValue()))
                .limit(3)
                .collect(Collectors.toList());

        List<Map<String, Object>> activity = attempts.stream().limit(6).map(a -> {
            Map<String, Object> item = new LinkedHashMap<>();
            int score = (int) Math.round(a.getPerformanceScore());
            item.put("icon", "📝");
            item.put("bg", "#eff6ff");
            item.put("label", "Adaptive Quiz — " + a.getTopic());
            item.put("sub", "Score: " + score + "% · Difficulty: " + a.getDifficulty());
            item.put("score", score);
            item.put("time", a.getTimestamp() == null ? "Recently" : a.getTimestamp().format(DateTimeFormatter.ofPattern("MMM dd, h:mm a")));
            return item;
        }).collect(Collectors.toList());

        if (activity.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("icon", "🚀");
            item.put("bg", "#f0fdf4");
            item.put("label", "Welcome to your adaptive dashboard");
            item.put("sub", "Take your first quiz to generate real analytics.");
            item.put("score", null);
            item.put("time", "Today");
            activity.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("student", student);
        response.put("streakCalendar", buildStreakCalendar(activeDates));
        response.put("kpi", kpi);
        response.put("learningCurve", learningCurve);
        response.put("weakTopics", weakTopics);
        response.put("topicMastery", topicMastery);
        response.put("skillRadar", buildSkillRadar(email));
        List<Map<String, Object>> quizHistory = attempts.stream().map(a -> {
            Map<String, Object> item = new LinkedHashMap<>();
            int score = (int) Math.round(a.getPerformanceScore());
            item.put("title", a.getTopic() + " — Quiz");
            item.put("topic", a.getTopic());
            item.put("date", a.getTimestamp() == null ? "Recently" : a.getTimestamp().toLocalDate().toString());
            item.put("score", score);
            item.put("difficulty", cleanDifficulty(a.getDifficulty(), "Easy").toLowerCase());
            item.put("correctAnswers", a.getCorrectAnswers());
            item.put("totalItems", a.getTotalItems());
            return item;
        }).collect(Collectors.toList());

        response.put("activity", activity);
        response.put("quizHistory", quizHistory);
        response.put("nextQuiz", currentDiff + " quiz on " + (weakTopics.isEmpty() ? "Topic A" : weakTopics.get(0).get("name")));

        // Per-topic learning curves (keyed by topic name)
        Map<String, List<Attempt>> byTopic = new LinkedHashMap<>();
        for (Attempt a : chronological) {
            byTopic.computeIfAbsent(a.getTopic(), k -> new ArrayList<>()).add(a);
        }

        Map<String, Object> perTopicCurve = new LinkedHashMap<>();
        for (Map.Entry<String, List<Attempt>> entry : byTopic.entrySet()) {
            List<Attempt> ta = entry.getValue();
            if (ta.size() > 20) ta = ta.subList(ta.size() - 20, ta.size());

            List<String> tLabels   = new ArrayList<>();
            List<Integer> tScores  = new ArrayList<>();
            List<Integer> tBaseline = new ArrayList<>();
            for (int i = 0; i < ta.size(); i++) {
                tLabels.add("Attempt " + (i + 1));
                tScores.add((int) Math.round(ta.get(i).getPerformanceScore()));
                tBaseline.add(60);
            }
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("labels",   tLabels);
            td.put("scores",   tScores);
            td.put("baseline", tBaseline);
            perTopicCurve.put(entry.getKey(), td);
        }
        response.put("perTopicCurve", perTopicCurve);

        return response;
    }

    private Map<String, Object> kpi(String label, String value, String sub, String icon, String badge, String theme) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("value", value);
        map.put("sub", sub);
        map.put("icon", icon);
        map.put("badge", badge);
        map.put("theme", theme);
        return map;
    }

    private Map<String, Object> buildSkillRadar(String studentId) {
        String[] categories = {"Terminology", "Computation", "Application", "Analysis", "Process Steps"};
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Double> credit = new LinkedHashMap<>();
        for (String c : categories) { counts.put(c, 0); credit.put(c, 0.0); }

        for (QuestionPerformance qp : questionPerformanceRepository.findByStudentId(studentId)) {
            String cat = counts.containsKey(qp.getCategory()) ? qp.getCategory() : "Analysis";
            counts.put(cat, counts.get(cat) + 1);
            if (qp.getEssayScore() != null) {
                credit.put(cat, credit.get(cat) + (qp.getEssayScore() / 100.0));
            } else {
                credit.put(cat, credit.get(cat) + ("correct".equalsIgnoreCase(qp.getResult()) ? 1.0 : 0.0));
            }
        }

        List<String> labels = new ArrayList<>();
        List<Object> scores = new ArrayList<>();
        List<Integer> countList = new ArrayList<>();
        for (String c : categories) {
            labels.add(c);
            int n = counts.get(c);
            countList.add(n);
            scores.add(n > 0 ? (int) Math.round((credit.get(c) / n) * 100) : null);
        }

        Map<String, Object> radar = new LinkedHashMap<>();
        radar.put("categories", labels);
        radar.put("scores", scores);
        radar.put("counts", countList);
        return radar;
    }

    private int calculateSpi(List<Attempt> attempts, double avgScore) {
        if (attempts.isEmpty()) return 0;
        double latest = attempts.get(0).getPerformanceScore();
        double progress = 0;
        if (attempts.size() > 1) {
            progress = latest - attempts.get(attempts.size() - 1).getPerformanceScore();
        }
        double spi = (latest * 0.5) + (avgScore * 0.35) + (Math.max(0, Math.min(100, 50 + progress)) * 0.15);
        return (int) Math.round(Math.max(0, Math.min(100, spi)));
    }

    /**
     * Distinct calendar dates on which the student completed at least one quiz
     * attempt. Attempts with a null timestamp are ignored.
     */
    private Set<LocalDate> activeStudyDates(List<Attempt> attempts) {
        return attempts.stream()
                .filter(a -> a.getTimestamp() != null)
                .map(a -> a.getTimestamp().toLocalDate())
                .collect(Collectors.toSet());
    }

    /**
     * Real consecutive-day study streak, replacing the old placeholder of
     * Math.min(7, quizzesTaken) — which just counted total quizzes ever taken
     * (capped at 7) and had nothing to do with actual days.
     *
     * Counts backward from today: if the student studied today, the streak
     * includes today and keeps counting through every unbroken prior day. If
     * the student hasn't studied YET today but did study yesterday, the streak
     * is still shown as active (a one-day grace period so it doesn't visually
     * reset the instant midnight passes, before the student has had a chance
     * to study). If neither today nor yesterday has activity, the streak is
     * broken and returns 0.
     */
    private int calculateStreak(Set<LocalDate> activeDates) {
        if (activeDates.isEmpty()) return 0;

        LocalDate today = LocalDate.now();
        LocalDate cursor = activeDates.contains(today) ? today : today.minusDays(1);
        if (!activeDates.contains(cursor)) return 0;

        int streak = 0;
        while (activeDates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /**
     * Real 28-day activity calendar for the Reports page's streak grid (oldest
     * first, today last). 1 = studied that day, 0 = no recorded activity,
     * 2 = today (always marked this way regardless of whether today has
     * activity yet, matching the frontend's "Today" legend color).
     */
    private List<Integer> buildStreakCalendar(Set<LocalDate> activeDates) {
        List<Integer> calendar = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 27; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            calendar.add(day.equals(today) ? 2 : (activeDates.contains(day) ? 1 : 0));
        }
        return calendar;
    }

    private String cleanDifficulty(String nextDiff, String fallback) {
        String value = nextDiff == null || nextDiff.isBlank() ? fallback : nextDiff;
        if (value == null || value.isBlank()) return "Medium";
        value = value.trim().toLowerCase();
        return value.substring(0, 1).toUpperCase() + value.substring(1);
    }

    private List<Map<String, Object>> topicMastery(List<Attempt> attempts) {
        Map<String, List<Attempt>> grouped = new LinkedHashMap<>();
        for (Attempt a : attempts) {
            if (a.getTopic() == null) continue;
            grouped.computeIfAbsent(a.getTopic(), k -> new ArrayList<>()).add(a);
        }

        return grouped.entrySet().stream().map(entry -> {
            double avg = entry.getValue().stream().mapToDouble(Attempt::getPerformanceScore).average().orElse(0);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", entry.getKey());
            map.put("mastery", (int) Math.round(avg));
            return map;
        }).limit(10).collect(Collectors.toList());
    }
}
