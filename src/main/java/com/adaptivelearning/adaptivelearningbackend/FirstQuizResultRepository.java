package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FirstQuizResultRepository extends JpaRepository<FirstQuizResult, Long> {

    /** One row per (studentId, topic) with the locked general score and latest adapted result. */
    Optional<FirstQuizResult> findByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /** Deletes all rows for a topic. */
    void deleteByTopicIgnoreCase(String topic);

    /** Deletes a single student's row for a topic. */
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);
}