package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces a student's question set for one topic as a SINGLE all-or-nothing step.
 *
 * <p>Before this existed every generator did "delete the old set, then saveAll the new one" as two
 * separate steps, so a crash or DB hiccup in between left the student with no questions at all. Now the
 * new questions are saved FIRST and the old ones are removed afterwards, inside one transaction:
 * if anything fails the transaction rolls back, nothing is deleted, and the old set stays exactly as it was.
 *
 * <p>Rules kept from the old behaviour: an old question with a PENDING report is archived (never served again,
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
        if (newQuestions == null || newQuestions.isEmpty()) return 0;

        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            // A rolled-back attempt leaves generated ids on the entities; clear them so the retry INSERTs fresh rows.
            for (Question q : newQuestions) q.setId(null);
            try {
                Integer saved = tx.execute(status -> swap(ownerId, topic, newQuestions));
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

    /** Runs inside the transaction. Order matters: new rows first, old rows only afterwards. */
    private int swap(String ownerId, String topic, List<Question> newQuestions) {
        List<Long> oldIds = questionRepository.findByOwnerAndTopicIgnoreCase(ownerId, topic).stream()
                .filter(q -> !q.isArchived())
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
