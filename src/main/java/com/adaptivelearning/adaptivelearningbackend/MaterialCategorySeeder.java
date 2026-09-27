package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures the fixed set of main-topic {@link MaterialCategory} rows always exists in the
 * database, independent of whether any {@link Material} has actually been tagged with them yet.
 *
 * Previously the category list only contained whatever an admin had manually added through the
 * Topics & Content → Categories admin screen, so on a fresh database (or one where categories
 * were deleted) {@link ClaudeService#getMaterialCategories()} returned an empty list — even
 * though "General / Other" is hard-coded as the fallback category in several places
 * (see AdminController.materialCategories, MaterialController.applyCategorization). That made the
 * AI classifier's category list, the Overview "Material Categorization" breakdown, and this admin
 * CRUD screen all start out empty until someone happened to add categories by hand.
 *
 * This runs once on startup and inserts any of the default categories below that don't already
 * exist (matched case-insensitively by name), so the "main topics" are always present and
 * editable here, not conjured into existence only when a material happens to use them. It never
 * touches categories that already exist, so admin edits/renames/retirements are never overwritten.
 */
@Component
public class MaterialCategorySeeder implements CommandLineRunner {

    /** Default fixed main-topic categories. Admins can rename, retire, or delete these afterwards. */
    private static final List<MaterialCategory> DEFAULT_CATEGORIES = List.of(
            new MaterialCategory("Mathematics", "Algebra, calculus, statistics, geometry, and other quantitative topics."),
            new MaterialCategory("Computer Science", "Programming, data structures, algorithms, and software concepts."),
            new MaterialCategory("Natural Sciences", "Biology, chemistry, physics, and earth/environmental science."),
            new MaterialCategory("Engineering", "Applied engineering disciplines and technical design coursework."),
            new MaterialCategory("Social Sciences", "Psychology, sociology, economics, political science, and related fields."),
            new MaterialCategory("Humanities", "History, philosophy, literature, and cultural studies."),
            new MaterialCategory("Language & Communication", "Language learning, writing, linguistics, and communication skills."),
            new MaterialCategory("Business & Law", "Business administration, finance, accounting, and legal studies."),
            new MaterialCategory("Health & Medicine", "Anatomy, nursing, medicine, and other health science topics."),
            new MaterialCategory("Arts", "Visual arts, music, design, and performing arts."),
            new MaterialCategory("General / Other", "Fallback category for material that doesn't fit any other subject.")
    );

    private final MaterialCategoryRepository categoryRepository;

    public MaterialCategorySeeder(MaterialCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(String... args) {
        for (MaterialCategory defaultCategory : DEFAULT_CATEGORIES) {
            if (categoryRepository.findByNameIgnoreCase(defaultCategory.getName()).isEmpty()) {
                categoryRepository.save(defaultCategory);
            }
        }
    }
}