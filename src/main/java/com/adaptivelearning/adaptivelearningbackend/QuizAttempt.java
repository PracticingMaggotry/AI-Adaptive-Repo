//Used for every quiz
package com.adaptivelearning.adaptivelearningbackend;
public class QuizAttempt{
    private int totalItems;
    private int correctans;
    private double prevscore;
    private String difficulty;


public QuizAttempt(int totalItems, int correctans, double prevscore, String difficulty){
    this.totalItems = totalItems;
    this.correctans = correctans;
    this.prevscore = prevscore;
    this.difficulty = difficulty;
}

public int gettotalItems(){
    return totalItems;
}

public int getcorrectans(){
    return correctans;
}

public double getprevscore(){
    return prevscore;
}

public String getdifficulty(){
    return difficulty;
    }

}



