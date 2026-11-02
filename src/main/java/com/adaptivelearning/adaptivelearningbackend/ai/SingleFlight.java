package com.adaptivelearning.adaptivelearningbackend.ai;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces identical concurrent work: while one caller is computing the value for a key, every other caller with
 * the same key waits for that result instead of paying for a second Anthropic call (double clicks, two tabs, two
 * classmates opening the same shared lesson at the same moment).
 */
public final class SingleFlight<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public V run(K key, long waitMs, Callable<V> work) throws Exception {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            try {
                return existing.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception ex) throw ex;
                throw e;
            }
        }
        try {
            V v = work.call();
            mine.complete(v);
            return v;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    public boolean isRunning(K key) { return inFlight.containsKey(key); }
}
