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

    // ── Owner-scoped (per-student) ──
    @Query("SELECT q FROM Question q WHERE q.ownerId = :ownerId AND LOWER(q.topic) = LOWER(:topic) AND LOWER(q.difficulty) = LOWER(:difficulty)")
    List<Question> findByOwnerAndTopicAndDifficultyIgnoreCase(@Param("ownerId") String ownerId,
                                                              @Param("topic") String topic,
                                                              @Param("difficulty") String difficulty);

    /** IDs captured before per-student topic deletion, so dependent QuestionReport rows can be cleaned up first. */
    @Query("SELECT q FROM Question q WHERE q.ownerId = :ownerId AND LOWER(q.topic) = LOWER(:topic)")
    List<Question> findByOwnerAndTopicIgnoreCase(@Param("ownerId") String ownerId,
                                                 @Param("topic") String topic);

    @Transactional
    @Modifying
    @Query("DELETE FROM Question q WHERE q.ownerId = :ownerId AND LOWER(q.topic) = LOWER(:topic)")
    void deleteByOwnerAndTopicIgnoreCase(@Param("ownerId") String ownerId, @Param("topic") String topic);

    // ── Global (all students) — used by admin's "Delete Topic". Single scoped DELETE instead of loading every row. ──
    @Transactional
    @Modifying
    @Query("DELETE FROM Question q WHERE LOWER(q.topic) = LOWER(:topic)")
    void deleteByTopicIgnoreCase(@Param("topic") String topic);
}