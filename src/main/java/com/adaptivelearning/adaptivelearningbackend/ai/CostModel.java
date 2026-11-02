package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.Locale;

/**
 * Converts token usage to money and decides how much AI a student may still use today. Prices are list prices in USD
 * per million tokens — verify them against the current Anthropic price list before relying on the numbers.
 */
public final class CostModel {
    private CostModel() {}

    public enum Level {
        /** Everything allowed. */ OK,
        /** Optional AI work is skipped or served from storage (re-categorising, regenerating a lesson). */ DEGRADED,
        /** No new question or lesson generation today; grading of an already-started quiz is still allowed. */ BLOCKED
    }

    /** Micro-dollars so daily totals can be stored and summed as integers. */
    public static long costMicros(String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        double inPrice, outPrice;
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("haiku")) { inPrice = 1.0; outPrice = 5.0; }
        else { inPrice = 3.0; outPrice = 15.0; } // Sonnet; anything unknown is priced like Sonnet (conservative)
        double usd = (inputTokens * inPrice + outputTokens * outPrice
                + cacheReadTokens * inPrice * 0.10 + cacheWriteTokens * inPrice * 1.25) / 1_000_000.0;
        return Math.round(usd * 1_000_000.0);
    }

    public static Level level(long spentMicros, double softUsd, double hardUsd) {
        double usd = spentMicros / 1_000_000.0;
        if (usd >= hardUsd) return Level.BLOCKED;
        if (usd >= softUsd) return Level.DEGRADED;
        return Level.OK;
    }
}
