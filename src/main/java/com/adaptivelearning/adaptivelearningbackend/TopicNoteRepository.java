package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TopicNoteRepository extends JpaRepository<TopicNote, Long> {
    Optional<TopicNote> findByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /** Single student deleting their own topic. */
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /** Admin deleting a topic globally (every student's note for it). */
    void deleteByTopicIgnoreCase(String topic);
}