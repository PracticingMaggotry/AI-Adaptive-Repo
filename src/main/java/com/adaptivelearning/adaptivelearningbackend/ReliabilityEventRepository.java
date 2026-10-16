package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only. Never delete. */
public interface ReliabilityEventRepository extends JpaRepository<ReliabilityEvent, Long> {
}
