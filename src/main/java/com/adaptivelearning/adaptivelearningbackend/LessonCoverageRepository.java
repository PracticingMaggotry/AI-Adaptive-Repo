package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LessonCoverageRepository extends JpaRepository<LessonCoverage, Long> {
    List<LessonCoverage> findByScopeId(Long scopeId);
}
