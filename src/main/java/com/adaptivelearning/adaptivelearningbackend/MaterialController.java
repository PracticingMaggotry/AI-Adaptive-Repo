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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final Path uploadDir = Paths.get("uploads", "materials");
    private final Path diagramDir = Paths.get("uploads", "materials", "diagrams");
    private final ObjectMapper mapper = new ObjectMapper();

    // Static counterpart for use inside the two static helper methods
    // (knowledgeContextForQuiz / knowledgeContextOrFullText) that cannot
    // access the instance field. ObjectMapper is thread-safe, so sharing
    // one instance here is safe.
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    // Server-side cap on topic name length. The UI never sends a topic name
    // longer than a short label in normal use, but without a real gate here
    // a request posted directly to /api/materials/upload could carry a
    // topic value of arbitrary size. The Material.topic column has no
    // length annotation, so it defaults to varchar(255) and an oversized
    // value would throw an unhandled DataIntegrityViolationException on
    // save (a 500) instead of a clean, user-facing 400. The same value is
    // also persisted verbatim on every Question/Attempt row for this topic
    // and shown back in the UI as a label, so keeping it short keeps all of
    // that sane regardless of what a client sends.
    private static final int MAX_TOPIC_LENGTH = 100;

    @Autowired private MaterialRepository materialRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private ClaudeService claudeService;
    @Autowired private DailyActionLimiter dailyActionLimiter;

    // Daily per-student caps on expensive AI-backed actions. See
    // DailyActionLimiter for the shared in-memory counting mechanism.
    private static final int MAX_UPLOADS_PER_DAY = 4;

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

        // Allowlist check — extension AND declared content type must both be
        // in the safe set. Rejecting before writing to disk means a malicious
        // .html or .js file is never stored where the static /uploads/** handler
        // could serve it back as same-origin content and enable stored XSS.
        //
        // IMPORTANT: this runs BEFORE the daily upload cap below. These are
        // free, side-effect-free checks against data already in memory — a
        // request that fails them was never going to produce a real upload,
        // so it must not cost the student one of their scarce daily slots.
        // (This used to run AFTER the quota check, which meant accidentally
        // picking the wrong file type, or a file a bit over 10MB, burned a
        // real upload slot on every failed attempt — a student could exhaust
        // their entire daily budget without ever successfully uploading
        // anything.)
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

        // Enforce 10 MB limit server-side. The UI shows the same cap, but
        // the browser check is trivially bypassed — this is the real gate.
        // Checked here, before writing anything to disk, so an oversized
        // file never reaches PDFBox or the filesystem at all. Also kept
        // ahead of the daily quota check for the same reason as the
        // allowlist check above — an oversized file was never going to be
        // accepted, so it shouldn't cost a daily slot either.
        final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024; // 10 MB
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "File is too large. Maximum upload size is 10 MB."));
        }

        // Daily upload cap — each student may upload at most MAX_UPLOADS_PER_DAY
        // materials per calendar day. Checked only now, after the file has
        // passed type/size validation, so a student is never charged a slot
        // for a request that was always going to be rejected — only requests
        // that are actually about to trigger real processing (text
        // extraction, knowledge extraction, Claude calls) below consume the
        // budget.
        if (!dailyActionLimiter.tryConsume("material-upload", email, MAX_UPLOADS_PER_DAY)) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "message", "Daily upload limit reached (" + MAX_UPLOADS_PER_DAY + " per day). Please try again tomorrow."));
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

        String extractedText = DocumentTextExtractor.extractText(file.getOriginalFilename(), file.getContentType(), storedPath);
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

        // ── Step 1: Build the knowledge extract first ────────────────────────
        // The extract (~800 chars of structured JSON) is computed once from the
        // full handout text and then reused by the Haiku calls below (summary,
        // categorization) instead of re-sending the raw text each time. This
        // makes those lightweight calls significantly cheaper: they receive the
        // compact extract rather than up to 4000 chars of raw material.
        // Knowledge extraction itself runs on Haiku (see ClaudeService) so the
        // one-time cost is low and the per-call savings on every subsequent
        // Haiku task more than offset it.
        String knowledgeContext = null; // compact context for Haiku tasks; null until extract succeeds
        try {
            String knowledgeRaw = claudeService.extractKnowledgeRepresentation(extractedText);
            String cleanedKnowledge = knowledgeRaw.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            mapper.readTree(cleanedKnowledge); // validate before caching
            material.setKnowledgeExtract(cleanedKnowledge);
            // Render the extract into the compact plain-text form used by Haiku calls.
            // Falls back to null if parsing fails, in which case the callers below
            // will receive the raw extractedText as their fallback.
            knowledgeContext = knowledgeContextOrFullText(material, null);
        } catch (Exception e) {
            System.err.println("Knowledge extraction failed (non-fatal): " + e.getMessage());
        }

        // ── Step 2: Summary and categorization — use extract when available ──
        // Both are lightweight Haiku calls. When the extract succeeded, they
        // receive ~800 chars of structured knowledge rather than 4000 chars of
        // raw text; when it failed they fall back to the raw text as before.
        String contextForHaiku = (knowledgeContext != null && !knowledgeContext.isBlank())
                ? knowledgeContext : extractedText;

        // Generate topic summary grounded in actual handout content.
        String topicSummary = claudeService.summariseMaterialContent(cleanedTopic, contextForHaiku);
        material.setTopicSummary(topicSummary);

        // Auto-categorize into one of the fixed MATERIAL_CATEGORIES super-categories
        // (see ClaudeService.categorizeMaterial for the rationale on why this is a
        // closed list rather than an ad hoc/free-form category per upload).
        applyCategorization(material, contextForHaiku);

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

            // Hard server-side cap of 10. ClaudeService.generateMixedQuestions()
            // already asks for exactly 10 questions, but that is only a request —
            // nothing previously stopped this method from saving every question
            // Claude returned if it ignored the instruction. Truncate here rather
            // than trusting the model's count.
            final int MAX_MIXED_QUESTIONS = 10;
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

    /**
     * Builds quiz-generation context that combines the structured knowledge
     * extract (as a topic index / key-concept guide) WITH the original full
     * handout text (as the authoritative source). This is intentionally
     * different from knowledgeContextOrFullText, which is used for
     * lightweight tasks (lesson content, summaries) where the distilled
     * extract alone is sufficient.
     *
     * For quiz generation the original handout text is always included so
     * that:
     *   1. Fill-in-the-blank excerpts can be verified verbatim against the
     *      real source (QuestionValidator already enforces this).
     *   2. Claude cannot generate questions about concepts, definitions, or
     *      facts that only exist in the AI-distilled extract and not in the
     *      actual handout — preventing students from being tested on the
     *      AI's own paraphrases instead of their material.
     *   3. The knowledge extract's concepts/objectives serve only as a
     *      structural guide (what to focus on), not as the source of truth.
     *
     * Token budget: extract (~800 chars) + full text (up to 6000 chars) ≈
     * the same budget quiz generators were already using for full-text-only
     * calls, so this does not meaningfully increase API cost.
     */
    public static String knowledgeContextForQuiz(Material material, String fullText) {
        String extract = material == null ? null : material.getKnowledgeExtract();
        // If no extract, fall back to full text only (same as before)
        if (extract == null || extract.isBlank()) return fullText;

        try {
            ObjectMapper m = STATIC_MAPPER;
            String cleaned = extract.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            JsonNode node = m.readTree(cleaned);
            StringBuilder sb = new StringBuilder();

            // Include concepts/objectives as a structural guide — labelled
            // clearly as a guide, not as the source of truth, so Claude
            // knows to verify against the handout text below.
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
                // Cap to 6000 chars — same limit the quiz generators already apply
                sb.append(fullText.length() > 6000 ? fullText.substring(0, 6000) : fullText);
            } else {
                // Full text unavailable (e.g. scanned PDF) — include verbatim
                // excerpts from the extract as the next-best source
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

    /**
     * Renders the cached structured knowledge extract into a compact plain-text
     * context block for reuse in later AI calls, instead of resending the full
     * handout text on every quiz regeneration. Falls back to fallbackFullText
     * if no cached extract exists yet (e.g. legacy materials) or parsing fails.
     *
     * NOTE: Use knowledgeContextForQuiz() instead when the context is for
     * quiz/question generation — that method always includes the original
     * handout text to prevent questions being generated from the AI's own
     * distilled paraphrases rather than the actual material.
     */
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