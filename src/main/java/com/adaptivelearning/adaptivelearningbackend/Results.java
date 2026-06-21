//Result of quiz 
package com.adaptivelearning.adaptivelearningbackend;
public class Results{
    public double performanceScore;
    public double weightedScore;
    public double progress;
    public boolean weakness;
    public String nextDiff;

//Just a bunch of variable to be used in the computation java file
public Results(double performanceScore, double weightedScore, double progress, boolean weakness, String nextDiff){
    this.performanceScore = performanceScore;
    this.progress = progress;
    this.weightedScore = weightedScore;
    this.weakness = weakness;
    this.nextDiff = nextDiff;
    }
    
    public boolean hasWeakness(){
        return weakness;
    }
    public double getperformanceScore(){
        return performanceScore;
    }
    public String getnextDiff(){
        return nextDiff;
    }
}