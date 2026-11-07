package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BaselineTemplateRepository extends JpaRepository<BaselineTemplate, Long> {
    Optional<BaselineTemplate> findByContentHashAndTierAndPromptVersion(String contentHash, String tier, String promptVersion);
    List<BaselineTemplate> findByContentHash(String contentHash);
}
