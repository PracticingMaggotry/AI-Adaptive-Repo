package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionResponseRepository extends JpaRepository<QuestionResponse, Long> {

    /** Everything the student ever answered on this handout, oldest first (the mastery model replays all of it). */
    List<QuestionResponse> findByScopeIdOrderByAnsweredAtAsc(Long scopeId);

    long countByScopeId(Long scopeId);

    boolean existsByAttemptId(Long attemptId);

    List<QuestionResponse> findByAnsweredAtAfter(java.time.LocalDateTime since);
}
