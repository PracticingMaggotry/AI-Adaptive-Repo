package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Admin REST API for managing system configuration.
 * Endpoint: /api/admin/configuration
 *
 * SECURITY: lives under /api/** so AuthInterceptor and CsrfInterceptor cover it, and every
 * method checks isAdmin(session) itself, because those interceptors don't enforce roles for API calls.
 */
@RestController
@RequestMapping("/api/admin/configuration")
public class SystemConfigurationController {

    @Autowired
    private SystemConfigurationRepository configRepository;

    @Autowired
    private ConfigurationService configurationService;

    private boolean isAdmin(HttpSession session) {
        Object flag = session.getAttribute("isAdmin");
        return flag instanceof Boolean && (Boolean) flag;
    }

    private ResponseEntity<Object> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Admin access required."));
    }

    /** GET /api/admin/configuration: list all system configurations. */
    @GetMapping
    public ResponseEntity<Object> listConfigurations(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ResponseEntity.ok(configRepository.findAll());
    }

    /** GET /api/admin/configuration/{key}: get a single configuration by key. */
    @GetMapping("/{key}")
    public ResponseEntity<Object> getConfiguration(@PathVariable String key, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        Optional<SystemConfiguration> config = configRepository.findByConfigKey(key);
        return config.<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** POST /api/admin/configuration: create a new configuration. */
    @PostMapping
    public ResponseEntity<Object> createConfiguration(
            @RequestBody SystemConfiguration config, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        if (config.getConfigKey() == null || config.getConfigKey().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (configRepository.findByConfigKey(config.getConfigKey()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        SystemConfiguration saved = configRepository.save(config);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** PUT /api/admin/configuration/{key}: update an existing configuration by key. */
    @PutMapping("/{key}")
    public ResponseEntity<Object> updateConfiguration(
            @PathVariable String key,
            @RequestBody SystemConfiguration updated,
            HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        Optional<SystemConfiguration> optional = configRepository.findByConfigKey(key);
        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SystemConfiguration existing = optional.get();
        if (updated.getConfigValue() != null) existing.setConfigValue(updated.getConfigValue());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getDataType() != null) existing.setDataType(updated.getDataType());

        return ResponseEntity.ok(configRepository.save(existing));
    }

    /** DELETE /api/admin/configuration/{key}: delete a configuration by key. */
    @DeleteMapping("/{key}")
    public ResponseEntity<Object> deleteConfiguration(@PathVariable String key, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        Optional<SystemConfiguration> optional = configRepository.findByConfigKey(key);
        if (optional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        configRepository.delete(optional.get());
        return ResponseEntity.noContent().build();
    }

    /** GET /api/admin/configuration/current/all: read-only snapshot of current values. */
    @GetMapping("/current/all")
    public ResponseEntity<Object> getCurrentConfiguration(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
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