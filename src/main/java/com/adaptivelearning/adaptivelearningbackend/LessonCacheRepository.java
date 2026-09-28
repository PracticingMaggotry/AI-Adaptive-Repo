package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LessonCacheRepository extends JpaRepository<LessonCache, Long> {

    /** Returns the cached lesson JSON for this student, topic, and tier, if any. */
    Optional<LessonCache> findByStudentIdAndTopicIgnoreCaseAndTierIgnoreCase(
            String studentId, String topic, String tier);

    /** Deletes cached lessons for a single student's topic. */
    @Transactional
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /** Deletes all cached lessons for a topic. */
    @Transactional
    void deleteByTopicIgnoreCase(String topic);
}
