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
}