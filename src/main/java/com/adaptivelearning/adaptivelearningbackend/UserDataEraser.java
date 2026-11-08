package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Permanently erases everything held about one account. Per-user data is keyed by the email string rather than
 * by a foreign key, so deleting the User row alone leaves it all behind (and a later registration with the same
 * address would inherit it). Matches both the current email and the pre-deletion "original email", because a
 * self-requested deletion swaps the account email for a placeholder while the data stays under the real address.
 *
 * Study participants: the participant-code link, consent records and SUS responses are erased too.
 * The BannedEmail list is intentionally untouched (a ban must outlive the account).
 *
 * All database work is one transaction. Stored files are removed only after it commits, so a rollback never
 * loses files that are still referenced.
 */
@Service
public class UserDataEraser {

    static final String ERASED = "[erased]";

    @PersistenceContext private EntityManager em;
    @Autowired private UserRepository userRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private ParticipantCodeRepository participantCodeRepository;
    @Autowired private AssignedHandoutRepository assignedHandoutRepository;
    @Autowired private MaterialContentService materialContentService;
    @Autowired private FileStorageService fileStorageService;

    @Transactional
    public void eraseAndDelete(User user) {
        Set<String> emails = new LinkedHashSet<>();
        addEmail(emails, user.getEmail());
        addEmail(emails, user.getOriginalEmail());

        Set<String> contentHashesToRelease = new LinkedHashSet<>();
        List<String> fileKeysToDelete = new ArrayList<>();
        List<String> assignedFilesToCheck = new ArrayList<>();

        for (String e : emails) {
            // Admin log first: it matches on the email text, which later steps don't touch but the User row carries.
            scrubAdminLog(e, user.getFullName());

            // Study identity: code link, then the coded rows that hang off it.
            participantCodeRepository.findByEmailIgnoreCase(e).ifPresent(pc -> {
                exec("DELETE FROM ConsentRecord r WHERE r.participantCode = :c", "c", pc.getCode());
                exec("DELETE FROM SusResponse r WHERE r.participantCode = :c", "c", pc.getCode());
                participantCodeRepository.delete(pc);
            });

            // Uploaded handouts: remember what to release, then drop the rows.
            List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(e);
            for (Material m : materials) {
                if (m.getContentHash() != null && !m.getContentHash().isBlank()) {
                    contentHashesToRelease.add(m.getContentHash());
                } else {                                          // pre-dedup rows own their files directly
                    if (m.getStoredFilename() != null) fileKeysToDelete.add(FileStorageService.handoutKey(m.getStoredFilename()));
                    if (m.getDiagramImageFilename() != null) fileKeysToDelete.add(FileStorageService.diagramKey(m.getDiagramImageFilename()));
                }
            }
            materialRepository.deleteAll(materials);

            // Admin-assigned handouts (the stored file may be shared by several students: checked after commit).
            for (AssignedHandout a : assignedHandoutRepository.findByStudentEmailIgnoreCaseOrderByAssignedAtDesc(e)) {
                if (a.getStoredFilename() != null) assignedFilesToCheck.add(a.getStoredFilename());
            }
            exec("DELETE FROM AssignedHandout a WHERE lower(a.studentEmail) = :e", "e", e);

            // Children before parents.
            exec("DELETE FROM LessonCoverage c WHERE c.scopeId IN (SELECT s.id FROM StudyScope s WHERE lower(s.studentId) = :e)", "e", e);
            exec("DELETE FROM QuestionReport r WHERE lower(r.reporterEmail) = :e "
                    + "OR r.questionId IN (SELECT q.id FROM Question q WHERE lower(q.ownerId) = :e)", "e", e);

            for (String entity : new String[]{
                    "Attempt", "AiUsage", "DailyActionCount", "FirstQuizResult", "LessonCache", "LessonSnapshot",
                    "QuestionExposureLog", "QuestionPerformance", "QuestionResponse", "StudyScope", "TopicNote",
                    "UserDailyLimitOverride"}) {
                exec("DELETE FROM " + entity + " x WHERE lower(x.studentId) = :e", "e", e);
            }
            exec("DELETE FROM Question q WHERE lower(q.ownerId) = :e", "e", e);
            exec("DELETE FROM TrustedDevice t WHERE lower(t.userEmail) = :e", "e", e);
        }

        em.flush();
        userRepository.deleteById(user.getId());

        final Set<String> hashes = contentHashesToRelease;
        final List<String> keys = fileKeysToDelete;
        final List<String> assigned = assignedFilesToCheck;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (String k : keys) fileStorageService.delete(k);
                for (String h : hashes) materialContentService.releaseIfOrphaned(h);
                for (String f : assigned) {
                    if (!assignedHandoutRepository.existsByStoredFilename(f)) {
                        fileStorageService.delete(FileStorageService.assignedKey(f));
                    }
                }
            }
        });
    }

    /**
     * Removes the person's email and name from admin-log rows that mention the email, and their email from the
     * "performed by" column. Rows are kept (the audit trail still shows that something happened).
     */
    private void scrubAdminLog(String email, String fullName) {
        String like = "%" + email + "%";
        if (fullName != null && fullName.trim().length() >= 3) {
            String name = fullName.trim();
            em.createQuery("UPDATE AdminActivityLog l SET l.message = REPLACE(l.message, :n, :r), l.detail = REPLACE(l.detail, :n, :r) "
                            + "WHERE lower(l.message) LIKE :p OR lower(l.detail) LIKE :p")
                    .setParameter("n", name).setParameter("r", ERASED).setParameter("p", like).executeUpdate();
        }
        em.createQuery("UPDATE AdminActivityLog l SET l.message = REPLACE(l.message, :e, :r), l.detail = REPLACE(l.detail, :e, :r) "
                        + "WHERE lower(l.message) LIKE :p OR lower(l.detail) LIKE :p")
                .setParameter("e", email).setParameter("r", ERASED).setParameter("p", like).executeUpdate();
        em.createQuery("UPDATE AdminActivityLog l SET l.performedBy = :r WHERE lower(l.performedBy) = :e")
                .setParameter("e", email).setParameter("r", ERASED).executeUpdate();
    }

    private void exec(String jpql, String param, Object value) {
        em.createQuery(jpql).setParameter(param, value).executeUpdate();
    }

    private static void addEmail(Set<String> set, String email) {
        if (email != null && !email.isBlank()) set.add(email.trim().toLowerCase(Locale.ROOT));
    }
}
