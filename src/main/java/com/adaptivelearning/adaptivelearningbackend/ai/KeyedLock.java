package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Runs work one caller at a time per key. Used so that when many students send the same new handout at once, the
 * first one pays for the analysis and the others wait, then find the stored result instead of paying again.
 * A caller that waits longer than {@code waitMs} runs anyway (a duplicate spend is better than a hung request).
 */
public final class KeyedLock {
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public <T> T with(String key, long waitMs, Supplier<T> work) {
        Entry e;
        synchronized (entries) {
            e = entries.computeIfAbsent(key, k -> new Entry());
            e.users++;
        }
        boolean locked = false;
        try {
            try {
                locked = e.lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return work.get();
        } finally {
            if (locked) e.lock.unlock();
            synchronized (entries) {
                if (--e.users == 0) entries.remove(key);
            }
        }
    }
}
