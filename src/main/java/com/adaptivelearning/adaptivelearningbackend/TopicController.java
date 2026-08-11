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
    // Refcount-aware release of shared material content (see MaterialContentService).
    @Autowired private MaterialContentService materialContentService;
    @Autowired private TopicNoteRepository topicNoteRepository;
    @Autowired private QuestionReportRepository questionReportRepository;

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
     * Admins get the old global behavior (wipes the topic for every student,
     * logged to AdminActivityLog); everyone else can only delete their own data.
     *
     * Shared material content: only the calling student's (or, for admins, every
     * affected student's) own Material row(s) are deleted. Another student's copy
     * of the same content is unaffected — the underlying R2 bytes and AI output
     * are only released once no Material row anywhere references that hash
     * (see MaterialContentService.releaseIfOrphaned).
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
        releaseMaterials(materials);

        lessonCacheRepository.deleteByTopicIgnoreCase(topic);
        questionPerformanceRepository.deleteByTopicIgnoreCase(topic);
        firstQuizResultRepository.deleteByTopicIgnoreCase(topic);
        topicNoteRepository.deleteByTopicIgnoreCase(topic);
        questionReportRepository.deleteByTopicIgnoreCase(topic);
    }

    private void deleteTopicForStudent(String studentId, String topic) {
        // Captured before the bulk delete so only THIS student's reports get cleaned up,
        // not every report sharing this topic name across other students.
        List<Long> questionIdsBeingDeleted = questionRepository
                .findByOwnerAndTopicIgnoreCase(studentId, topic)
                .stream().map(Question::getId).toList();

        questionRepository.deleteByOwnerAndTopicIgnoreCase(studentId, topic);

        if (!questionIdsBeingDeleted.isEmpty()) {
            questionReportRepository.deleteByQuestionIdIn(questionIdsBeingDeleted);
        }

        List<Attempt> attempts = attemptRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);
        attemptRepository.deleteAll(attempts);

        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId).stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(topic))
                .toList();
        releaseMaterials(materials);

        lessonCacheRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        questionPerformanceRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        firstQuizResultRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        topicNoteRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
    }

    /**
     * Deletes the given Material rows, then releases each hash's shared content
     * (R2 bytes + diagram + registry row) only once no Material row anywhere
     * still references it.
     */
    private void releaseMaterials(List<Material> materials) {
        if (materials.isEmpty()) return;
        List<String> hashes = materials.stream().map(Material::getContentHash).toList();
        materialRepository.deleteAll(materials);
        hashes.forEach(materialContentService::releaseIfOrphaned);
    }
}