package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: only save()/find*() are ever called on this repository. Never delete. */
public interface QuestionExposureLogRepository extends JpaRepository<QuestionExposureLog, Long> {
}