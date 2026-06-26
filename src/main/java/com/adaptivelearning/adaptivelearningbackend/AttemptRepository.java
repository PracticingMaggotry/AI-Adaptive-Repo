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

    /**
     * Platform-wide attempt history (used by the admin dashboard's "Recent Quiz
     * Activity" panel). Unlike findByStudentIdOrderByTimestampDesc, this is not
     * scoped to any one student — admins never have quiz attempts of their own
     * (they're redirected away from every quiz page), so a per-student query
     * would always come back empty when called from an admin's session.
     */
    List<Attempt> findAllByOrderByTimestampDesc();

    /**
     * All distinct topic names that have at least one recorded attempt,
     * platform-wide. Uses a native SQL query (nativeQuery=true) to bypass
     * Spring Data JPA's method-name parser entirely — the method name
     * "queryAllDistinctTopics" has no special meaning to Spring Data so it
     * will never try to derive a query from it.
     */
    @Query(value = "SELECT DISTINCT topic FROM attempts WHERE topic IS NOT NULL AND topic <> ''", nativeQuery = true)
    List<String> queryAllDistinctTopics();
}