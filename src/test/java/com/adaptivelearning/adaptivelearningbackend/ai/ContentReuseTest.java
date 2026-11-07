package com.adaptivelearning.adaptivelearningbackend.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ContentReuseTest {

    private static final String BODY = ("Photosynthesis converts light energy into chemical energy stored in glucose. "
            + "It takes place in the chloroplasts, where chlorophyll absorbs light. The light reactions produce ATP "
            + "and NADPH, which the Calvin cycle then uses to fix carbon dioxide into sugar. ").repeat(2);

    @Test
    void sameContentRewrappedOrReformattedGetsTheSameFingerprint() {
        String rewrapped = BODY.replace(". ", ".\n\n").replace("light", "light ").replace("\u2019", "'");
        String bulleted = "• " + BODY.replace(", ", " – ");
        assertEquals(ContentReuse.fingerprint(BODY), ContentReuse.fingerprint(rewrapped));
        assertEquals(ContentReuse.fingerprint(BODY), ContentReuse.fingerprint(bulleted));
        assertTrue(ContentReuse.fingerprint(BODY).startsWith(ContentReuse.PREFIX));
    }

    @Test
    void differentContentOrTooShortTextDoesNotMatch() {
        assertNotEquals(ContentReuse.fingerprint(BODY), ContentReuse.fingerprint(BODY + " One more fact about stomata."));
        assertNull(ContentReuse.fingerprint("too short"));
        assertNull(ContentReuse.fingerprint(null));
    }

    @Test
    void legacyFingerprintIsTheOldTrimLowercaseHash() {
        assertNotNull(ContentReuse.legacyFingerprint(BODY));
        assertEquals(ContentReuse.legacyFingerprint(BODY), ContentReuse.legacyFingerprint("  " + BODY.toUpperCase() + " "));
        assertEquals(64, ContentReuse.legacyFingerprint(BODY).length());
        assertNull(ContentReuse.legacyFingerprint("short"));
    }

    @Test
    void blurbKeepsTheFirstSentencesWithinTheLimit() {
        String s = "This handout explains photosynthesis. It covers light reactions and the Calvin cycle. It ends with a quiz.";
        assertEquals("This handout explains photosynthesis. It covers light reactions and the Calvin cycle.",
                ContentReuse.blurb(s, 2, 400));
        assertEquals("", ContentReuse.blurb("  ", 2, 400));
        assertEquals("", ContentReuse.blurb(null, 2, 400));
        String cut = ContentReuse.blurb("word ".repeat(200), 2, 50);
        assertTrue(cut.length() <= 50 && cut.endsWith("..."));
    }

    @Test
    void keyedLockRunsSameKeyOneAtATimeAndDifferentKeysTogether() throws Exception {
        KeyedLock lock = new KeyedLock();
        AtomicInteger inside = new AtomicInteger(), maxInside = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> lock.with("same", 5000, () -> {
                int now = inside.incrementAndGet();
                maxInside.accumulateAndGet(now, Math::max);
                try { Thread.sleep(40); } catch (InterruptedException ignored) {}
                inside.decrementAndGet();
                return null;
            }));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        assertEquals(1, maxInside.get());
        assertEquals("ok", lock.with("other", 10, () -> "ok"));
    }

    @Test
    void keyedLockLetsAWaiterThroughAfterTheTimeout() throws Exception {
        KeyedLock lock = new KeyedLock();
        Thread holder = new Thread(() -> lock.with("k", 1000, () -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            return null;
        }));
        holder.start();
        Thread.sleep(50);
        assertEquals("ran", lock.with("k", 20, () -> "ran"));
        holder.join();
    }
}
