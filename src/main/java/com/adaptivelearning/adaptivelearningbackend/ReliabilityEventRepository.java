package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Append-only. Never delete. */
public interface ReliabilityEventRepository extends JpaRepository<ReliabilityEvent, Long> {

    /** Events at or after {@code from}; the Pageable carries the row cap and newest-first sort. */
    List<ReliabilityEvent> findByOccurredAtGreaterThanEqual(LocalDateTime from, Pageable pageable);

    Optional<ReliabilityEvent> findFirstByOrderByOccurredAtAsc();
}
