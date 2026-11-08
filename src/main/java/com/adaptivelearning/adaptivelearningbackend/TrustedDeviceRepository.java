package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {
    Optional<TrustedDevice> findByUserEmailAndTokenHash(String userEmail, String tokenHash);

    /** Forgets every remembered browser for the account (password change). Returns how many were removed. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TrustedDevice t WHERE LOWER(t.userEmail) = :email")
    int deleteAllForUser(@Param("email") String lowerCaseEmail);
}
