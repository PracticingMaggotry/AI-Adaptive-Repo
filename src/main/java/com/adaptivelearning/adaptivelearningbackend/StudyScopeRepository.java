package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudyScopeRepository extends JpaRepository<StudyScope, Long> {

    Optional<StudyScope> findFirstByStudentIdAndTopicKeyAndStatusOrderByIdDesc(String studentId, String topicKey, String status);

    List<StudyScope> findByStudentIdAndTopicKeyAndStatus(String studentId, String topicKey, String status);

    List<StudyScope> findByStudentIdOrderByCreatedAtDesc(String studentId);

    List<StudyScope> findByTopicKeyAndStatus(String topicKey, String status);
}
