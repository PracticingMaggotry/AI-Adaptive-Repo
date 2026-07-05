package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletResponse;
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
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    // uploadDir / diagramDir fields removed — all file I/O now goes through
    // FileStorageService, which writes to and reads from Cloudflare R2.
    // Keys follow the same logical path they used to have on disk:
    //   Handout files:  "materials/{storedFilename}"   → FileStorageService.handoutKey()
    //   Diagram images: "materials/diagrams/{name}"    → FileStorageService.diagramKey()

    private final ObjectMapper mapper = new ObjectMapper();
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();
    private static final int MAX_TOPIC_LENGTH = 100;
    private static final int MAX_UPLOADS_PER_DAY = 4;

    @Autowired private MaterialRepository materialRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private ClaudeService claudeService;
    @Autowired private DailyActionLimiter dailyActionLimiter;
    @Autowired private FileStorageService fileStorageService;
    // Shared-content dedup: hashes extracted text, reuses another student's
    // already-processed R2 bytes / knowledge extract / summary / category
    // when the content is byte-for-byte identical, and refcounts deletion.
    // See MaterialContent / MaterialContentService for full rationale.
    // Quiz questions and Learning Hub lesson content are NEVER touched by
    // this — those stay fully personalized per student, generated fresh
    // from the (possibly shared) extracted text every upload.
    @Autowired private MaterialContentService materialContentService;

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
        if (topic.trim().length() > MAX_TOPIC_LENGTH)
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Topic name is too long. Please use " + MAX_TOPIC_LENGTH + " characters or fewer."));
        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Please choose a file to upload."));

        // Allowlist check — runs before quota so a bad file never costs a slot.
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
                || declaredType.contains("octet-stream");
        if (!allowedExtension || !allowedContentType) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Unsupported file type. Please upload a PDF, TXT, CSV, DOC, or DOCX file."));
        }

        final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "File is too large. Maximum upload size is 10 MB."));
        }

        // Read file bytes once — used for text extraction, diagram extraction,
        // and (on a cache miss below) the R2 upload.
        byte[] fileBytes = file.getBytes();

        String safeOriginalName = Optional.ofNullable(file.getOriginalFilename())
                .orElse("material.txt").replaceAll("[^a-zA-Z0-9._() -]", "_");

        String cleanedTopic = toTitleCase(topic.trim());

        // Text extraction runs directly on the in-memory bytes and happens
        // BEFORE the daily quota check AND before any R2/Claude work — it's
        // cheap, local work on bytes already in memory, and its result tells
        // us up front whether this upload can possibly produce anything at
        // all. A scanned image-only PDF, a corrupted file, or an unsupported
        // layout always yields zero questions regardless of quota, so it
        // must never be allowed to burn one of the student's
        // MAX_UPLOADS_PER_DAY slots. Previously the quota was consumed
        // BEFORE this extraction ran, so a blank-extraction upload silently
        // cost a slot for a material that generated zero questions.
        String extractedText = DocumentTextExtractor.extractText(
                file.getOriginalFilename(), file.getContentType(), fileBytes);
        System.out.println("Extracted text length: " + extractedText.length());
        if (!extractedText.isBlank()) {
            System.out.println("Extracted text preview: " + extractedText.substring(0, Math.min(200, extractedText.length())));
        }

        if (extractedText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "No readable text could be extracted from this file — it may be a scanned "
                            + "image-only PDF, a corrupted file, or an unsupported layout. This attempt was "
                            + "NOT counted against your daily upload limit. Please try a text-based PDF, "
                            + "DOCX, or TXT file instead."));
        }

        // Quota is only ever consumed now that we know the file will
        // actually produce something to work with.
        if (!dailyActionLimiter.tryConsume("material-upload", email, MAX_UPLOADS_PER_DAY)) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "message", "Daily upload limit reached (" + MAX_UPLOADS_PER_DAY + " per day). Please try again tomorrow."));
        }

        String contentHash = materialContentService.computeContentHash(extractedText);

        // IMPORTANT — ordering matters here.
        //
        // We used to delete the student's prior Material row for this topic
        // (and release its shared content if orphaned) BEFORE looking up
        // whether this exact content already exists in the shared registry.
        // That was a bug: re-uploading byte-identical content to the same
        // topic means oldHash == contentHash, so deleting the old Material
        // row and immediately calling releaseIfOrphaned(oldHash) wiped out
        // the very MaterialContent row (and its R2 handout/diagram objects)
        // that the dedup lookup below was about to search for — guaranteeing
        // a cache MISS on every same-content re-upload, i.e. a full R2
        // re-upload plus three redundant Claude calls for content that
        // existed a moment earlier.
        //
        // Fix: capture the old Material row(s) now, but don't delete/release
        // them until AFTER the new Material row has been built and saved
        // below. By the time releaseIfOrphaned(oldHash) runs,
        // materialRepository.existsByContentHash(oldHash) correctly returns
        // true (via the new row this upload just saved) whenever
        // oldHash == contentHash, so the shared content survives exactly
        // when it should — while a genuinely different replacement upload
        // (oldHash != contentHash) still gets released as before.
        List<Material> priorMaterials = materialRepository.findByUploadedByAndTopicIgnoreCase(email, cleanedTopic);

        Optional<MaterialContent> existingContent = materialContentService.findByHash(contentHash);

        Material material;
        String preview;

        if (existingContent.isPresent()) {
            // ── DEDUP HIT ────────────────────────────────────────────────
            // Another student already uploaded byte-identical content (same
            // extracted text). Reuse their R2 file, diagram image, and
            // Claude-generated knowledge extract / summary / category —
            // skip the R2 upload and all three Claude calls entirely.
            // Quiz questions below are STILL generated fresh for this
            // student — never shared.
            MaterialContent shared = existingContent.get();
            preview = shared.getExtractedPreview();

            material = new Material(cleanedTopic, safeOriginalName, shared.getStoredFilename(),
                    file.getContentType(), file.getSize(), email, preview);
            material.setDiagramImageFilename(shared.getDiagramImageFilename());
            material.setKnowledgeExtract(shared.getKnowledgeExtract());
            material.setTopicSummary(shared.getTopicSummary());
            material.setPrimaryCategory(shared.getPrimaryCategory());
            material.setSubCategory(shared.getSubCategory());
            material.setContentHash(contentHash);
            materialRepository.save(material);

            System.out.println("Matched upload to existing shared content (hash=" + contentHash
                    + ") — skipped R2 upload and Claude knowledge/summary/category calls.");
        } else {
            // ── DEDUP MISS — first time this exact content has been seen ──
            String storedName = System.currentTimeMillis() + "_" + safeOriginalName.replaceAll("\\s+", "_");

            fileStorageService.storeBytes(
                    FileStorageService.handoutKey(storedName),
                    fileBytes,
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream");

            preview = shorten(extractedText, 1800);

            String diagramImageFilename = extractDiagramImage(
                    file.getOriginalFilename(), file.getContentType(), fileBytes);

            material = new Material(cleanedTopic, safeOriginalName, storedName,
                    file.getContentType(), file.getSize(), email, preview);
            material.setDiagramImageFilename(diagramImageFilename);
            material.setContentHash(contentHash);
            materialRepository.save(material);

            // Build knowledge extract
            String knowledgeContext = null;
            try {
                String knowledgeRaw = claudeService.extractKnowledgeRepresentation(extractedText);
                String cleanedKnowledge = knowledgeRaw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
                mapper.readTree(cleanedKnowledge);
                material.setKnowledgeExtract(cleanedKnowledge);
                knowledgeContext = knowledgeContextOrFullText(material, null);
            } catch (Exception e) {
                System.err.println("Knowledge extraction failed (non-fatal): " + e.getMessage());
            }

            String contextForHaiku = (knowledgeContext != null && !knowledgeContext.isBlank())
                    ? knowledgeContext : extractedText;

            String topicSummary = claudeService.summariseMaterialContent(cleanedTopic, contextForHaiku);
            material.setTopicSummary(topicSummary);

            applyCategorization(material, contextForHaiku);
            materialRepository.save(material);

            // Persist the shared registry row so the NEXT identical upload
            // (by any student, any topic name) hits the dedup path above.
            materialContentService.saveSharedContent(
                    contentHash, storedName, file.getContentType(), file.getSize(),
                    material.getDiagramImageFilename(), material.getKnowledgeExtract(),
                    material.getTopicSummary(), material.getPrimaryCategory(),
                    material.getSubCategory(), preview);
        }

        // Now that the new Material row is saved and referencing
        // contentHash, it's safe to remove the student's prior row(s) for
        // this topic and release any content that's now genuinely orphaned.
        // If a prior row's hash equals contentHash (identical re-upload),
        // releaseIfOrphaned's existsByContentHash check will find the row
        // we just saved above and correctly leave the shared content alone.
        replaceExistingMaterials(priorMaterials);

        clearQuestionsForTopic(email, cleanedTopic);

        // Quiz question generation is ALWAYS run per student, regardless of
        // whether the content was deduped — this is the personalized part
        // of the pipeline and must never be shared across students.
        // extractedText is guaranteed non-blank here (the blank case
        // returns early above, before the quota is even consumed), so this
        // always attempts real generation now.
        int generatedCount = generateQuestionsWithClaude(email, cleanedTopic, extractedText, "Easy");
        generatedCount += appendDiagramQuestionIfEligible(email, material, cleanedTopic, "Easy", "Easy");

        String message = generatedCount > 0
                ? "Material uploaded. Generated " + generatedCount + " AI quiz questions for " + cleanedTopic + "."
                : "Material uploaded and text was extracted, but AI question generation did not return any "
                  + "usable questions this time. You can try re-uploading, or use Adapted/Targeted Quiz "
                  + "generation later.";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("material", materialToMap(material));
        response.put("generatedQuestions", generatedCount);
        response.put("quizUrl", "/quizpage.html?topic=" + URLEncoder.encode(cleanedTopic, StandardCharsets.UTF_8) + "&difficulty=Easy");
        return ResponseEntity.ok(response);
    }

    // ── Diagram image proxy ───────────────────────────────────────────────
    //
    // Now that diagram PNGs live in R2 rather than the local filesystem, the
    // old /uploads/materials/diagrams/** static handler in WebConfig can no
    // longer serve them. This endpoint replaces it: quizpage.html's
    // buildDiagram() sets the <img src> to /api/materials/diagram/{filename},
    // and the admin Content Review modal's base64 path goes through
    // AdminController.getMaterialContent() which calls readDiagramImageBytes()
    // on FileStorageService directly — so only quizpage.html needs this proxy.

    @GetMapping("/diagram/{filename}")
    public void serveDiagram(@PathVariable String filename,
                             HttpSession session,
                             HttpServletResponse response) throws IOException {
        // Must be logged in — same gate as every other API endpoint.
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        // Reject any path that looks like a traversal attempt.
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        byte[] bytes = fileStorageService.load(FileStorageService.diagramKey(filename));
        if (bytes == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("image/png");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
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

    // ── Auto-categorization ───────────────────────────────────────────────

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

    // ── Claude question generation ────────────────────────────────────────
    // Always run per student — questions are never shared across students
    // even when the underlying material content is deduped above.

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

            final int MAX_MIXED_QUESTIONS = 30;
            List<Question> questionsToSave = parsed.questions.size() > MAX_MIXED_QUESTIONS
                    ? parsed.questions.subList(0, MAX_MIXED_QUESTIONS)
                    : parsed.questions;

            questionRepository.saveAll(questionsToSave);
            System.out.println("Generated " + questionsToSave.size() + " mixed questions for topic: " + topic);
            return questionsToSave.size();

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("QUESTION GEN FAILED: " + e.getMessage());
            return 0;
        }
    }

    // ── Diagram image extraction ──────────────────────────────────────────
    //
    // Accepts the file bytes directly (already in memory from the upload)
    // rather than reading back from disk. The extracted PNG is written to
    // R2 via FileStorageService instead of the local diagrams/ directory.
    // Only called on a dedup MISS — a dedup HIT reuses the diagram filename
    // already recorded on the shared MaterialContent row.

    private String extractDiagramImage(String originalFilename,
                                       String contentType,
                                       byte[] fileBytes) {
        try {
            String name = Optional.ofNullable(originalFilename).orElse("").toLowerCase(Locale.ROOT);
            String type = Optional.ofNullable(contentType).orElse("").toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".pdf") || type.contains("pdf"))) {
                return null;
            }

            BufferedImage best = null;
            long bestArea = 0;

            try (PDDocument document = PDDocument.load(fileBytes)) {
                document.setAllSecurityToBeRemoved(true);

                for (PDPage page : document.getPages()) {
                    for (COSName name2 : page.getResources().getXObjectNames()) {
                        PDXObject xobject = page.getResources().getXObject(name2);
                        if (xobject instanceof PDImageXObject imageXObject) {
                            BufferedImage img = imageXObject.getImage();
                            long area = (long) img.getWidth() * img.getHeight();
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

            // Encode to PNG bytes in memory and upload to R2
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(best, "png", baos);
            byte[] pngBytes = baos.toByteArray();

            String diagramFilename = System.currentTimeMillis() + "_diagram.png";
            fileStorageService.storeBytes(
                    FileStorageService.diagramKey(diagramFilename), pngBytes, "image/png");
            System.out.println("Extracted diagram image (" + best.getWidth() + "x" + best.getHeight()
                    + ") -> R2/" + FileStorageService.diagramKey(diagramFilename));
            return diagramFilename;

        } catch (Exception e) {
            System.out.println("Diagram image extraction failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads a diagram image from R2 and returns it as base64.
     * Returns null if the file does not exist in R2.
     */
    public String readDiagramImageBase64(String diagramImageFilename) {
        if (diagramImageFilename == null || diagramImageFilename.isBlank()) return null;
        try {
            byte[] bytes = fileStorageService.load(FileStorageService.diagramKey(diagramImageFilename));
            if (bytes == null) return null;
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            System.out.println("Could not read diagram image from R2: " + e.getMessage());
            return null;
        }
    }

    /**
     * Appends one DIAGRAM question when tier is "Hard" and the material has
     * an extracted diagram image in R2. Vision-grounded generation is
     * always run per student (personalized), even when the diagram image
     * itself is a shared/reused file.
     */
    int appendDiagramQuestionIfEligible(String ownerId, Material material, String topic,
                                        String tier, String savedDifficultyLabel) {
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

            ObjectNode payloadNode = mapper.createObjectNode();
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
     * Deletes the existing Material row(s) for this student+topic. The
     * underlying R2 objects (handout file + diagram image) and the shared
     * MaterialContent registry row are NOT deleted directly here anymore —
     * they're only released via MaterialContentService.releaseIfOrphaned
     * once no OTHER student's Material row still references the same
     * contentHash. This is what makes shared content survive one student
     * re-uploading/replacing their own topic while other students still
     * have it attached.
     */
    private void replaceExistingMaterials(List<Material> existing) {
        if (existing.isEmpty()) return;

        List<String> hashes = existing.stream().map(Material::getContentHash).toList();
        materialRepository.deleteAll(existing);
        hashes.forEach(materialContentService::releaseIfOrphaned);

        System.out.println("Replaced " + existing.size() + " prior material(s).");
    }

    private void clearQuestionsForTopic(String ownerId, String topic) {
        questionRepository.deleteByOwnerAndTopicIgnoreCase(ownerId, topic);
        System.out.println("Cleared questions for topic: " + topic);
    }

    // ── Map helper ────────────────────────────────────────────────────────

    // ── Subtopic extraction (from existing knowledgeExtract — no new AI call) ──
    //
    // extractKnowledgeRepresentation() (routed to Haiku, see ClaudeService) already
    // produces a "concepts" array of {term, definition} pairs for every material at
    // upload time, stored in Material.knowledgeExtract. This just re-surfaces the
    // "term" values as a flat subtopic list for the frontend — it does not trigger
    // any additional Claude call.
    private List<String> extractSubtopics(Material material) {
        if (material == null || material.getKnowledgeExtract() == null || material.getKnowledgeExtract().isBlank()) {
            return List.of();
        }
        try {
            String cleaned = material.getKnowledgeExtract()
                    .replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = STATIC_MAPPER.readTree(cleaned);
            JsonNode concepts = node.path("concepts");
            List<String> terms = new ArrayList<>();
            if (concepts.isArray()) {
                for (JsonNode c : concepts) {
                    String term = c.path("term").asText("").trim();
                    if (!term.isBlank()) terms.add(term);
                }
            }
            return terms;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> materialToMap(Material material) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", material.getId());
        item.put("topic", material.getTopic());
        item.put("filename", material.getOriginalFilename());
        item.put("contentType", material.getContentType());
        item.put("sizeBytes", material.getSizeBytes());
        item.put("topicSummary", material.getTopicSummary() != null ? material.getTopicSummary() : "Summary not yet generated.");
        // NEW — flat list of subtopic terms extracted by Haiku at upload time
        // (see extractKnowledgeRepresentation / extractSubtopics above). Replaces
        // the redundant "About this topic" summary blurb in quizhub.html's topic
        // cards with a real count/list of subtopics covered by the material.
        item.put("subtopics", extractSubtopics(material));
        item.put("primaryCategory", material.getPrimaryCategory() != null ? material.getPrimaryCategory() : "General / Other");
        item.put("subCategory", material.getSubCategory());
        item.put("uploadedAt", material.getUploadedAt() == null ? "" :
                material.getUploadedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        item.put("quizUrl", "/quizpage.html?topic=" +
                URLEncoder.encode(material.getTopic(), StandardCharsets.UTF_8) + "&difficulty=Easy");
        return item;
    }

    // ── Knowledge context helpers (unchanged logic, kept here) ────────────

    public static String knowledgeContextForQuiz(Material material, String fullText) {
        String extract = material == null ? null : material.getKnowledgeExtract();
        if (extract == null || extract.isBlank()) return fullText;

        try {
            ObjectMapper m = STATIC_MAPPER;
            String cleaned = extract.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = m.readTree(cleaned);
            StringBuilder sb = new StringBuilder();

            sb.append("=== KNOWLEDGE GUIDE (structural index only — verify all facts against the Handout Text below) ===\n\n");

            JsonNode concepts = node.path("concepts");
            if (concepts.isArray() && concepts.size() > 0) {
                sb.append("Key concepts to focus on:\n");
                for (JsonNode c : concepts) {
                    String term = c.path("term").asText("");
                    if (!term.isBlank()) sb.append("- ").append(term).append("\n");
                }
                sb.append("\n");
            }

            JsonNode objectives = node.path("learningObjectives");
            if (objectives.isArray() && objectives.size() > 0) {
                sb.append("Learning objectives:\n");
                for (JsonNode o : objectives) sb.append("- ").append(o.asText("")).append("\n");
                sb.append("\n");
            }

            sb.append("=== HANDOUT TEXT (authoritative source — all questions must be grounded in this) ===\n\n");
            if (fullText != null && !fullText.isBlank()) {
                sb.append(fullText.length() > 6000 ? fullText.substring(0, 6000) : fullText);
            } else {
                JsonNode excerpts = node.path("keyExcerpts");
                if (excerpts.isArray() && excerpts.size() > 0) {
                    sb.append("Verbatim excerpts from the handout:\n");
                    for (JsonNode e : excerpts) sb.append("\"").append(e.asText("")).append("\"\n");
                }
            }

            String rendered = sb.toString().trim();
            return rendered.isBlank() ? fullText : rendered;
        } catch (Exception e) {
            return fullText;
        }
    }

    public static String knowledgeContextOrFullText(Material material, String fallbackFullText) {
        String extract = material.getKnowledgeExtract();
        if (extract == null || extract.isBlank()) return fallbackFullText;
        try {
            ObjectMapper m = STATIC_MAPPER;
            String cleaned = extract.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = m.readTree(cleaned);
            StringBuilder sb = new StringBuilder();

            String summary = node.path("summary").asText("");
            if (!summary.isBlank()) sb.append("Summary: ").append(summary).append("\n\n");

            JsonNode concepts = node.path("concepts");
            if (concepts.isArray() && concepts.size() > 0) {
                sb.append("Key concepts:\n");
                for (JsonNode c : concepts) {
                    String term = c.path("term").asText("");
                    String def = c.path("definition").asText("");
                    if (!term.isBlank()) sb.append("- ").append(term).append(": ").append(def).append("\n");
                }
                sb.append("\n");
            }

            JsonNode objectives = node.path("learningObjectives");
            if (objectives.isArray() && objectives.size() > 0) {
                sb.append("Learning objectives:\n");
                for (JsonNode o : objectives) sb.append("- ").append(o.asText("")).append("\n");
                sb.append("\n");
            }

            JsonNode relationships = node.path("relationships");
            if (relationships.isArray() && relationships.size() > 0) {
                sb.append("Relationships between concepts:\n");
                for (JsonNode r : relationships) sb.append("- ").append(r.asText("")).append("\n");
                sb.append("\n");
            }

            JsonNode excerpts = node.path("keyExcerpts");
            if (excerpts.isArray() && excerpts.size() > 0) {
                sb.append("Verbatim excerpts from the handout (use exactly, word-for-word, for any fill-in-the-blank question):\n");
                for (JsonNode e : excerpts) sb.append("- \"").append(e.asText("")).append("\"\n");
            }

            String rendered = sb.toString().trim();
            return rendered.isBlank() ? fallbackFullText : rendered;
        } catch (Exception e) {
            return fallbackFullText;
        }
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

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