package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Canonical, deduplicated registry of uploaded-handout CONTENT — as opposed
 * to {@link Material}, which is per-student. Lets identical uploads (e.g. 30
 * students uploading the same course PDF) skip redundant R2 uploads and
 * Claude calls (knowledge extraction, summary, categorization), while each
 * student still keeps their own Material row and fully personalized quiz
 * questions / lesson content (not stored here).
 *
 * One row per distinct contentHash (see MaterialContentService.computeContentHash).
 * Reference-counted implicitly: deleted only once MaterialContentService.
 * releaseIfOrphaned finds zero Material rows still pointing at its hash —
 * i.e. every student who uploaded this content has deleted their topic.
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

    /** SHA-256 of normalized extracted text, or a "unique-<uuid>" sentinel for un-fingerprintable content. */
    @Column(name = "content_hash", nullable = false, unique = true, length = 80)
    private String contentHash;

    /** R2 key suffix, shared across every Material row with this hash. */
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