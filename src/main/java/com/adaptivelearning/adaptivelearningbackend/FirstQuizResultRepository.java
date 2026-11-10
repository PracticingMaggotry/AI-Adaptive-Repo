package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface FirstQuizResultRepository extends JpaRepository<FirstQuizResult, Long> {

    /** One row per (studentId, topic) with the locked general score and latest adapted result. */
    Optional<FirstQuizResult> findByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /** Handouts this student has taken at least one Adapted Quiz on (the latest-adapted fields are set by those only). */
    java.util.List<FirstQuizResult> findByStudentIdAndLatestAdaptedScoreIsNotNull(String studentId);

    @Transactional
    void deleteByTopicIgnoreCase(String topic);

    @Transactional
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);
}
