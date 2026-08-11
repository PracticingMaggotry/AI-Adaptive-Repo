package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface AttemptRepository extends JpaRepository<Attempt, Long> {
    List<Attempt> findByStudentIdOrderByTimestampDesc(String studentId);

    @Query("SELECT MAX(a.performanceScore) FROM Attempt a WHERE a.studentId = :studentId AND LOWER(a.topic) = LOWER(:topic)")
    Double findBestScoreByStudentIdAndTopic(@Param("studentId") String studentId, @Param("topic") String topic);

    @Query("SELECT a FROM Attempt a WHERE LOWER(a.topic) = LOWER(:topic)")
    List<Attempt> findByTopicIgnoreCase(@Param("topic") String topic);

    @Query("SELECT a FROM Attempt a WHERE a.studentId = :studentId AND LOWER(a.topic) = LOWER(:topic)")
    List<Attempt> findByStudentIdAndTopicIgnoreCase(@Param("studentId") String studentId, @Param("topic") String topic);

    /** All attempts across every student, newest first. */
    List<Attempt> findAllByOrderByTimestampDesc();

    /** All distinct topic names with at least one recorded attempt, platform-wide. */
    @Query(value = "SELECT DISTINCT topic FROM attempts WHERE topic IS NOT NULL AND topic <> ''", nativeQuery = true)
    List<String> queryAllDistinctTopics();
}