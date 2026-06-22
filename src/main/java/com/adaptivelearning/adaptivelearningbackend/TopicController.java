package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/topics")
public class TopicController {

    @Autowired private QuestionRepository questionRepository;
    @Autowired private AttemptRepository attemptRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private LessonCacheRepository lessonCacheRepository;
    @Autowired private QuestionPerformanceRepository questionPerformanceRepository;
    @Autowired private FirstQuizResultRepository firstQuizResultRepository;

    @GetMapping
    public List<String> getTopics() {
        return questionRepository.findDistinctTopicBy();
    }

    /**
     * Deletes a topic's data.
     *
     * Two callers, two scopes — this is intentional, not a gap:
     *   - Admins (admin.html / Admin.html "Delete Topic" button) get the
     *     OLD global behavior: every student's data for this topic name is
     *     wiped, because that's a moderation action over the whole platform.
     *   - Everyone else (quizhub.html drawer "Delete" button) can ONLY
     *     delete their OWN data for this topic. A student can no longer
     *     wipe another student's quiz history, materials, or attempts just
     *     by knowing or guessing a topic name they don't own.
     *
     * Without this session check, this endpoint had no ownership
     * verification at all — any logged-in student could DELETE any other
     * student's entire topic history.
     */
    @Transactional
    @DeleteMapping("/{topic}")
    public ResponseEntity<Map<String, Object>> deleteTopic(@PathVariable String topic, HttpSession session) {
        try {
            String studentId = (String) session.getAttribute("loggedInUserEmail");
            boolean isAdmin = Boolean.TRUE.equals(session.getAttribute("isAdmin"));

            if (studentId == null || studentId.isBlank()) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
            }

            if (isAdmin) {
                deleteTopicGlobally(topic);
            } else {
                deleteTopicForStudent(studentId, topic);
            }

            return ResponseEntity.ok(Map.of("success", true, "message", "Deleted: " + topic));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * Admin path — wipes this topic name for every student on the platform.
     * Same behavior as the original, unscoped deleteTopic().
     */
    private void deleteTopicGlobally(String topic) {
        List<Question> questions = questionRepository.findAll().stream()
                .filter(q -> q.getTopic() != null && q.getTopic().equalsIgnoreCase(topic))
                .toList();
        questionRepository.deleteAll(questions);

        List<Attempt> attempts = attemptRepository.findByTopicIgnoreCase(topic);
        attemptRepository.deleteAll(attempts);

        List<Material> materials = materialRepository.findByTopicIgnoreCaseOrderByUploadedAtDesc(topic);
        materials.forEach(this::deleteMaterialFiles);
        materialRepository.deleteAll(materials);

        lessonCacheRepository.deleteByTopicIgnoreCase(topic);
        questionPerformanceRepository.deleteByTopicIgnoreCase(topic);
        firstQuizResultRepository.deleteByTopicIgnoreCase(topic);
    }

    /**
     * Student path — wipes ONLY this student's own data for this topic name.
     * Other students' questions, attempts, and materials for the same topic
     * name are left untouched.
     */
    private void deleteTopicForStudent(String studentId, String topic) {
        questionRepository.deleteByOwnerAndTopicIgnoreCase(studentId, topic);

        List<Attempt> attempts = attemptRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);
        attemptRepository.deleteAll(attempts);

        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId).stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(topic))
                .toList();
        materials.forEach(this::deleteMaterialFiles);
        materialRepository.deleteAll(materials);

        lessonCacheRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        questionPerformanceRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        firstQuizResultRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
    }

    /**
     * Deletes the on-disk handout file and extracted diagram image (if any)
     * for a single Material row. Non-fatal — a missing or already-deleted
     * file is silently ignored so it never blocks the DB deletion that follows.
     */
    private void deleteMaterialFiles(Material material) {
        Path uploadDir = Paths.get("uploads", "materials");
        Path diagramDir = uploadDir.resolve("diagrams");

        if (material.getStoredFilename() != null) {
            try {
                Files.deleteIfExists(uploadDir.resolve(material.getStoredFilename()));
            } catch (Exception e) {
                System.err.println("Could not delete handout file (non-fatal): " + e.getMessage());
            }
        }
        if (material.getDiagramImageFilename() != null) {
            try {
                Files.deleteIfExists(diagramDir.resolve(material.getDiagramImageFilename()));
            } catch (Exception e) {
                System.err.println("Could not delete diagram file (non-fatal): " + e.getMessage());
            }
        }
    }
}