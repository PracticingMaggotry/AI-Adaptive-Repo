package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserDailyLimitOverrideRepository extends JpaRepository<UserDailyLimitOverride, Long> {

    Optional<UserDailyLimitOverride> findByActionTypeAndStudentId(String actionType, String studentId);

    List<UserDailyLimitOverride> findByStudentId(String studentId);

    void deleteByActionTypeAndStudentId(String actionType, String studentId);
}