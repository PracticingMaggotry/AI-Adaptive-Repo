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
    // Refcount-aware release of shared material content (see MaterialContent /
    // MaterialContentService). Topic deletion here still only ever touches
    // the deleting student's (or, for admins, every student's) own Material
    // rows — the underlying R2 file bytes for a piece of content shared by
    // multiple students are only actually deleted once EVERY student who
    // uploaded it has deleted the topic it was attached to.
    @Autowired private MaterialContentService materialContentService;

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
     *
     * NOTE on shared material content: this always deletes only the calling
     * student's (or, for admins, every affected student's) own Material
     * row(s) — topic deletion remains strictly student-owned. If another
     * student uploaded the exact same content and still has it attached to
     * one of their own topics, that student's copy is completely
     * unaffected: the underlying R2 file bytes and AI-generated summary/
     * category/knowledge-extract are only actually deleted once NO
     * Material row anywhere still references that content (see
     * MaterialContentService.releaseIfOrphaned).
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
    }

    private void deleteTopicForStudent(String studentId, String topic) {
        questionRepository.deleteByOwnerAndTopicIgnoreCase(studentId, topic);

        List<Attempt> attempts = attemptRepository.findByStudentIdAndTopicIgnoreCase(studentId, topic);
        attemptRepository.deleteAll(attempts);

        List<Material> materials = materialRepository.findByUploadedByOrderByUploadedAtDesc(studentId).stream()
                .filter(m -> m.getTopic() != null && m.getTopic().equalsIgnoreCase(topic))
                .toList();
        releaseMaterials(materials);

        lessonCacheRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        questionPerformanceRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
        firstQuizResultRepository.deleteByStudentIdAndTopicIgnoreCase(studentId, topic);
    }

    /**
     * Deletes the given Material rows, then releases each one's shared
     * content (R2 handout bytes + diagram image + the MaterialContent
     * registry row) ONLY for hashes that no longer have any remaining
     * Material row pointing at them anywhere in the system — i.e. only
     * once every student who uploaded that exact content has deleted it.
     * Replaces the old direct-delete-every-time behavior from before
     * shared material content existed.
     */
    private void releaseMaterials(List<Material> materials) {
        if (materials.isEmpty()) return;
        List<String> hashes = materials.stream().map(Material::getContentHash).toList();
        materialRepository.deleteAll(materials);
        hashes.forEach(materialContentService::releaseIfOrphaned);
    }
}