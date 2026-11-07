package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.adaptivelearning.adaptivelearningbackend.ai.ContentReuse;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared-content dedup + refcounted cleanup for uploaded handouts. Avoids re-uploading to R2 and
 * re-running three Claude calls per student when different students upload byte-identical content.
 * See {@link MaterialContent}. Never touches Questions/LessonCache — those stay per-student.
 */
@Service
public class MaterialContentService {

    @Autowired private MaterialContentRepository materialContentRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private BaselineTemplateService baselineTemplateService;

    /**
     * Stable fingerprint of the extracted text (see {@link ContentReuse#fingerprint}); returns a "unique-&lt;uuid&gt;"
     * sentinel for text too short to fingerprint, which never matches anything.
     */
    public String computeContentHash(String extractedText) {
        String fp = ContentReuse.fingerprint(extractedText);
        return fp != null ? fp : "unique-" + UUID.randomUUID();
    }

    /**
     * Shared content already prepared for this text: the current fingerprint first, then the hash used before
     * normalisation existed, so handouts uploaded earlier still match. The caller must then use the returned row's own
     * hash (not the one it computed), because a student's study scope is keyed by it.
     */
    public Optional<MaterialContent> findForText(String extractedText, String contentHash) {
        Optional<MaterialContent> hit = findByHash(contentHash);
        if (hit.isPresent()) return hit;
        String legacy = ContentReuse.legacyFingerprint(extractedText);
        return legacy == null ? Optional.empty() : findByHash(legacy);
    }

    /** Sentinel "unique-*" hashes are never looked up — they exist specifically to never match. */
    public Optional<MaterialContent> findByHash(String contentHash) {
        if (contentHash == null || contentHash.isBlank() || contentHash.startsWith("unique-")) {
            return Optional.empty();
        }
        return materialContentRepository.findByContentHash(contentHash);
    }

    /**
     * Persists the shared registry row so future identical uploads reuse it via {@link #findByHash}.
     * Returns null (not throw) on a concurrent-insert race for the same hash — the caller's own
     * Material row is unaffected either way.
     */
    public MaterialContent saveSharedContent(String contentHash, String storedFilename, String contentType,
                                             Long sizeBytes, String diagramImageFilename, String knowledgeExtract,
                                             String topicSummary, String primaryCategory, String subCategory,
                                             String extractedPreview,
                                             String extractedText) {
        MaterialContent content = new MaterialContent();
        content.setContentHash(contentHash);
        content.setStoredFilename(storedFilename);
        content.setContentType(contentType);
        content.setSizeBytes(sizeBytes);
        content.setDiagramImageFilename(diagramImageFilename);
        content.setKnowledgeExtract(knowledgeExtract);
        content.setTopicSummary(topicSummary);
        content.setPrimaryCategory(primaryCategory);
        content.setSubCategory(subCategory);
        content.setExtractedPreview(extractedPreview);
        content.setExtractedText(extractedText);
        content.setCreatedAt(LocalDateTime.now());
        try {
            return materialContentRepository.save(content);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            System.out.println("Shared content row for hash=" + contentHash
                    + " was created concurrently by another upload — skipping (non-fatal).");
            return null;
        }
    }

    /** Stores the AI analysis on a row that was saved without one (an earlier analysis failed), so nobody pays for it twice. */
    public void updateAnalysis(String contentHash, String knowledgeExtract, String topicSummary,
                               String primaryCategory, String subCategory) {
        materialContentRepository.findByContentHash(contentHash).ifPresent(c -> {
            c.setKnowledgeExtract(knowledgeExtract);
            c.setTopicSummary(topicSummary);
            c.setPrimaryCategory(primaryCategory);
            c.setSubCategory(subCategory);
            materialContentRepository.save(c);
        });
    }

    /** Fills in the stored text of a row created before the text was kept. */
    public void backfillText(MaterialContent content, String extractedText) {
        if (content == null || extractedText == null || extractedText.isBlank()) return;
        if (content.getExtractedText() != null && !content.getExtractedText().isBlank()) return;
        content.setExtractedText(extractedText);
        materialContentRepository.save(content);
    }

    /**
     * The handout's reference-stripped text. Read from the shared row when it is there; otherwise (a handout uploaded
     * before the text was kept) extracted from the stored file once and remembered. Returns "" when nothing is readable.
     */
    public String textFor(Material material) {
        if (material == null) return "";
        Optional<MaterialContent> row = material.getContentHash() == null || material.getContentHash().isBlank()
                ? Optional.empty() : materialContentRepository.findByContentHash(material.getContentHash());
        if (row.isPresent() && row.get().getExtractedText() != null && !row.get().getExtractedText().isBlank()) {
            return row.get().getExtractedText();
        }
        if (material.getStoredFilename() == null) return "";
        byte[] bytes = fileStorageService.load(FileStorageService.handoutKey(material.getStoredFilename()));
        if (bytes == null) return "";
        String text = DocumentTextExtractor.stripReferences(DocumentTextExtractor.extractText(
                material.getOriginalFilename(), material.getContentType(), bytes));
        if (text == null) return "";
        row.ifPresent(c -> backfillText(c, text));
        return text;
    }

    /**
     * Call after Material rows referencing this hash are deleted. No-op if another Material row
     * still references it. Only once the last reference is gone are the R2 objects and the
     * MaterialContent row removed. Safe with a null/blank hash (pre-dedup rows) — simply skipped.
     */
    public void releaseIfOrphaned(String contentHash) {
        if (contentHash == null || contentHash.isBlank()) return;

        boolean stillReferenced = materialRepository.existsByContentHash(contentHash);
        if (stillReferenced) return;

        materialContentRepository.findByContentHash(contentHash).ifPresent(content -> {
            try {
                if (content.getStoredFilename() != null) {
                    fileStorageService.delete(FileStorageService.handoutKey(content.getStoredFilename()));
                }
                if (content.getDiagramImageFilename() != null) {
                    fileStorageService.delete(FileStorageService.diagramKey(content.getDiagramImageFilename()));
                }
            } catch (Exception e) {
                System.err.println("Could not remove orphaned R2 object(s) for content hash "
                        + contentHash + " (non-fatal): " + e.getMessage());
            }
            materialContentRepository.delete(content);
            baselineTemplateService.dropFor(contentHash);
            System.out.println("Released shared material content (hash=" + contentHash
                    + ") — last referencing student deleted their topic.");
        });
    }
}