package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TopicNoteRepository extends JpaRepository<TopicNote, Long> {
    Optional<TopicNote> findByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    @Transactional
    void deleteByStudentIdAndTopicIgnoreCase(String studentId, String topic);

    @Transactional
    void deleteByTopicIgnoreCase(String topic);
}
