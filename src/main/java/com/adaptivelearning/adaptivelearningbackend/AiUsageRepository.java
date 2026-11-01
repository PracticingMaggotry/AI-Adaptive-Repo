package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {

    Optional<AiUsage> findByStudentIdAndDayAndTaskAndModel(String studentId, LocalDate day, String task, String model);

    @Query("SELECT COALESCE(SUM(u.costMicros), 0) FROM AiUsage u WHERE u.studentId = :studentId AND u.day = :day")
    long sumCostMicros(@Param("studentId") String studentId, @Param("day") LocalDate day);

    List<AiUsage> findByDayGreaterThanEqual(LocalDate from);
}
