package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/consent")
public class ConsentController {

    @Autowired private ConsentService consentService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null) return ResponseEntity.status(401).body(Map.of("success", false));
        boolean admin = Boolean.TRUE.equals(session.getAttribute("isAdmin"));
        boolean required = !admin && consentService.isParticipant(email);
        return ResponseEntity.ok(Map.of("success", true, "required", required,
                "accepted", !required || consentService.hasConsented(email),
                "version", ConsentService.VERSION));
    }

    @PostMapping("/accept")
    public ResponseEntity<Map<String, Object>> accept(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null) return ResponseEntity.status(401).body(Map.of("success", false));
        consentService.accept(email);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
