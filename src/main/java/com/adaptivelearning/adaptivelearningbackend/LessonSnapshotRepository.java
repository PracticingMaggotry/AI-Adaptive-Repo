package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LessonSnapshotRepository extends JpaRepository<LessonSnapshot, Long> {

    Optional<LessonSnapshot> findFirstByScopeIdAndTierAndStateHashAndPromptVersionOrderByIdDesc(
            Long scopeId, String tier, String stateHash, String promptVersion);

    Optional<LessonSnapshot> findFirstByScopeIdAndTierOrderByIdDesc(Long scopeId, String tier);
}
