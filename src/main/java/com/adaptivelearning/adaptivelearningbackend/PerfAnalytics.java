package com.adaptivelearning.adaptivelearningbackend;
import java.util.List;

public class PerfAnalytics {

    public double calculateMasteryLevel(List<QuizAttempt> attempts, int lastN) {
    if (attempts == null || attempts.isEmpty()) {
        return 0;
    }

    // We only average the last N attempts (or fewer if not enough)
    int count = Math.min(lastN, attempts.size());

    double total = 0;

    // Start index of the last N attempts
    int startIndex = attempts.size() - count;

    for (int i = startIndex; i < attempts.size(); i++) {
        QuizAttempt a = attempts.get(i);
        double score = calculateperformanceScore(a.getcorrectans(), a.gettotalItems());
        total += score;
    }

    return total / count;
}

    //Analyze is just a method name
    public static Results analyze(QuizAttempt attempt) {

        //To calculate perfScore need muna kunin yung correctans and total items
        double performanceScore = calculateperformanceScore(
                attempt.getcorrectans(),
                attempt.gettotalItems()
        );

        /*Used to equalize the scores in each level
          For example, A score of 80 in easy is not equal to score of 80 in hard
          That's why it has weighted scoring, its job is to multiply the score of the user according to the difficulty.
        */
        double weight = getDifficultyWeight(attempt.getdifficulty());
        double weightedScore = performanceScore * weight;

        //Used to compare the score of previous quiz and current quiz
        double progress = performanceScore - attempt.getprevscore();
        boolean weakness = detectWeakness(performanceScore);
        String nextDifficulty = adjustDifficulty(performanceScore);

        //Updated data
        return new Results(
                performanceScore,
                weightedScore,
                progress,
                weakness,
                nextDifficulty
        );
    }

    //Performance score formula (Inside the document)
    private static double calculateperformanceScore(int correct, int total) {
        return ((double) correct / total) * 100;
    }

    //Equivalence of each difficulty
    private static double getDifficultyWeight(String difficulty) {
        switch (difficulty) {
            case "easy": return 1.0;
            case "medium": return 1.5;
            case "hard": return 2.0;
            default: return 1.0;
        }
    }

    //Used to determine the score of the student
    private static boolean detectWeakness(double performanceScore) {
        double errorRate = 100 - performanceScore;
        return errorRate >= 40;
    }

    //Used to determine what difficulty level is next for the user
    private static String adjustDifficulty(double performanceScore) {
        if (performanceScore >= 90) return "hard";
        if (performanceScore >= 70) return "medium";
        return "easy";
    }
}
