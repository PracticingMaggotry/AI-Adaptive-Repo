package com.adaptivelearning.adaptivelearningbackend;

public class Recommendation {
    private String nextTopic;
    private String nextDiff;
    private String reason;

    public Recommendation(String nextTopic, String nextDiff, String reason) {
        this.nextTopic = nextTopic;
        this.nextDiff = nextDiff;
        this.reason = reason;
    }

    public String getNextTopic() {
        return nextTopic;
    }

    public String getNextDiff() {
        return nextDiff;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "\nCurrent topic: " + nextTopic +
                "\n\nNext difficulty will be: " + nextDiff +
                "\n" + reason;
    }
}