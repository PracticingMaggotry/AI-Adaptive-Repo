package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LessonCacheRepository extends JpaRepository<LessonCache, Long> {

    /**
     * Exact cache hit — same student, same topic, same tier.
     * Returns the stored lesson JSON so Claude is NOT called again.
     */
    Optional<LessonCache> findByStudentIdAndTopicIgnoreCaseAndTierIgnoreCase(
            String studentId, String topic, String tier);

    /**
     * Used by TopicController when a topic is deleted — wipes cached lessons too.
     */
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /**
     * Used when a topic is fully deleted (admin / drawer delete button).
     */
    void deleteByTopicIgnoreCase(String topic);
}