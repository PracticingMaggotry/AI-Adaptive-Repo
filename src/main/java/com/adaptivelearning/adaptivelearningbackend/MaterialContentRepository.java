package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/** JPA repository for the shared MaterialContent dedup registry. */
public interface MaterialContentRepository extends JpaRepository<MaterialContent, Long> {
    Optional<MaterialContent> findByContentHash(String contentHash);
}