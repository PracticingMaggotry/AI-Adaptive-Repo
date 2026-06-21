package com.adaptivelearning.adaptivelearningbackend;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface QuestionPerformanceRepository extends JpaRepository<QuestionPerformance, Long> {
    List<QuestionPerformance> findByStudentId(String studentId);

    @Transactional
    @Modifying
    @Query("DELETE FROM QuestionPerformance qp WHERE LOWER(qp.topic) = LOWER(:topic)")
    void deleteByTopicIgnoreCase(@Param("topic") String topic);

    @Transactional
    @Modifying
    @Query("DELETE FROM QuestionPerformance qp WHERE qp.studentId = :studentId AND LOWER(qp.topic) = LOWER(:topic)")
    void deleteByStudentIdAndTopicIgnoreCase(@Param("studentId") String studentId, @Param("topic") String topic);
}