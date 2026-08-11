package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared-content dedup + refcounted cleanup for uploaded handouts. Avoids re-uploading to R2 and
 * re-running three Claude calls per student when different students upload byte-identical content.
 * See {@link MaterialContent}. Never touches Questions/LessonCache — those stay per-student.
 */
@Service
public class MaterialContentService {

    /** Below this length, text is too short to fingerprint reliably — a unique never-matching hash is used instead. */
    private static final int MIN_HASHABLE_LENGTH = 200;

    @Autowired private MaterialContentRepository materialContentRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private FileStorageService fileStorageService;

    /** Stable fingerprint of extracted text; returns a "unique-&lt;uuid&gt;" sentinel for blank/short extractions. */
    public String computeContentHash(String extractedText) {
        if (extractedText == null || extractedText.trim().length() < MIN_HASHABLE_LENGTH) {
            return "unique-" + UUID.randomUUID();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(
                    extractedText.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Fail safe into "never dedup" rather than breaking the upload.
            System.err.println("Content hashing failed (non-fatal, dedup skipped): " + e.getMessage());
            return "unique-" + UUID.randomUUID();
        }
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
                                             String extractedPreview) {
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
        content.setCreatedAt(LocalDateTime.now());
        try {
            return materialContentRepository.save(content);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            System.out.println("Shared content row for hash=" + contentHash
                    + " was created concurrently by another upload — skipping (non-fatal).");
            return null;
        }
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
            System.out.println("Released shared material content (hash=" + contentHash
                    + ") — last referencing student deleted their topic.");
        });
    }
}