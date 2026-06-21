package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BlockedIpRepository extends JpaRepository<BlockedIp, Long> {
    Optional<BlockedIp> findByIp(String ip);
    boolean existsByIp(String ip);
    void deleteByIp(String ip);
}