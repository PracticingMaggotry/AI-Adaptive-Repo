package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** JPA repository for uploaded Material rows. */
public interface MaterialRepository extends JpaRepository<Material, Long> {
    List<Material> findByUploadedByOrderByUploadedAtDesc(String uploadedBy);
    List<Material> findByTopicIgnoreCaseOrderByUploadedAtDesc(String topic);

    /** Finds a student's existing material(s) for a topic, so re-uploads can replace rather than duplicate. */
    List<Material> findByUploadedByAndTopicIgnoreCase(String uploadedBy, String topic);

    /** True if any Material row anywhere still references this content hash (used for refcounted cleanup). */
    boolean existsByContentHash(String contentHash);

    /** All distinct topic names that have an uploaded Material row, platform-wide. */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT m.topic FROM Material m")
    List<String> findDistinctTopicNames();
}