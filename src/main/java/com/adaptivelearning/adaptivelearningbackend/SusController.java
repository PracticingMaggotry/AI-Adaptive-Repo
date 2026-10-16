package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sus")
public class SusController {

    @Autowired private ParticipantCodeRepository codes;
    @Autowired private SusResponseRepository repo;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null) return ResponseEntity.status(401).body(Map.of("success", false));
        Optional<ParticipantCode> p = codes.findByEmailIgnoreCase(email);
        boolean participant = p.isPresent() && !Boolean.TRUE.equals(session.getAttribute("isAdmin"));
        return ResponseEntity.ok(Map.of("success", true, "participant", participant,
                "submitted", participant && repo.existsByParticipantCode(p.get().getCode())));
    }

    /** Body: { "answers": [1-5 x10] } in item order. Score is computed here, never trusted from the client. */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, List<Integer>> body, HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null) return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        Optional<ParticipantCode> p = codes.findByEmailIgnoreCase(email);
        if (p.isEmpty() || Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Only enrolled study participants can submit."));
        }
        List<Integer> list = body == null ? null : body.get("answers");
        if (list == null || list.size() != 10 || list.stream().anyMatch(v -> v == null || v < 1 || v > 5)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Please answer all 10 items (1-5)."));
        }
        if (repo.existsByParticipantCode(p.get().getCode())) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Already submitted. Thank you!"));
        }
        try {
            repo.save(new SusResponse(p.get().getCode(), list.stream().mapToInt(Integer::intValue).toArray()));
        } catch (org.springframework.dao.DataIntegrityViolationException ignored) {}
        return ResponseEntity.ok(Map.of("success", true, "message", "Thank you!"));
    }
}
