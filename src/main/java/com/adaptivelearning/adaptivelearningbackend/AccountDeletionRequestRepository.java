package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequest, Long> {
    List<AccountDeletionRequest> findByStatusOrderByRequestedAtDesc(String status);
    List<AccountDeletionRequest> findAllByOrderByRequestedAtDesc();
    Optional<AccountDeletionRequest> findByTargetEmailIgnoreCaseAndStatus(String targetEmail, String status);
}