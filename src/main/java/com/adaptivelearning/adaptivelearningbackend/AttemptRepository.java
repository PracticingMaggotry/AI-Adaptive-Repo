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
     * platform-wide. Used by TopicController so that topics show up in the
     * admin panel even when a student's materials or questions have been
     * deleted but their quiz history still exists.
     *
     * Named getAllDistinctTopics() rather than findDistinctTopicNames() to
     * avoid Spring Data JPA trying to parse it as a derived query (it would
     * attempt to resolve "TopicNames" as a field on Attempt and throw a
     * 500 at startup/call time).
     */
    @Query("SELECT DISTINCT a.topic FROM Attempt a WHERE a.topic IS NOT NULL AND a.topic <> ''")
    List<String> getAllDistinctTopics();
}