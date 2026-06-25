package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    List<Material> findByUploadedByOrderByUploadedAtDesc(String uploadedBy);
    List<Material> findByTopicIgnoreCaseOrderByUploadedAtDesc(String topic);

    /**
     * Finds any existing material(s) this student already has for a given
     * topic name (case-insensitive). Used on upload to replace the old
     * material instead of silently piling up duplicate rows that all show
     * up as separate cards while only the newest one's questions are live.
     */
    List<Material> findByUploadedByAndTopicIgnoreCase(String uploadedBy, String topic);

    /**
     * All distinct topic names that have an uploaded Material row, platform-wide.
     * Used by TopicController to make sure a topic is visible to admins as soon
     * as a student uploads a handout for it — even before any Question rows
     * exist for that topic (e.g. AI question generation hasn't run yet, failed,
     * or every generated question was rejected by QuestionValidator). Without
     * this, /api/topics only reflected the `questions` table, so a topic with
     * real uploaded material but zero saved questions was invisible to the
     * admin panel (Topics & Content tab, totalTopics KPI) even though the
     * student could already see and use it via /api/materials.
     */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT m.topic FROM Material m")
    List<String> findDistinctTopicNames();
}