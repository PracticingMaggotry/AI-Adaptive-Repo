package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FirstQuizResultRepository extends JpaRepository<FirstQuizResult, Long> {

    /**
     * One row per (studentId, topic) — the locked general score plus
     * whatever the latest adapted quiz result is, if any.
     */
    Optional<FirstQuizResult> findByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /**
     * Used by TopicController when a topic is fully deleted.
     */
    void deleteByTopicIgnoreCase(String topic);

    /**
     * Used when a single student's data for a topic needs clearing.
     */
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);
}