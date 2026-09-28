package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** JPA repository for per-student daily-limit overrides. */
public interface UserDailyLimitOverrideRepository extends JpaRepository<UserDailyLimitOverride, Long> {

    Optional<UserDailyLimitOverride> findByActionTypeAndStudentId(String actionType, String studentId);

    /** All overrides for a student, across every action type — used by the admin usage panel. */
    List<UserDailyLimitOverride> findByStudentId(String studentId);

    void deleteByActionTypeAndStudentId(String actionType, String studentId);
}