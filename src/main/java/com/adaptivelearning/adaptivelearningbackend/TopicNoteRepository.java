package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TopicNoteRepository extends JpaRepository<TopicNote, Long> {
    Optional<TopicNote> findByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /** Single student deleting their own topic. */
    @Transactional
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    /** Admin deleting a topic globally (every student's note for it). */
    @Transactional
    void deleteByTopicIgnoreCase(String topic);
}
