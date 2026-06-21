package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<String> findDistinctTopicBy();

    // ── Owner-scoped (per-student) ──────────────────────────────────────
    @Query("SELECT q FROM Question q WHERE q.ownerId = :ownerId AND LOWER(q.topic) = LOWER(:topic) AND LOWER(q.difficulty) = LOWER(:difficulty)")
    List<Question> findByOwnerAndTopicAndDifficultyIgnoreCase(@Param("ownerId") String ownerId,
                                                              @Param("topic") String topic,
                                                              @Param("difficulty") String difficulty);

    @Transactional
    @Modifying
    @Query("DELETE FROM Question q WHERE q.ownerId = :ownerId AND LOWER(q.topic) = LOWER(:topic)")
    void deleteByOwnerAndTopicIgnoreCase(@Param("ownerId") String ownerId, @Param("topic") String topic);
}