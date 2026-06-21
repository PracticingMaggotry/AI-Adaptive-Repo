package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItem, Long> {
    List<KnowledgeItem> findByTopicIgnoreCaseOrderByCreatedAtDesc(String topic);
    List<KnowledgeItem> findByTopicIgnoreCaseAndCreatedByOrderByCreatedAtDesc(String topic, String createdBy);
}