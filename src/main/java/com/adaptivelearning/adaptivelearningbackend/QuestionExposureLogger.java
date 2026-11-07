package com.adaptivelearning.adaptivelearningbackend;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Records which questions were served to whom. Never throws: logging must not break quiz serving.
 *
 * <p>It also must not silently lose data, so a failed write is handled in three steps:
 * <ol>
 *   <li>the batch is retried once straight away;</li>
 *   <li>if that fails too, the batch is parked in a small bounded in-memory queue and flushed on the next serve
 *       (and on shutdown), keeping its original {@code served_at} so the record is still accurate;</li>
 *   <li>every failure is written to the reliability log, so it shows up on the admin reliability view instead of
 *       only in the console.</li>
 * </ol>
 * One serve is saved in a single transaction (saveAll), so a serve is logged completely or not at all.
 */
@Component
public class QuestionExposureLogger {

    private static final long RETRY_PAUSE_MS = 100;
    /** Parked batches kept while the DB is unreachable; the oldest is reported and dropped beyond this. */
    private static final int MAX_PENDING_BATCHES = 200;
    private static final int FLUSH_BATCHES_PER_CALL = 10;

    @Autowired private QuestionExposureLogRepository repo;
    @Autowired private ReliabilityLogger reliabilityLogger;

    private final Deque<List<QuestionExposureLog>> pending = new ArrayDeque<>();

    public void logServed(String studentId, String topic, String requestedDifficulty, List<Question> served) {
        if (studentId == null || served == null || served.isEmpty()) return;
        try {
            String serveId = UUID.randomUUID().toString();
            List<QuestionExposureLog> rows = new ArrayList<>(served.size());
            for (int i = 0; i < served.size(); i++) {
                Question q = served.get(i);
                // Use the topic the question is stored under, not whatever casing the request URL happened to carry,
                // so one topic is never split into two in the dataset.
                String rowTopic = q.getTopic() != null && !q.getTopic().isBlank() ? q.getTopic() : topic;
                rows.add(new QuestionExposureLog(serveId, studentId, rowTopic, requestedDifficulty, q, i));
            }
            if (!saveWithRetry(rows)) {
                park(rows);
                return; // DB is struggling; the parked batch is retried on the next serve
            }
        } catch (Exception e) {
            report("could not build or save exposure rows: " + e.getMessage());
            return;
        }
        flushPending(); // the DB just accepted a write, so try to clear anything parked earlier
    }

    /** Best-effort last attempt for anything still parked when the app stops. */
    @PreDestroy
    public void flushOnShutdown() {
        flushPending();
        int left;
        synchronized (pending) { left = pending.size(); }
        if (left > 0) report(left + " exposure batch(es) could not be saved before shutdown and were lost");
    }

    /** How many served batches are waiting to be written (0 when healthy). */
    public int pendingBatches() {
        synchronized (pending) { return pending.size(); }
    }

    private boolean saveWithRetry(List<QuestionExposureLog> rows) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                repo.saveAll(rows);
                return true;
            } catch (Exception e) {
                rows.forEach(QuestionExposureLog::forgetId);
                report("exposure log write failed (attempt " + attempt + "/2): " + e.getMessage());
                if (attempt == 1) {
                    try {
                        Thread.sleep(RETRY_PAUSE_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void park(List<QuestionExposureLog> rows) {
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING_BATCHES) {
                List<QuestionExposureLog> dropped = pending.pollFirst();
                report("exposure backlog full: dropped the oldest unsaved batch of "
                        + (dropped == null ? 0 : dropped.size()) + " question(s)");
            }
            pending.addLast(rows);
        }
    }

    private void flushPending() {
        for (int i = 0; i < FLUSH_BATCHES_PER_CALL; i++) {
            List<QuestionExposureLog> batch;
            synchronized (pending) { batch = pending.peekFirst(); }
            if (batch == null) return;
            try {
                repo.saveAll(batch);
            } catch (Exception e) {
                batch.forEach(QuestionExposureLog::forgetId);
                return; // still failing; leave it parked
            }
            synchronized (pending) {
                if (pending.peekFirst() == batch) pending.pollFirst(); else pending.remove(batch);
            }
        }
    }

    private void report(String detail) {
        System.err.println("Question exposure logging (non-fatal): " + detail);
        try { reliabilityLogger.pipelineFailure("exposure-log", detail); } catch (Exception ignored) { }
    }
}
