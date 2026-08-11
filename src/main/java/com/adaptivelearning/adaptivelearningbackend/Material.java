package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One student's uploaded handout, including extracted metadata, category, and AI-generated summary. */
@Entity
@Table(name = "materials")
public class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String topic;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private Long sizeBytes;
    private String uploadedBy;
    private LocalDateTime uploadedAt;

    @Column(length = 2000)
    private String extractedPreview;

    @Column(length = 500)
    private String topicSummary;

    /** Broad subject category chosen from ClaudeService.MATERIAL_CATEGORIES. */
    @Column(name = "primary_category", length = 80)
    private String primaryCategory;

    /** Optional short specific sub-label within primaryCategory (e.g. "Data Structures"). */
    @Column(name = "sub_category", length = 120)
    private String subCategory;

    /** Filename of the most relevant extracted diagram image, or null if none was found. */
    @Column(name = "diagram_image_filename")
    private String diagramImageFilename;

    @Column(name = "knowledge_extract", columnDefinition = "TEXT")
    private String knowledgeExtract;

    /** Content fingerprint linking this row to its shared MaterialContent registry entry for dedup. */
    @Column(name = "content_hash", length = 80)
    private String contentHash;


    public Material() {}

    public Material(String topic, String originalFilename, String storedFilename, String contentType, Long sizeBytes, String uploadedBy, String extractedPreview) {
        this.topic = topic;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.extractedPreview = extractedPreview;
        this.uploadedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getTopic() { return topic; }
    public String getOriginalFilename() { return originalFilename; }
    public String getStoredFilename() { return storedFilename; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getUploadedBy() { return uploadedBy; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public String getExtractedPreview() { return extractedPreview; }
    public String getTopicSummary() { return topicSummary; }
    public String getPrimaryCategory() { return primaryCategory; }
    public String getSubCategory() { return subCategory; }
    public String getDiagramImageFilename() { return diagramImageFilename; }
    public String getKnowledgeExtract() { return knowledgeExtract; }
    public String getContentHash() { return contentHash; }

    public void setKnowledgeExtract(String knowledgeExtract) { this.knowledgeExtract = knowledgeExtract; }
    public void setId(Long id) { this.id = id; }
    public void setTopic(String topic) { this.topic = topic; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public void setExtractedPreview(String extractedPreview) { this.extractedPreview = extractedPreview; }
    public void setTopicSummary(String topicSummary) { this.topicSummary = topicSummary; }
    public void setPrimaryCategory(String primaryCategory) { this.primaryCategory = primaryCategory; }
    public void setSubCategory(String subCategory) { this.subCategory = subCategory; }
    public void setDiagramImageFilename(String diagramImageFilename) { this.diagramImageFilename = diagramImageFilename; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}