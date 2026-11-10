package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Replaces a student's question set for one topic as a SINGLE all-or-nothing step.
 *
 * <p>Deleting first and saving second would leave the student with no questions after a crash in
 * between. The new questions are saved FIRST and the old ones are removed afterwards, inside one
 * transaction:
 * if anything fails the transaction rolls back, nothing is deleted, and the old set stays exactly as it was.
 *
 * <p>An old question with a PENDING report is archived (never served again,
 * but admins can still read it) instead of deleted; archived rows already in the topic are left alone.
 *
 * <p>Fallback: each swap is retried once (it is atomic, so retrying is safe). If both attempts fail a
 * {@link ReplaceFailedException} is thrown and the old set is still in place. An empty replacement never
 * deletes anything.
 */
@Service
public class QuestionSetService {

    private static final int ATTEMPTS = 2;
    private static final long RETRY_PAUSE_MS = 150;

    private final QuestionRepository questionRepository;
    private final QuestionReportRepository questionReportRepository;
    private final TransactionTemplate tx;

    public QuestionSetService(QuestionRepository questionRepository,
                              QuestionReportRepository questionReportRepository,
                              PlatformTransactionManager transactionManager) {
        this.questionRepository = questionRepository;
        this.questionReportRepository = questionReportRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Thrown only after the swap failed and was rolled back; the previous question set is untouched. */
    public static class ReplaceFailedException extends RuntimeException {
        public ReplaceFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @return how many new questions were saved; 0 if {@code newQuestions} was empty (nothing is touched then)
     * @throws ReplaceFailedException if the swap could not be completed; the old set was kept
     */
    public int replaceSet(String ownerId, String topic, List<Question> newQuestions) {
        return replaceSet(ownerId, topic, newQuestions, q -> true);
    }

    /**
     * Same all-or-nothing swap, but only the live questions that match {@code replaceable} are removed afterwards;
     * every other question in the topic is left exactly as it is. Used where several sets share one topic
     * (the admin AI-testing sandbox keeps one "Test" set per demand level).
     */
    public int replaceSet(String ownerId, String topic, List<Question> newQuestions, Predicate<Question> replaceable) {
        if (newQuestions == null || newQuestions.isEmpty()) return 0;

        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            // A rolled-back attempt leaves generated ids on the entities; clear them so the retry INSERTs fresh rows.
            for (Question q : newQuestions) q.setId(null);
            try {
                Integer saved = tx.execute(status -> swap(ownerId, topic, newQuestions, replaceable));
                return saved == null ? 0 : saved;
            } catch (RuntimeException e) {
                last = e;
                System.err.println("Question set swap failed for topic \"" + topic + "\" (attempt " + attempt + "/"
                        + ATTEMPTS + "), existing questions kept: " + e.getMessage());
                if (attempt < ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_PAUSE_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        for (Question q : newQuestions) q.setId(null);
        throw new ReplaceFailedException(
                "Could not save the new questions, so your existing question set was kept.", last);
    }

    /**
     * What {@link #deleteOwnedQuestions} did. {@code skippedIds} are requested ids that were not deleted because no
     * such question exists any more or it is not this owner's; nothing was touched for them.
     */
    public record DeleteResult(List<Long> deletedIds, List<Long> skippedIds, int reportsResolved,
                               Map<String, Integer> deletedByTopic) {}

    /**
     * Admin delete of specific questions from one student's set, as a single all-or-nothing step.
     *
     * <p>Only questions owned by {@code ownerId} are removed; any other id is skipped, so a crafted request cannot
     * reach another student's questions. Pending reports on a removed question are closed as DELETED (same outcome
     * as deleting from the reports page) so the report queue does not keep an open item for a question that is gone.
     * Archived questions can be removed too. Answer history, attempts and exposure logs are not touched.
     */
    public DeleteResult deleteOwnedQuestions(String ownerId, Collection<Long> ids, String reviewerEmail) {
        Set<Long> requested = new LinkedHashSet<>();
        if (ids != null) for (Long id : ids) if (id != null) requested.add(id);
        if (requested.isEmpty()) return new DeleteResult(List.of(), List.of(), 0, Map.of());

        return tx.execute(status -> {
            List<Question> owned = questionRepository.findAllById(requested).stream()
                    .filter(q -> q.getOwnerId() != null && q.getOwnerId().equalsIgnoreCase(ownerId))
                    .toList();
            List<Long> deletedIds = owned.stream().map(Question::getId).toList();
            Set<Long> deletedSet = new HashSet<>(deletedIds);
            List<Long> skippedIds = requested.stream().filter(id -> !deletedSet.contains(id)).toList();

            Map<String, Integer> byTopic = new LinkedHashMap<>();
            for (Question q : owned) byTopic.merge(q.getTopic() == null ? "(no topic)" : q.getTopic(), 1, Integer::sum);

            int resolved = 0;
            if (!deletedIds.isEmpty()) {
                LocalDateTime now = LocalDateTime.now();
                List<QuestionReport> pending = questionReportRepository.findByQuestionIdIn(deletedIds).stream()
                        .filter(r -> "PENDING".equals(r.getStatus()))
                        .toList();
                for (QuestionReport r : pending) {
                    r.setStatus("DELETED");
                    r.setReviewedAt(now);
                    r.setReviewedBy(reviewerEmail);
                    r.setAdminNote("Question deleted by admin.");
                }
                questionReportRepository.saveAll(pending);
                resolved = pending.size();
                questionRepository.deleteAllByIdInBatch(deletedIds);
            }
            return new DeleteResult(deletedIds, skippedIds, resolved, byTopic);
        });
    }

    /** Runs inside the transaction. Order matters: new rows first, old rows only afterwards. */
    private int swap(String ownerId, String topic, List<Question> newQuestions, Predicate<Question> replaceable) {
        List<Long> oldIds = questionRepository.findByOwnerAndTopicIgnoreCase(ownerId, topic).stream()
                .filter(q -> !q.isArchived())
                .filter(replaceable)
                .map(Question::getId)
                .toList();

        questionRepository.saveAll(newQuestions);
        questionRepository.flush(); // surface any insert problem now, before a single old row is touched

        if (!oldIds.isEmpty()) {
            List<Long> reported = questionReportRepository.findDistinctQuestionIdsWithPendingReport(oldIds);
            Set<Long> keep = new HashSet<>(reported);
            if (!reported.isEmpty()) questionRepository.archiveByIds(reported);

            List<Long> toDelete = oldIds.stream().filter(id -> !keep.contains(id)).toList();
            if (!toDelete.isEmpty()) questionRepository.deleteAllByIdInBatch(toDelete);
        }
        return newQuestions.size();
    }
}
