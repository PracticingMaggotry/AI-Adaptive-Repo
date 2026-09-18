package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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

    // File I/O goes through FileStorageService (R2), not local disk.
    private final ObjectMapper mapper = new ObjectMapper();
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();
    // MAX_TOPIC_LENGTH moved to ConfigurationService
    // MAX_UPLOADS_PER_DAY moved to ConfigurationService

    @Autowired private MaterialRepository materialRepository;
    @Autowired private ConfigurationService configurationService;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private ClaudeService claudeService;
    @Autowired private DailyActionLimiter dailyActionLimiter;
    @Autowired private FileStorageService fileStorageService;
    // Dedup: reuses another student's R2 bytes/AI output for byte-identical content. Questions/lessons stay per-student.
    @Autowired private MaterialContentService materialContentService;
    // Streams real upload/AI-processing stages to the browser via SSE — see UploadProgressService.
    @Autowired private UploadProgressService uploadProgressService;

    // uploadId (client-generated, per attempt) -> the Material row it created, so a client that aborted
    // mid-request (navigated away) can ask us to delete it via /cancel-upload. Entries are removed on
    // both success and cancel; a rare leaked entry (e.g. server crash mid-request) just sits unused —
    // it's never read again without the matching uploadId.
    private static final Map<String, Long> pendingUploads = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Upload ────────────────────────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadMaterial(
            @RequestParam String topic,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String uploadId,
            HttpSession session) throws IOException {

        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            uploadProgressService.complete(uploadId, false, "Please log in before uploading materials.");
            return ResponseEntity.status(401).body(Map.of("success", false, "message", "Please log in before uploading materials."));
        }
        if (topic == null || topic.isBlank()) {
            uploadProgressService.complete(uploadId, false, "Please enter a topic name.");
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Please enter a topic name."));
        }
        if (topic.trim().length() > configurationService.getMaxTopicLength()) {
            uploadProgressService.complete(uploadId, false, "Topic name is too long.");
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Topic name is too long. Please use " + configurationService.getMaxTopicLength() + " characters or fewer."));
        }
        if (file == null || file.isEmpty()) {
            uploadProgressService.complete(uploadId, false, "Please choose a file to upload.");
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Please choose a file to upload."));
        }

        // Allowlist check — runs before quota so a bad file never costs a slot.
        String originalFilename = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        String declaredType = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        
        // Get allowed extensions and content types from config
        String[] allowedExts = configurationService.getAllowedFileExtensions().split(",");
        String[] allowedTypes = configurationService.getAllowedContentTypes().split(",");
        
        boolean allowedExtension = false;
        for (String ext : allowedExts) {
            if (originalFilename.endsWith("." + ext.trim())) {
                allowedExtension = true;
                break;
            }
        }
        
        boolean allowedContentType = false;
        for (String type : allowedTypes) {
            if (declaredType.contains(type.trim())) {
                allowedContentType = true;
                break;
            }
        }
        if (!allowedExtension || !allowedContentType) {
            uploadProgressService.complete(uploadId, false, "Unsupported file type.");
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Unsupported file type. Please upload a PDF, TXT, CSV, DOC, or DOCX file."));
        }

        final long MAX_UPLOAD_BYTES = configurationService.getMaxUploadBytes();
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            uploadProgressService.complete(uploadId, false, "File is too large.");
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

        uploadProgressService.publish(uploadId, "extracting", "Reading your file and extracting text...");

        // Extract before consuming quota — a blank/unreadable file must never cost an upload slot.
        String extractedText = DocumentTextExtractor.extractText(
                file.getOriginalFilename(), file.getContentType(), fileBytes);
        System.out.println("Extracted text length: " + extractedText.length());
        if (!extractedText.isBlank()) {
            System.out.println("Extracted text preview: " + extractedText.substring(0, Math.min(200, extractedText.length())));
        }

        if (extractedText.isBlank()) {
            uploadProgressService.complete(uploadId, false, "No readable text could be extracted from this file.");
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "No readable text could be extracted from this file — it may be a scanned "
                            + "image-only PDF, a corrupted file, or an unsupported layout. This attempt was "
                            + "NOT counted against your daily upload limit. Please try a text-based PDF, "
                            + "DOCX, or TXT file instead."));
        }

        // Consume quota only now that the file has usable content.
        if (!dailyActionLimiter.tryConsume("material-upload", email, configurationService.getMaxUploadsPerDay())) {
            uploadProgressService.complete(uploadId, false, "Daily upload limit reached.");
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "message", "Daily upload limit reached (" + configurationService.getMaxUploadsPerDay() + " per day). Please try again tomorrow."));
        }

        String contentHash = materialContentService.computeContentHash(extractedText);

        // Ordering matters: capture prior rows now but don't delete/release them until AFTER the new
        // row is saved, so a same-content re-upload doesn't release its own shared content before the
        // dedup lookup below can find it.
        List<Material> priorMaterials = materialRepository.findByUploadedByAndTopicIgnoreCase(email, cleanedTopic);

        Optional<MaterialContent> existingContent = materialContentService.findByHash(contentHash);

        Material material;
        String preview;

        if (existingContent.isPresent()) {
            // DEDUP HIT — reuse another student's R2 file/diagram/AI output. Questions are still generated fresh.
            uploadProgressService.publish(uploadId, "reusing_content", "Matched to previously processed content — skipping AI analysis...");
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
            if (uploadId != null) pendingUploads.put(uploadId, material.getId());

            System.out.println("Matched upload to existing shared content (hash=" + contentHash
                    + ") — skipped R2 upload and Claude knowledge/summary/category calls.");
        } else {
            // DEDUP MISS — first time this content has been seen.
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
            uploadProgressService.publish(uploadId, "analyzing", "Claude is analyzing your document's structure and key concepts...");
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

            uploadProgressService.publish(uploadId, "summarizing", "Generating a topic summary...");
            String topicSummary = claudeService.summariseMaterialContent(cleanedTopic, contextForHaiku);
            material.setTopicSummary(topicSummary);

            uploadProgressService.publish(uploadId, "categorizing", "Categorizing the material...");
            applyCategorization(material, contextForHaiku);
            materialRepository.save(material);
            if (uploadId != null) pendingUploads.put(uploadId, material.getId());

            // Persist so the next identical upload (any student) hits the dedup path above.
            materialContentService.saveSharedContent(
                    contentHash, storedName, file.getContentType(), file.getSize(),
                    material.getDiagramImageFilename(), material.getKnowledgeExtract(),
                    material.getTopicSummary(), material.getPrimaryCategory(),
                    material.getSubCategory(), preview);
        }

        // Safe to release prior rows now — the new row is already saved and referencing contentHash.
        replaceExistingMaterials(priorMaterials);

        clearQuestionsForTopic(email, cleanedTopic);

        // Questions are always generated fresh per student, never shared.
        uploadProgressService.publish(uploadId, "generating_questions", "Generating quiz questions from your material...");
        int generatedCount = generateQuestionsWithClaude(email, cleanedTopic, extractedText, "Easy");
        generatedCount += appendDiagramQuestionIfEligible(email, material, cleanedTopic, "Easy", "Easy");

        String message = generatedCount > 0
                ? "Material uploaded. Generated " + generatedCount + " AI quiz questions for " + cleanedTopic + "."
                : "Material uploaded and text was extracted, but AI question generation did not return any "
                  + "usable questions this time. You can try re-uploading, or use Adapted/Targeted Quiz "
                  + "generation later.";

        if (uploadId != null) pendingUploads.remove(uploadId);
        uploadProgressService.complete(uploadId, true, message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", message);
        response.put("material", materialToMap(material));
        response.put("generatedQuestions", generatedCount);
        response.put("quizUrl", "/quizpage.html?topic=" + URLEncoder.encode(cleanedTopic, StandardCharsets.UTF_8) + "&difficulty=Easy");
        return ResponseEntity.ok(response);
    }

    /**
     * Opens the SSE stream for one upload attempt. The browser must call this (with the same
     * client-generated uploadId it will send as a form field) BEFORE POSTing to /upload, so the
     * connection is already listening when uploadMaterial() starts publishing stage events.
     */
    @GetMapping(value = "/upload-stream/{uploadId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUploadProgress(@PathVariable String uploadId) {
        return uploadProgressService.subscribe(uploadId);
    }

    /**
     * Called (via navigator.sendBeacon) when the client aborts an in-flight upload by navigating away.
     * Deletes only the exact Material row this specific attempt created — identified by the client-
     * generated uploadId — plus the fresh questions generated for it. Never touches any other Material
     * or question row for this topic, so a genuinely unrelated prior upload for the same topic name is
     * left alone. A no-op if the server hadn't reached the save point yet, or the upload already
     * finished normally (its entry would already be gone).
     */
    @PostMapping("/cancel-upload")
    public ResponseEntity<Void> cancelUpload(@RequestBody(required = false) Map<String, String> body, HttpSession session) {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank() || body == null) return ResponseEntity.ok().build();

        String uploadId = body.get("uploadId");
        if (uploadId == null) return ResponseEntity.ok().build();

        Long materialId = pendingUploads.remove(uploadId);
        if (materialId != null) {
            materialRepository.findById(materialId).ifPresent(m -> {
                if (email.equals(m.getUploadedBy())) {
                    replaceExistingMaterials(List.of(m));       // deletes the row + releases R2 content if orphaned
                    clearQuestionsForTopic(email, m.getTopic()); // remove any questions this attempt generated
                }
            });
        }
        return ResponseEntity.ok().build();
    }

    // ── Diagram image proxy — serves R2-stored diagrams (replaces the old static file handler) ──
    @GetMapping("/diagram/{filename}")
    public void serveDiagram(@PathVariable String filename,
                             HttpSession session,
                             HttpServletResponse response) throws IOException {
        String email = (String) session.getAttribute("loggedInUserEmail");
        if (email == null || email.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        // Reject path traversal.
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

    // ── Auto-categorization ──
    private void applyCategorization(Material material, String extractedText) {
        String fallbackCategory = "General / Other";
        try {
            String raw = claudeService.categorizeMaterial(extractedText);
            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = mapper.readTree(raw);

            String category = node.path("category").asText("").trim();
            String subLabel = node.path("subLabel").asText("").trim();

            boolean valid = claudeService.getMaterialCategories().stream()
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

    // ── Claude question generation — always per student, never shared ──
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

    // ── Diagram image extraction — only on a dedup MISS; writes PNG to R2 ──
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

    /** Reads a diagram image from R2 as base64, or null if missing. */
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

    /** Appends one DIAGRAM question when tier is "Hard" and a diagram image exists. Always generated fresh per student. */
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

    // ── Cleanup helpers ──

    /** Deletes the student's Material rows; underlying R2/shared content is only released once orphaned. */
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

    // ── Subtopic extraction — re-surfaces "term" values already in Material.knowledgeExtract; no new AI call ──
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
        item.put("subtopics", extractSubtopics(material));
        item.put("primaryCategory", material.getPrimaryCategory() != null ? material.getPrimaryCategory() : "General / Other");
        item.put("subCategory", material.getSubCategory());
        item.put("uploadedAt", material.getUploadedAt() == null ? "" :
                material.getUploadedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        item.put("quizUrl", "/quizpage.html?topic=" +
                URLEncoder.encode(material.getTopic(), StandardCharsets.UTF_8) + "&difficulty=Easy");
        return item;
    }

    // ── Knowledge context helpers ──
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