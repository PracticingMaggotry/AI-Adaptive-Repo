package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin REST API for managing system configuration.
 * Endpoint: /admin/configuration
 */
@RestController
@RequestMapping("/admin/configuration")
public class SystemConfigurationController {

    @Autowired
    private SystemConfigurationRepository configRepository;

    @Autowired
    private ConfigurationService configurationService;

    /**
     * GET /admin/configuration
     * List all system configurations.
     */
    @GetMapping
    public List<SystemConfiguration> listConfigurations() {
        return configRepository.findAll();
    }

    /**
     * GET /admin/configuration/{key}
     * Get a single configuration by key.
     */
    @GetMapping("/{key}")
    public ResponseEntity<SystemConfiguration> getConfiguration(@PathVariable String key) {
        Optional<SystemConfiguration> config = configRepository.findByConfigKey(key);
        return config.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * POST /admin/configuration
     * Create a new configuration.
     */
    @PostMapping
    public ResponseEntity<SystemConfiguration> createConfiguration(
            @RequestBody SystemConfiguration config) {
        if (config.getConfigKey() == null || config.getConfigKey().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (configRepository.findByConfigKey(config.getConfigKey()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        SystemConfiguration saved = configRepository.save(config);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * PUT /admin/configuration/{key}
     * Update an existing configuration by key.
     */
    @PutMapping("/{key}")
    public ResponseEntity<SystemConfiguration> updateConfiguration(
            @PathVariable String key,
            @RequestBody SystemConfiguration updated) {
        Optional<SystemConfiguration> optional = configRepository.findByConfigKey(key);
        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SystemConfiguration existing = optional.get();
        if (updated.getConfigValue() != null) {
            existing.setConfigValue(updated.getConfigValue());
        }
        if (updated.getDescription() != null) {
            existing.setDescription(updated.getDescription());
        }
        if (updated.getDataType() != null) {
            existing.setDataType(updated.getDataType());
        }

        SystemConfiguration saved = configRepository.save(existing);
        return ResponseEntity.ok(saved);
    }

    /**
     * DELETE /admin/configuration/{key}
     * Delete a configuration by key.
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deleteConfiguration(@PathVariable String key) {
        Optional<SystemConfiguration> optional = configRepository.findByConfigKey(key);
        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        configRepository.delete(optional.get());
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /admin/configuration/current/all
     * Get all current configuration values (read-only snapshot for admins).
     */
    @GetMapping("/current/all")
    public ResponseEntity<Map<String, Object>> getCurrentConfiguration() {
        return ResponseEntity.ok(Map.ofEntries(
                Map.entry("maxUploadsPerDay", configurationService.getMaxUploadsPerDay()),
                Map.entry("maxMixedQuestions", configurationService.getMaxMixedQuestions()),
                Map.entry("maxTopicLength", configurationService.getMaxTopicLength()),
                Map.entry("maxUploadBytes", configurationService.getMaxUploadBytes()),
                Map.entry("hardDifficultyThreshold", configurationService.getHardDifficultyThreshold()),
                Map.entry("mediumDifficultyThreshold", configurationService.getMediumDifficultyThreshold()),
                Map.entry("maxPreviewLength", configurationService.getMaxPreviewLength()),
                Map.entry("minExtractedTextLength", configurationService.getMinExtractedTextLength()),
                Map.entry("minPasswordLength", configurationService.getMinPasswordLength()),
                Map.entry("requirePasswordUppercase", configurationService.requirePasswordUppercase()),
                Map.entry("requirePasswordNumber", configurationService.requirePasswordNumber()),
                Map.entry("requirePasswordSpecialChar", configurationService.requirePasswordSpecialChar())
        ));
    }
}

