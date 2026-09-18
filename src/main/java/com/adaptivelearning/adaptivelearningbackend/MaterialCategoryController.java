package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/admin/categories")
public class MaterialCategoryController {

    @Autowired
    private MaterialCategoryRepository categoryRepository;

    @GetMapping
    public List<MaterialCategory> listCategories() {
        return categoryRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<MaterialCategory> createCategory(@RequestBody MaterialCategory category) {
        if (category.getName() == null || category.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // Check for duplicates (case-insensitive)
        Optional<MaterialCategory> existing = categoryRepository.findByNameIgnoreCase(category.getName());
        if (existing.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        MaterialCategory saved = categoryRepository.save(category);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaterialCategory> updateCategory(@PathVariable Long id, @RequestBody MaterialCategory updated) {
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
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
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
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        Optional<MaterialCategory> optional = categoryRepository.findById(id);
        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        categoryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}

