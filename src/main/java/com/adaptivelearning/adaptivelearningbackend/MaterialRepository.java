package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    List<Material> findByUploadedByOrderByUploadedAtDesc(String uploadedBy);
    List<Material> findByTopicIgnoreCaseOrderByUploadedAtDesc(String topic);

    List<Material> findByUploadedByAndTopicIgnoreCase(String uploadedBy, String topic);

    /** True if any Material row anywhere still references this content hash (used for refcounted cleanup). */
    boolean existsByContentHash(String contentHash);

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT m.topic FROM Material m")
    List<String> findDistinctTopicNames();

    /** {primaryCategory, subCategory, count} for every tagged sub-category. Feeds sub-category dedup. */
    @Query("SELECT m.primaryCategory, m.subCategory, COUNT(m) FROM Material m "
            + "WHERE m.primaryCategory IS NOT NULL AND m.subCategory IS NOT NULL AND m.subCategory <> '' "
            + "GROUP BY m.primaryCategory, m.subCategory")
    List<Object[]> countSubCategories();

    /** Re-tags every material under {@code category} whose sub-category is {@code from}. Returns rows changed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Material m SET m.subCategory = :to WHERE m.primaryCategory = :category AND m.subCategory = :from")
    int renameSubCategory(@Param("category") String category, @Param("from") String from, @Param("to") String to);
}
