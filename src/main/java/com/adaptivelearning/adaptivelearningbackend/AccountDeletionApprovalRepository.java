package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AccountDeletionApprovalRepository extends JpaRepository<AccountDeletionApproval, Long> {
    List<AccountDeletionApproval> findByRequestId(Long requestId);
    Optional<AccountDeletionApproval> findByRequestIdAndAdminEmailIgnoreCase(Long requestId, String adminEmail);
}