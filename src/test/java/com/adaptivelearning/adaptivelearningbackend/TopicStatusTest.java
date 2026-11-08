package com.adaptivelearning.adaptivelearningbackend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the single set of cut-offs every learner page shares (browser copy: api.js topicBand). */
class TopicStatusTest {

    @Test
    void cutOffsAre60And80() {
        assertEquals(60, TopicStatus.WEAK_BELOW);
        assertEquals(80, TopicStatus.MASTERED_AT);
    }

    @Test
    void belowSixtyNeedsPractice() {
        assertTrue(TopicStatus.needsPractice(0));
        assertTrue(TopicStatus.needsPractice(59.4));
        assertFalse(TopicStatus.needsPractice(60));
        assertFalse(TopicStatus.needsPractice(95));
    }

    @Test
    void eightyAndUpIsMastered() {
        assertFalse(TopicStatus.mastered(79.9));
        assertTrue(TopicStatus.mastered(80));
        assertTrue(TopicStatus.mastered(100));
    }

    @Test
    void aTopicIsNeverBothWeakAndMastered() {
        for (int s = 0; s <= 100; s++) {
            assertFalse(TopicStatus.needsPractice(s) && TopicStatus.mastered(s), "score " + s);
        }
    }
}
