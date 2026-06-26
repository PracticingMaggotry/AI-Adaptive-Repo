package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    @Query("""
    SELECT DISTINCT q.topic
    FROM Question q
    WHERE q.topic IS NOT NULL
      AND q.topic <> ''
    """)
    List<String> findDistinctTopicNames();

    // ── Owner-scoped (per-student) ──────────────────────────────────────
    @Query("SELECT q FROM Question q WHERE q.ownerId = :ownerId AND LOWER(q.topic) = LOWER(:topic) AND LOWER(q.difficulty) = LOWER(:difficulty)")
    List<Question> findByOwnerAndTopicAndDifficultyIgnoreCase(@Param("ownerId") String ownerId,
                                                              @Param("topic") String topic,
                                                              @Param("difficulty") String difficulty);

    @Transactional
    @Modifying
    @Query("DELETE FROM Question q WHERE q.ownerId = :ownerId AND LOWER(q.topic) = LOWER(:topic)")
    void deleteByOwnerAndTopicIgnoreCase(@Param("ownerId") String ownerId, @Param("topic") String topic);

    // ── Global (platform-wide, every student) ────────────────────────────
    // Used by TopicController.deleteTopicGlobally() — the admin "Delete
    // Topic" moderation action, which intentionally wipes a topic name for
    // EVERY student, not just one. Previously that method called findAll()
    // and filtered the entire questions table in a Java stream just to
    // collect the IDs to delete, which loads every row in the database into
    // memory regardless of how many actually match. This issues a single
    // DELETE statement scoped by topic, the same way the owner-scoped
    // version above is scoped by owner+topic.
    @Transactional
    @Modifying
    @Query("DELETE FROM Question q WHERE LOWER(q.topic) = LOWER(:topic)")
    void deleteByTopicIgnoreCase(@Param("topic") String topic);
}