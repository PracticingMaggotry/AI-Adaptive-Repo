package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/** Backs learninghub.html's floating notepad. Scoped strictly to the logged-in student's own notes. */
@RestController
@RequestMapping("/api/notes")
public class TopicNoteController {

    /** Generous but bounded — this is a scratchpad, not a document editor. */
    private static final int MAX_NOTE_LENGTH = 20_000;

    @Autowired private TopicNoteRepository topicNoteRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNote(
            @RequestParam String topic, HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));

        Optional<TopicNote> note = topicNoteRepository.findByStudentIdAndTopicIgnoreCase(email, topic);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "content", note.map(TopicNote::getContent).orElse("")
        ));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> saveNote(
            @RequestBody NoteRequest request, HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        if (request.topic == null || request.topic.isBlank())
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Topic is required."));

        String content = request.content == null ? "" : request.content;
        if (content.length() > MAX_NOTE_LENGTH) {
            content = content.substring(0, MAX_NOTE_LENGTH);
        }

        TopicNote note = topicNoteRepository.findByStudentIdAndTopicIgnoreCase(email, request.topic)
                .orElseGet(() -> new TopicNote(email, request.topic, ""));
        note.setContent(content);
        note.setUpdatedAt(LocalDateTime.now());
        topicNoteRepository.save(note);

        return ResponseEntity.ok(Map.of("success", true));
    }

    public static class NoteRequest {
        public String topic;
        public String content;
    }
}