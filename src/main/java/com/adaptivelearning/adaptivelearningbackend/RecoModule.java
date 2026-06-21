package com.adaptivelearning.adaptivelearningbackend;
public class RecoModule{
    public static Recommendation reco(Results result, String currentTopic, String difficulty){
        
        //Used to make the user go back to one level
        if(result.hasWeakness()){
            return new Recommendation(currentTopic, "easy", "Read again");
        }

        //Used to make user stay on the level and practice more
        if(practMore(result, difficulty)){
            return new Recommendation(currentTopic, difficulty, "Stay on this level and practice more");
        }

        return new Recommendation(getnextTopic(currentTopic), result.getnextDiff(), "Good job!");
        
        }
        
        //I used performanceScore here to measure the raw score of the student
        //Weighted score is to compare while PerformanceScore is the exact score
        private static boolean practMore(Results result, String difficulty){
            double score = result.getperformanceScore();
            switch(difficulty.toLowerCase()){
            case "easy": return score < 80;
            case "medium": return score < 70;
            case "hard": return score < 60;
            default: return score < 70;
        }
    }

        private static String getnextTopic(String topic){
            return topic;
        }

}
