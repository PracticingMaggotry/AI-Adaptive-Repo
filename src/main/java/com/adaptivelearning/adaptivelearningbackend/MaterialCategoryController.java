package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin-only CRUD for the fixed list of subject categories ClaudeService/AdminController use to
 * auto-classify uploaded material (see MaterialController.applyCategorization).
 *
 * SECURITY: moved under /api/** (was bare /admin/categories) so it's covered by AuthInterceptor's
 * ban/suspension enforcement and the CsrfInterceptor's token check like every other admin
 * endpoint. Neither of those actually restricts this to admins though — AuthInterceptor only
 * gates *.html pages by role, and every /api/** call is "authorised by each controller's own
 * session check" (see its class javadoc). The bare /admin/categories path previously matched
 * neither isHtmlPage nor isApiCall in AuthInterceptor at all, so it was reachable by anyone who
 * could reach the server, logged in or not. The explicit isAdmin(session) check below — the same
 * pattern AdminController uses on every one of its endpoints — is what actually enforces
 * admin-only access; the path move alone would not have been enough.
 */
@RestController
@RequestMapping("/api/admin/categories")
public class MaterialCategoryController {

    @Autowired
    private MaterialCategoryRepository categoryRepository;

    private boolean isAdmin(HttpSession session) {
        Object flag = session.getAttribute("isAdmin");
        return flag instanceof Boolean && (Boolean) flag;
    }

    private ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin access required."));
    }

    @GetMapping
    public ResponseEntity<Object> listCategories(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ResponseEntity.ok(categoryRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<Object> createCategory(@RequestBody MaterialCategory category, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        if (category.getName() == null || category.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Category name is required."));
        }
        // Check for duplicates (case-insensitive)
        Optional<MaterialCategory> existing = categoryRepository.findByNameIgnoreCase(category.getName());
        if (existing.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", "A category with that name already exists."));
        }
        MaterialCategory saved = categoryRepository.save(category);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Object> updateCategory(@PathVariable Long id, @RequestBody MaterialCategory updated, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Optional<MaterialCategory> optional = categoryRepository.findById(id);
        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MaterialCategory existing = optional.get();

        // Check if new name conflicts with another category (case-insensitive, excluding self)
        if (updated.getName() != null && !updated.getName().isBlank() &&
            !existing.getName().equalsIgnoreCase(updated.getName())) {
            Optional<MaterialCategory> conflict = categoryRepository.findByNameIgnoreCase(updated.getName());
            if (conflict.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("success", false, "message", "A category with that name already exists."));
            }
        }

        if (updated.getName() != null && !updated.getName().isBlank()) {
            existing.setName(updated.getName());
        }
        if (updated.getDescription() != null) {
            existing.setDescription(updated.getDescription());
        }
        if (updated.getActive() != null) {
            existing.setActive(updated.getActive());
        }

        MaterialCategory saved = categoryRepository.save(existing);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deleteCategory(@PathVariable Long id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        Optional<MaterialCategory> optional = categoryRepository.findById(id);
        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        categoryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}