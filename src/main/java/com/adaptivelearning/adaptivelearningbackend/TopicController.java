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
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;

    @GetMapping
    public ResponseEntity<?> getTopics(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }

        // Union of topics that have generated Question rows AND topics that
        // only have an uploaded Material row so far. Previously this only
        // returned questionRepository.findDistinctTopicBy(), which meant a
        // topic was invisible here (and to the admin panel that calls this
        // endpoint) until AI question generation succeeded for it. If
        // generation failed, errored, or every question got rejected by
        // QuestionValidator, the Material row still existed and the student
        // could already see the topic via /api/materials — but admins saw
        // nothing at all for that topic, with no indication it existed.
        //
        // Deduplicated case-insensitively (same convention as
        // MaterialController.toTitleCase / replaceExistingMaterialsForTopic),
        // keeping whichever casing was seen first, so "Data Structures" and
        // "data structures" don't show up as two separate rows.
        java.util.LinkedHashMap<String, String> byLowerCase = new java.util.LinkedHashMap<>();
        for (String t : materialRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }
        for (String t : questionRepository.findDistinctTopicBy()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }
        // Also include topics that only exist in the attempts table — covers
        // cases where a student took quizzes but their materials/questions were
        // later deleted, or where materials were never saved but attempts were.
        // Without this, the admin's Topics & Content table and totalTopics KPI
        // are blind to any topic whose only surviving trace is quiz history.
        for (String t : attemptRepository.queryAllDistinctTopics()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }

        return ResponseEntity.ok(new java.util.ArrayList<>(byLowerCase.values()));
    }

    /**
     * Deletes a topic's data.
     *
     * Two callers, two scopes — this is intentional, not a gap:
     *   - Admins (admin.html / Admin.html "Delete Topic" button) get the
     *     OLD global behavior: every student's data for this topic name is
     *     wiped, because that's a moderation action over the whole platform.
     *     This is now also recorded in AdminActivityLog so every admin sees
     *     it, not just whichever admin's browser triggered it.
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
                adminActivityLogRepository.save(new AdminActivityLog(
                        "delete-topic", "Deleted topic: " + topic, "Admin content moderation", studentId));
            } else {
                deleteTopicForStudent(studentId, topic);
            }

            return ResponseEntity.ok(Map.of("success", true, "message", "Deleted: " + topic));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private void deleteTopicGlobally(String topic) {
        questionRepository.deleteByTopicIgnoreCase(topic);

        List<Attempt> attempts = attemptRepository.findByTopicIgnoreCase(topic);
        attemptRepository.deleteAll(attempts);

        List<Material> materials = materialRepository.findByTopicIgnoreCaseOrderByUploadedAtDesc(topic);
        materials.forEach(this::deleteMaterialFiles);
        materialRepository.deleteAll(materials);

        lessonCacheRepository.deleteByTopicIgnoreCase(topic);
        questionPerformanceRepository.deleteByTopicIgnoreCase(topic);
        firstQuizResultRepository.deleteByTopicIgnoreCase(topic);
    }

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