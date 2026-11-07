package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only cleanup of near-identical sub-categories (see {@link SubCategoryService}).
 *
 * <p>Two steps by design: {@code POST /preview} proposes merges and writes nothing; {@code POST /apply} writes only the
 * merges posted back to it. The AI is never re-run on apply, so what an admin approved is exactly what is written.
 * POST is a state-changing admin call, so AdminSessionGuard's step-up requirement already covers both.
 */
@RestController
@RequestMapping("/api/admin/subcategories")
public class SubCategoryAdminController {

    @Autowired private SubCategoryService subCategoryService;
    @Autowired private AdminActivityLogRepository adminActivityLogRepository;

    private static boolean isAdmin(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute("isAdmin"));
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin access required."));
    }

    private static ResponseEntity<Map<String, Object>> busy(SubCategoryService.BusyException e) {
        return ResponseEntity.status(409).body(Map.of("success", false, "message", e.getMessage()));
    }

    /** GET /api/admin/subcategories/health: per main category, how many sub-labels exist and how many are plain duplicates. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ResponseEntity.ok(Map.of("success", true, "categories", subCategoryService.health()));
    }

    /** POST /api/admin/subcategories/preview { "category": optional, "useAi": true } */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String category = body == null || body.get("category") == null ? null : String.valueOf(body.get("category"));
        boolean useAi = body == null || !Boolean.FALSE.equals(body.get("useAi"));
        try {
            SubCategoryService.PreviewReport report = subCategoryService.preview(category, useAi);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("report", report);
            return ResponseEntity.ok(out);
        } catch (SubCategoryService.BusyException e) {
            return busy(e);
        }
    }

    /** POST /api/admin/subcategories/apply { "merges": [ { "category": "...", "from": "...", "to": "..." } ] } */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        List<SubCategoryService.Approved> approved = new java.util.ArrayList<>();
        Object raw = body == null ? null : body.get("merges");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    approved.add(new SubCategoryService.Approved(str(m.get("category")), str(m.get("from")), str(m.get("to"))));
                }
            }
        }
        if (approved.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No merges to apply."));
        }
        if (approved.size() > 500) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Too many merges in one request (max 500)."));
        }

        try {
            SubCategoryService.ApplyResult result = subCategoryService.apply(approved);
            if (result.merges() > 0) {
                String by = (String) session.getAttribute("loggedInUserEmail");
                String message = "Merged " + result.merges() + " sub-categor" + (result.merges() == 1 ? "y" : "ies")
                        + " (" + result.materialsChanged() + " handouts re-tagged)";
                String detail = approved.stream().limit(10)
                        .map(a -> a.category() + ": " + a.from() + " -> " + a.to())
                        .collect(java.util.stream.Collectors.joining("; "));
                adminActivityLogRepository.save(new AdminActivityLog("SUBCATEGORY_MERGE", message,
                        detail.length() > 1000 ? detail.substring(0, 1000) : detail, by));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("result", result);
            return ResponseEntity.ok(out);
        } catch (SubCategoryService.BusyException e) {
            return busy(e);
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
