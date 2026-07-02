package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Canonical, deduplicated registry of uploaded-handout CONTENT — as opposed
 * to {@link Material}, which is a per-student row.
 *
 * WHY THIS EXISTS: materials were previously scoped per-ownerId with zero
 * dedup. If 30 students in the same class upload the identical official
 * course PDF, that used to mean 30 separate R2 uploads and 30 separate
 * Claude calls (knowledge extraction, summary, categorization) on
 * byte-identical content. This table lets identical content be recognised
 * and reused across students, while each student still gets their own
 * {@link Material} row (own topic name, own upload timestamp, own
 * ownership for topic-deletion purposes) and — critically — their own
 * AI-generated quiz questions and Learning Hub lesson content, which are
 * NOT stored here and remain fully personalized per student.
 *
 * One row per distinct {@code contentHash} (SHA-256 of the normalized
 * extracted text — see {@link MaterialContentService#computeContentHash}).
 * Holds only the NON-personalized, expensive-to-regenerate outputs:
 *   - the actual file bytes in R2 (storedFilename)
 *   - the extracted diagram image, if any (diagramImageFilename)
 *   - Claude's knowledge extraction / topic summary / categorization
 *
 * Reference-counted implicitly: a row here is deleted only when
 * {@link MaterialContentService#releaseIfOrphaned} finds zero remaining
 * {@link Material} rows pointing at its contentHash — i.e. only after
 * EVERY student who uploaded this exact content has deleted their topic.
 */
@Entity
@Table(
        name = "material_contents",
        uniqueConstraints = @UniqueConstraint(name = "uq_content_hash", columnNames = "content_hash")
)
public class MaterialContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SHA-256 hex digest of the normalized extracted text, OR — for
     * uploads whose extracted text was too short/blank to fingerprint
     * reliably — a "unique-<uuid>" sentinel that can never match another
     * upload. See {@link MaterialContentService#computeContentHash}.
     */
    @Column(name = "content_hash", nullable = false, unique = true, length = 80)
    private String contentHash;

    /** R2 key suffix (see FileStorageService.handoutKey) — shared across every Material row with this hash. */
    @Column(name = "stored_filename")
    private String storedFilename;

    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "diagram_image_filename")
    private String diagramImageFilename;

    @Column(name = "knowledge_extract", columnDefinition = "TEXT")
    private String knowledgeExtract;

    @Column(name = "topic_summary", length = 500)
    private String topicSummary;

    @Column(name = "primary_category", length = 80)
    private String primaryCategory;

    @Column(name = "sub_category", length = 120)
    private String subCategory;

    @Column(name = "extracted_preview", length = 2000)
    private String extractedPreview;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public MaterialContent() {}

    public Long getId() { return id; }
    public String getContentHash() { return contentHash; }
    public String getStoredFilename() { return storedFilename; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getDiagramImageFilename() { return diagramImageFilename; }
    public String getKnowledgeExtract() { return knowledgeExtract; }
    public String getTopicSummary() { return topicSummary; }
    public String getPrimaryCategory() { return primaryCategory; }
    public String getSubCategory() { return subCategory; }
    public String getExtractedPreview() { return extractedPreview; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public void setDiagramImageFilename(String diagramImageFilename) { this.diagramImageFilename = diagramImageFilename; }
    public void setKnowledgeExtract(String knowledgeExtract) { this.knowledgeExtract = knowledgeExtract; }
    public void setTopicSummary(String topicSummary) { this.topicSummary = topicSummary; }
    public void setPrimaryCategory(String primaryCategory) { this.primaryCategory = primaryCategory; }
    public void setSubCategory(String subCategory) { this.subCategory = subCategory; }
    public void setExtractedPreview(String extractedPreview) { this.extractedPreview = extractedPreview; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}