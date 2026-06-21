package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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

    /**
     * Filename (stored under uploads/materials/diagrams/) of the most relevant
     * extracted figure/diagram image from this material's PDF, or null if
     * the file had no images, wasn't a PDF, or no figure-like image was found.
     * Used to ground DIAGRAM-type quiz questions in the actual artwork instead
     * of letting the AI invent plausible-sounding labels from nearby text.
     */
    @Column(name = "diagram_image_filename")
    private String diagramImageFilename;

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
    public String getDiagramImageFilename() { return diagramImageFilename; }

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
    public void setDiagramImageFilename(String diagramImageFilename) { this.diagramImageFilename = diagramImageFilename; }
}