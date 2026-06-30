package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    @Autowired private FileStorageService fileStorageService;

    @GetMapping
    public ResponseEntity<?> getTopics(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }

        java.util.LinkedHashMap<String, String> byLowerCase = new java.util.LinkedHashMap<>();
        for (String t : materialRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }
        for (String t : questionRepository.findDistinctTopicNames()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }
        for (String t : attemptRepository.queryAllDistinctTopics()) {
            if (t != null && !t.isBlank()) byLowerCase.putIfAbsent(t.toLowerCase(), t);
        }

        return ResponseEntity.ok(new java.util.ArrayList<>(byLowerCase.values()));
    }

    /**
     * Deletes a topic's data.
     *
     * Two callers, two scopes — this is intentional, not a gap:
     *   - Admins get the OLD global behavior: every student's data for this
     *     topic name is wiped, recorded in AdminActivityLog.
     *   - Everyone else can ONLY delete their OWN data for this topic.
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

    /**
     * Deletes a material's R2 objects (handout file + diagram image if any).
     * Replaces the old local-disk Files.deleteIfExists calls — see
     * FileStorageService for the R2-backed implementation.
     */
    private void deleteMaterialFiles(Material material) {
        if (material.getStoredFilename() != null) {
            fileStorageService.delete(FileStorageService.handoutKey(material.getStoredFilename()));
        }
        if (material.getDiagramImageFilename() != null) {
            fileStorageService.delete(FileStorageService.diagramKey(material.getDiagramImageFilename()));
        }
    }
}