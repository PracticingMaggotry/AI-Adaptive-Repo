package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Student-scoped, read-only endpoint for the handout/topic/concept weakness report. */
@RestController
public class ConceptWeaknessReportController {

    private final ConceptWeaknessReportService reportService;

    public ConceptWeaknessReportController(ConceptWeaknessReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/api/learner/concept-weakness")
    public ResponseEntity<?> getConceptWeaknessReport(HttpSession session) {
        String studentId = (String) session.getAttribute("loggedInUserEmail");
        if (studentId == null || studentId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));
        }
        return ResponseEntity.ok(reportService.build(studentId));
    }
}
