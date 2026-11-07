package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ANL-04: pins the Student Performance Index to the documented recency-weighted formula
 * SPI1 = S1, SPIn = 0.30 x Sn + 0.70 x SPI(n-1), rounded to an integer, over ALL saved attempts in time order.
 */
class DashboardSpiTest {

    /** Scores in the order the quizzes were taken (oldest first); the controller receives them newest-first. */
    private static int spi(double... scoresOldestFirst) throws Exception {
        List<Attempt> newestFirst = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 8, 0);
        for (int i = 0; i < scoresOldestFirst.length; i++) {
            Attempt a = new Attempt();
            a.setPerformanceScore(scoresOldestFirst[i]);
            a.setTimestamp(t.plusHours(i));
            newestFirst.add(0, a);
        }
        Method m = DashboardController.class.getDeclaredMethod("calculateSpi", List.class);
        m.setAccessible(true);
        return (int) m.invoke(new DashboardController(), newestFirst);
    }

    @Test
    void workbookSampleGives60Then66Then76() throws Exception {
        assertEquals(60, spi(60));
        assertEquals(66, spi(60, 80));
        assertEquals(76, spi(60, 80, 100));   // 76.2 rounded
    }

    @Test
    void noAttemptsIsZero() throws Exception {
        assertEquals(0, spi());
    }

    @Test
    void latestAttemptWeighsMoreThanOlderOnes() throws Exception {
        assertEquals(84, spi(100, 80, 60));   // 100 -> 94 -> 83.8
        assertEquals(76, spi(60, 80, 100));
    }

    @Test
    void resultIsClampedToZeroToHundred() throws Exception {
        assertEquals(100, spi(100, 100, 100));
        assertEquals(0, spi(0, 0));
    }
}
