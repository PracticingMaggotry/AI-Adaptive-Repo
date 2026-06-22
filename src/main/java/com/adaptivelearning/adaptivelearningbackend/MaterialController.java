package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final Path uploadDir = Paths.get("uploads", "materials");
    private final Path diagramDir = Paths.get("uploads", "materials", "diagrams");
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private MaterialRepository materialRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private ClaudeService claudeService;

    // ── Upload ────────────────────────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadMaterial(
            @RequestParam String topic,
            @RequestParam("file") MultipartFile file,
            HttpSession session) throws IOException {

        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in before uploading materials."));
        if (topic == null || topic.isBlank())
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Please enter a topic name."));
        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Please choose a file to upload."));

        // Allowlist check — extension AND declared content type must both be
        // in the safe set. Rejecting before writing to disk means a malicious
        // .html or .js file is never stored where the static /uploads/** handler
        // could serve it back as same-origin content and enable stored XSS.
        String originalFilename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        String declaredType = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        boolean allowedExtension = originalFilename.endsWith(".pdf")
                || originalFilename.endsWith(".txt")
                || originalFilename.endsWith(".csv")
                || originalFilename.endsWith(".doc")
                || originalFilename.endsWith(".docx");
        boolean allowedContentType = declaredType.contains("pdf")
                || declaredType.contains("text")
                || declaredType.contains("csv")
                || declaredType.contains("msword")
                || declaredType.contains("wordprocessingml")
                || declaredType.contains("octet-stream"); // browsers sometimes send this for .docx
        if (!allowedExtension || !allowedContentType) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Unsupported file type. Please upload a PDF, TXT, CSV, DOC, or DOCX file."));
        }

        Files.createDirectories(uploadDir);
        String safeOriginalName = Optional.ofNullable(file.getOriginalFilename())
                .orElse("material.txt").replaceAll("[^a-zA-Z0-9._() -]", "_");
        String storedName = System.currentTimeMillis() + "_" + safeOriginalName.replaceAll("\\s+", "_");
        Path storedPath = uploadDir.resolve(storedName);
        Files.copy(file.getInputStream(), storedPath, StandardCopyOption.REPLACE_EXISTING);

        String cleanedTopic = toTitleCase(topic.trim());

        // Replace any prior material this student already has for this exact
        // topic name, instead of letting uploads silently pile up as separate
        // rows. Previously every upload just inserted a new Material row, so
        // re-uploading the same topic left N duplicate cards in the materials
        // list while clearQuestionsForTopic()/clearKnowledgeForTopic() below
        // had already wiped out the previous upload's quiz content — meaning
        // old cards stuck around showing stale summaries for content that no
        // longer had any matching questions. One topic name now always maps
        // to exactly one Material row, so the summary shown always matches
        // the live question set, and the materials list never shows
        // duplicate-looking cards for the same topic.
        replaceExistingMaterialsForTopic(email, cleanedTopic);

        String extractedText = extractText(file, storedPath);
        System.out.println("Extracted text length: " + extractedText.length());
        System.out.println("Extracted text preview: " + extractedText.substring(0, Math.min(200, extractedText.length())));
        String preview = extractedText.isBlank() ? "No readable text extracted." : shorten(extractedText, 1800);

        // Extract the most relevant embedded figure/diagram image (if any) so
        // DIAGRAM-type quiz questions can be grounded in the real artwork
        // instead of an AI-invented guess based on nearby caption text.
        String diagramImageFilename = extractDiagramImage(file, storedPath, extractedText);

        // Save material record
        Material material = new Material(cleanedTopic, safeOriginalName, storedName,
                file.getContentType(), file.getSize(), email, preview);
        material.setDiagramImageFilename(diagramImageFilename);
        materialRepository.save(material);

        // Generate topic summary — based on the ACTUAL extracted handout text,
        // not just the topic name (previously this called describeTopicBriefly
        // which never saw the material content at all).
        String topicSummary = claudeService.summariseMaterialContent(cleanedTopic, extractedText);
        material.setTopicSummary(topicSummary);

        // Auto-categorize into one of the fixed MATERIAL_CATEGORIES super-categories
        // (see ClaudeService.categorizeMaterial for the rationale on why this is a
        // closed list rather than an ad hoc/free-form category per upload).
        applyCategorization(material, extractedText);

        materialRepository.save(material);

        // Clear old questions for this topic
        clearQuestionsForTopic(email, cleanedTopic);

        // Generate questions with Claude
        int generatedCount = 0;
        if (!extractedText.isBlank()) {
            generatedCount = generateQuestionsWithClaude(email, cleanedTopic, extractedText, "Easy");
        }
        generatedCount += appendDiagramQuestionIfEligible(email, material, cleanedTopic, "Easy", "Easy");

        String message = generatedCount > 0
                ? "Material uploaded. Generated " + generatedCount + " AI quiz questions for " + cleanedTopic + "."
                : "Material uploaded, but no readable text could be extracted. Try a text-based PDF or TXT file.";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("material", materialToMap(material));
        response.put("generatedQuestions", generatedCount);
        response.put("quizUrl", "/quizpage.html?topic=" + URLEncoder.encode(cleanedTopic, StandardCharsets.UTF_8) + "&difficulty=Easy");
        return ResponseEntity.ok(response);
    }

    // ── List materials ────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> listMaterials(HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank())
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in first."));

        List<Map<String, Object>> items = materialRepository
                .findByUploadedByOrderByUploadedAtDesc(email)
                .stream().map(this::materialToMap).toList();
        return ResponseEntity.ok(Map.of("success", true, "materials", items));
    }

    // ── Auto-categorization ─────────────────────────────────────────────────

    /**
     * Calls ClaudeService.categorizeMaterial(), parses the JSON result, and
     * sets material.primaryCategory / material.subCategory. Validates the
     * returned category against the fixed MATERIAL_CATEGORIES list — if
     * Claude returns something outside the list (or the call/parse fails),
     * falls back to "General / Other" rather than storing an uncontrolled
     * free-form value, which would defeat the point of using a closed list.
     */
    private void applyCategorization(Material material, String extractedText) {
        String fallbackCategory = "General / Other";
        try {
            String raw = claudeService.categorizeMaterial(extractedText);
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = mapper.readTree(raw);

            String category = node.path("category").asText("").trim();
            String subLabel = node.path("subLabel").asText("").trim();

            boolean valid = ClaudeService.MATERIAL_CATEGORIES.stream()
                    .anyMatch(c -> c.equalsIgnoreCase(category));

            material.setPrimaryCategory(valid ? category : fallbackCategory);
            material.setSubCategory(subLabel.isBlank() ? null : subLabel);

            if (!valid) {
                System.out.println("Categorization returned unrecognized category \"" + category
                        + "\" — defaulted to \"" + fallbackCategory + "\".");
            }
        } catch (Exception e) {
            System.err.println("Material categorization failed (non-fatal): " + e.getMessage());
            material.setPrimaryCategory(fallbackCategory);
            material.setSubCategory(null);
        }
    }

    // ── Claude question generation ─────────────────────────────────────────

    int generateQuestionsWithClaude(String ownerId, String topic, String text, String difficulty) {
        try {
            String raw = claudeService.generateMixedQuestions(topic, text, difficulty);
            System.out.println("=== CLAUDE RAW RESPONSE START ===");
            System.out.println(raw);
            System.out.println("=== CLAUDE RAW RESPONSE END ===");
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();

            JsonNode array = mapper.readTree(raw);
            if (!array.isArray()) {
                System.err.println("Claude did not return a JSON array for topic: " + topic);
                return 0;
            }

            QuestionParser.ParseResult parsed = QuestionParser.parse(
                    array, text, ownerId, topic, difficulty, "DROPPED question: ");

            questionRepository.saveAll(parsed.questions);
            System.out.println("Generated " + parsed.questions.size() + " mixed questions for topic: " + topic);
            return parsed.questions.size();

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("QUESTION GEN FAILED: " + e.getMessage());
            return 0;
        }
    }

    // ── Text extraction ───────────────────────────────────────────────────

    private String extractText(MultipartFile file, Path storedPath) {
        try {
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
            String type = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);

            if (name.endsWith(".txt") || name.endsWith(".csv") || type.contains("text")) {
                return Files.readString(storedPath, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            } else if (name.endsWith(".pdf") || type.contains("pdf")) {
                return extractPdfText(storedPath);
            } else if (name.endsWith(".docx")) {
                return extractDocxText(storedPath);
            } else if (name.endsWith(".doc")) {
                return extractDocText(storedPath);
            }
            return "";
        } catch (Exception e) {
            System.err.println("Text extraction failed: " + e.getMessage());
            return "";
        }
    }

    /** Modern Office Open XML format (.docx) via XWPFDocument. */
    private String extractDocxText(Path storedPath) {
        try (InputStream is = Files.newInputStream(storedPath);
             XWPFDocument document = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            return text == null ? "" : text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            System.out.println("DOCX extraction error: " + e.getMessage());
            return "";
        }
    }

    /** PDF text extraction via PDFBox. */
    private String extractPdfText(Path storedPath) {
        try (PDDocument doc = PDDocument.load(storedPath.toFile())) {
            doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            System.out.println("PDF extraction error: " + e.getMessage());
            return "";
        }
    }

    /** Legacy binary Word format (.doc) via HWPFDocument — separate API from .docx. */
    private String extractDocText(Path storedPath) {
        try (InputStream is = Files.newInputStream(storedPath);
             HWPFDocument document = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(document)) {
            String text = String.join(" ", extractor.getParagraphText());
            return text.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            System.out.println("DOC extraction error: " + e.getMessage());
            return "";
        }
    }

    // ── Diagram image extraction ────────────────────────────────────────
    //
    // Pulls embedded raster images out of the PDF so DIAGRAM-type quiz
    // questions can be generated from what is ACTUALLY drawn, instead of
    // an AI guess based on nearby caption text (which was the root cause
    // of unreliable / fabricated diagram labels).
    //
    // Heuristic: a handout typically embeds a handful of images. We keep
    // the single LARGEST one (by pixel area) on the theory that small
    // images tend to be logos/icons/bullets, while a genuine architecture
    // figure is comparatively large. This is intentionally simple — good
    // enough to reliably grab "Figure 1 / Figure 2"-style diagrams without
    // requiring layout analysis.

    private String extractDiagramImage(MultipartFile file, Path storedPath, String extractedText) {
        try {
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
            String type = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".pdf") || type.contains("pdf"))) {
                return null; // only PDFs carry embedded images we can extract this way
            }

            byte[] bytes = Files.readAllBytes(storedPath);
            BufferedImage best = null;
            long bestArea = 0;

            try (PDDocument document = PDDocument.load(bytes)) {
                document.setAllSecurityToBeRemoved(true);

                for (PDPage page : document.getPages()) {
                    for (COSName name2 : page.getResources().getXObjectNames()) {
                        PDXObject xobject = page.getResources().getXObject(name2);
                        if (xobject instanceof PDImageXObject imageXObject) {
                            BufferedImage img = imageXObject.getImage();
                            long area = (long) img.getWidth() * img.getHeight();
                            // Skip tiny images (icons, bullets, decorative dividers)
                            if (area < 8000) continue;
                            if (area > bestArea) {
                                bestArea = area;
                                best = img;
                            }
                        }
                    }
                }
            }

            if (best == null) {
                System.out.println("No embedded figure-sized image found in PDF.");
                return null;
            }

            Files.createDirectories(diagramDir);
            String diagramFilename = System.currentTimeMillis() + "_diagram.png";
            Path diagramPath = diagramDir.resolve(diagramFilename);
            ImageIO.write(best, "png", diagramPath.toFile());
            System.out.println("Extracted diagram image (" + best.getWidth() + "x" + best.getHeight()
                    + ") -> " + diagramFilename);
            return diagramFilename;

        } catch (Exception e) {
            System.out.println("Diagram image extraction failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads a previously-extracted diagram image off disk and returns it as
     * base64, for sending to Claude's vision API. Returns null if the file
     * is missing or unreadable.
     */
    public String readDiagramImageBase64(String diagramImageFilename) {
        if (diagramImageFilename == null || diagramImageFilename.isBlank()) return null;
        try {
            Path path = diagramDir.resolve(diagramImageFilename);
            byte[] bytes = Files.readAllBytes(path);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            System.out.println("Could not read diagram image: " + e.getMessage());
            return null;
        }
    }

    /**
     * Appends one real, image-grounded DIAGRAM question for this material —
     * but ONLY when tier is "Hard" (DIAGRAM is reserved for the hardest
     * difficulty state) AND the material actually has an extracted diagram
     * image. Mirrors the vision flow used by the Test All Types debug
     * endpoint, but wired into the real generation paths and gated properly.
     *
     * @param tier                  gate condition — must be "Hard" or this no-ops
     * @param savedDifficultyLabel  difficulty value stored on the saved Question
     *                              (usually same as tier, except Targeted Quiz,
     *                              which wants "Targeted" stored even though the
     *                              gate is based on a Hard-equivalent avgScore)
     * @return 1 if a diagram question was generated and saved, 0 otherwise
     */
    int appendDiagramQuestionIfEligible(String ownerId, Material material, String topic, String tier, String savedDifficultyLabel) {
        if (tier == null || !tier.equalsIgnoreCase("Hard")) return 0;
        if (material == null || material.getDiagramImageFilename() == null) return 0;

        String imageBase64 = readDiagramImageBase64(material.getDiagramImageFilename());
        if (imageBase64 == null) return 0;

        try {
            String diagramRaw = claudeService.generateDiagramQuestion(topic, imageBase64);
            diagramRaw = diagramRaw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode dNode = mapper.readTree(diagramRaw);

            JsonNode labels = dNode.path("labels");
            if (!labels.isArray() || labels.size() == 0) {
                System.out.println("Diagram question generation returned no labels — skipping.");
                return 0;
            }

            Question dq = new Question();
            dq.setOwnerId(ownerId);
            dq.setTopic(topic);
            dq.setDifficulty(savedDifficultyLabel);
            dq.setType("DIAGRAM");
            dq.setQuestionText(dNode.path("questionText").asText("Label the parts of the diagram."));
            dq.setHint(dNode.path("hint").asText(""));
            dq.setExplanation(dNode.path("explanation").asText(""));

            tools.jackson.databind.node.ObjectNode payloadNode = mapper.createObjectNode();
            payloadNode.set("labels", labels);
            payloadNode.put("imageFilename", material.getDiagramImageFilename());
            payloadNode.put("mode", "typed");
            dq.setPayload(payloadNode.toString());

            questionRepository.save(dq);
            System.out.println("Appended vision-grounded DIAGRAM question for topic: " + topic);
            return 1;
        } catch (Exception e) {
            System.err.println("Diagram vision question generation failed (non-fatal): " + e.getMessage());
            return 0;
        }
    }

    // ── Cleanup helpers ───────────────────────────────────────────────────

    /**
     * Deletes any existing Material row(s) this student already has for the
     * given topic name, along with their on-disk handout file and extracted
     * diagram image (if any), so re-uploading a topic doesn't leave orphan
     * files behind on top of the orphan DB rows the duplicate-row bug used
     * to create.
     */
    private void replaceExistingMaterialsForTopic(String email, String cleanedTopic) {
        List<Material> existing = materialRepository.findByUploadedByAndTopicIgnoreCase(email, cleanedTopic);
        if (existing.isEmpty()) return;

        for (Material old : existing) {
            try {
                if (old.getStoredFilename() != null) {
                    Files.deleteIfExists(uploadDir.resolve(old.getStoredFilename()));
                }
                if (old.getDiagramImageFilename() != null) {
                    Files.deleteIfExists(diagramDir.resolve(old.getDiagramImageFilename()));
                }
            } catch (Exception e) {
                // Non-fatal — a stray file on disk is far less harmful than
                // blocking the new upload over cleanup of the old one.
                System.out.println("Could not remove old material file (non-fatal): " + e.getMessage());
            }
        }
        materialRepository.deleteAll(existing);
        System.out.println("Replaced " + existing.size() + " prior material(s) for topic: " + cleanedTopic);
    }

    private void clearQuestionsForTopic(String ownerId, String topic) {
        questionRepository.deleteByOwnerAndTopicIgnoreCase(ownerId, topic);
        System.out.println("Cleared questions for topic: " + topic);
    }

    // ── Map helper ────────────────────────────────────────────────────────

    private Map<String, Object> materialToMap(Material material) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", material.getId());
        item.put("topic", material.getTopic());
        item.put("filename", material.getOriginalFilename());
        item.put("contentType", material.getContentType());
        item.put("sizeBytes", material.getSizeBytes());
        item.put("topicSummary", material.getTopicSummary() != null ? material.getTopicSummary() : "Summary not yet generated.");
        item.put("primaryCategory", material.getPrimaryCategory() != null ? material.getPrimaryCategory() : "General / Other");
        item.put("subCategory", material.getSubCategory());
        item.put("uploadedAt", material.getUploadedAt() == null ? "" :
                material.getUploadedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        item.put("quizUrl", "/quizpage.html?topic=" +
                URLEncoder.encode(material.getTopic(), StandardCharsets.UTF_8) + "&difficulty=Easy");
        return item;
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    /**
     * Title-cases a topic name word-by-word (e.g. "data structures" -> "Data
     * Structures", "OBJECT ORIENTED PROGRAMMING" -> "Object Oriented
     * Programming"). The previous implementation only capitalized the very
     * first character of the whole string and lowercased everything after
     * it, which mangled every word after the first one in any multi-word
     * topic name (e.g. "Data Structures" became "Data structures").
     */
    private String toTitleCase(String input) {
        if (input == null || input.isBlank()) return input;
        String[] words = input.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(" ");
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }
}