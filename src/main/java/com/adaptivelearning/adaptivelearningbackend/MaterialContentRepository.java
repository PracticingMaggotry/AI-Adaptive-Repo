package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

/** JPA repository for the shared MaterialContent dedup registry. */
public interface MaterialContentRepository extends JpaRepository<MaterialContent, Long> {
    Optional<MaterialContent> findByContentHash(String contentHash);

    /** {primaryCategory, subCategory} pairs stored on shared content (labels a re-upload of the same file would reuse). */
    @Query("SELECT DISTINCT c.primaryCategory, c.subCategory FROM MaterialContent c "
            + "WHERE c.primaryCategory IS NOT NULL AND c.subCategory IS NOT NULL AND c.subCategory <> ''")
    List<Object[]> distinctSubCategories();

    /** Re-tags shared content under {@code category} whose sub-category is {@code from}. Returns rows changed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MaterialContent c SET c.subCategory = :to WHERE c.primaryCategory = :category AND c.subCategory = :from")
    int renameSubCategory(@Param("category") String category, @Param("from") String from, @Param("to") String to);
}
