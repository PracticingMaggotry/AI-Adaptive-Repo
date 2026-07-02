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
 * Shared-content dedup + refcounted cleanup for uploaded handouts.
 *
 * See {@link MaterialContent} for the full rationale. In short: many
 * students in the same class often upload the exact same official course
 * PDF. Without dedup, that's one full R2 upload + three Claude calls
 * (knowledge extraction, summary, categorization) PER STUDENT for
 * byte-identical content. This service:
 *
 *   1. Fingerprints a material's extracted text into a stable hash
 *      ({@link #computeContentHash}), so MaterialController can look up
 *      whether this exact content has already been processed by anyone.
 *   2. Persists the shared, NON-personalized outputs once
 *      ({@link #saveSharedContent}) so future identical uploads can copy
 *      them instead of regenerating.
 *   3. Refcounts deletion ({@link #releaseIfOrphaned}) so the underlying
 *      R2 file bytes and the MaterialContent row are only actually removed
 *      once EVERY {@link Material} row referencing that hash is gone —
 *      i.e. every student who uploaded it has deleted their topic.
 *
 * Deliberately does NOT touch quiz Questions or LessonCache — those stay
 * exactly as they were: generated fresh per student, keyed by
 * (ownerId/studentId, topic), never shared. Dedup only applies to the
 * upload-time AI calls that would otherwise produce identical output for
 * identical input regardless of who's asking.
 */
@Service
public class MaterialContentService {

    /**
     * Below this length, extracted text is too short/generic to fingerprint
     * reliably (e.g. a failed/near-empty extraction) — treating it as
     * "content" for dedup purposes risks unrelated near-blank uploads
     * colliding on trivial similarity. Below this threshold we always
     * generate a unique, never-matching hash instead.
     */
    private static final int MIN_HASHABLE_LENGTH = 200;

    @Autowired private MaterialContentRepository materialContentRepository;
    @Autowired private MaterialRepository materialRepository;
    @Autowired private FileStorageService fileStorageService;

    /**
     * Computes a stable fingerprint for a material's extracted text.
     * Returns a "unique-&lt;uuid&gt;" sentinel (guaranteed never to match
     * anything) for blank or very short extractions, so two unrelated
     * failed-extraction uploads never get incorrectly merged.
     */
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
            // Should never happen (SHA-256 is always available), but fail
            // safe into "never dedup" rather than breaking the upload.
            System.err.println("Content hashing failed (non-fatal, dedup skipped): " + e.getMessage());
            return "unique-" + UUID.randomUUID();
        }
    }

    /**
     * Looks up existing shared content for a hash. Sentinel "unique-*"
     * hashes are intentionally never looked up — they exist specifically
     * to never match, so an actual DB round-trip would be wasted work.
     */
    public Optional<MaterialContent> findByHash(String contentHash) {
        if (contentHash == null || contentHash.isBlank() || contentHash.startsWith("unique-")) {
            return Optional.empty();
        }
        return materialContentRepository.findByContentHash(contentHash);
    }

    /**
     * Persists the shared, non-personalized outputs of a first-time upload
     * so future identical uploads (by any student) can reuse them via
     * {@link #findByHash}.
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
        return materialContentRepository.save(content);
    }

    /**
     * Call AFTER one or more {@link Material} rows referencing this hash
     * have already been deleted from the DB (see callers in
     * TopicController / AdminController / MaterialController).
     *
     * If any OTHER Material row still references the same contentHash —
     * i.e. another student still has this material attached to one of
     * their topics — this is a no-op: the shared file bytes and AI output
     * stay put. Only once the last referencing Material row is gone are
     * the R2 objects (handout + diagram) deleted and the MaterialContent
     * registry row removed.
     *
     * Safe to call with a null/blank hash (e.g. Material rows that predate
     * this feature) — those are simply skipped, matching the old
     * per-material-only deletion behavior for that edge case.
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