package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AssignedHandoutRepository extends JpaRepository<AssignedHandout, Long> {
    List<AssignedHandout> findByStudentEmailIgnoreCaseOrderByAssignedAtDesc(String studentEmail);
    List<AssignedHandout> findAllByOrderByAssignedAtDesc();
    List<AssignedHandout> findByStudentEmailIgnoreCaseAndTopicIgnoreCase(String studentEmail, String topic);

    /** True while any assignment still points at this stored file (used before deleting the file). */
    boolean existsByStoredFilename(String storedFilename);
}
