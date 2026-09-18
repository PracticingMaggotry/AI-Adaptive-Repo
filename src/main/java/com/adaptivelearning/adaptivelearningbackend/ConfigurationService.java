package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Centralized service for accessing system configuration.
 * Provides type-safe getters with fallback defaults for missing configs.
 */
@Service
public class ConfigurationService {

    @Autowired
    private SystemConfigurationRepository configRepository;

    // ── Quiz & Learning Limits ───────────────────────────────────────────

    public int getMaxUploadsPerDay() {
        return getIntConfig("MAX_UPLOADS_PER_DAY", 4);
    }

    public int getMaxMixedQuestions() {
        return getIntConfig("MAX_MIXED_QUESTIONS", 30);
    }

    public int getMaxTopicLength() {
        return getIntConfig("MAX_TOPIC_LENGTH", 100);
    }

    public long getMaxUploadBytes() {
        return getLongConfig("MAX_UPLOAD_BYTES", 10L * 1024 * 1024);
    }

    // ── Difficulty Score Thresholds ──────────────────────────────────────

    public int getHardDifficultyThreshold() {
        return getIntConfig("HARD_DIFFICULTY_THRESHOLD", 80);
    }

    public int getMediumDifficultyThreshold() {
        return getIntConfig("MEDIUM_DIFFICULTY_THRESHOLD", 50);
    }

    // ── File Upload Configuration ────────────────────────────────────────

    public String getAllowedFileExtensions() {
        return getStringConfig("ALLOWED_FILE_EXTENSIONS", "pdf,txt,csv,doc,docx");
    }

    public String getAllowedContentTypes() {
        return getStringConfig("ALLOWED_CONTENT_TYPES", "pdf,text,csv,msword,wordprocessingml,octet-stream");
    }

    // ── Text Extraction & Preview ────────────────────────────────────────

    public int getMaxPreviewLength() {
        return getIntConfig("MAX_PREVIEW_LENGTH", 1800);
    }

    public int getMinExtractedTextLength() {
        return getIntConfig("MIN_EXTRACTED_TEXT_LENGTH", 100);
    }

    // ── Password & Security ──────────────────────────────────────────────

    public int getMinPasswordLength() {
        return getIntConfig("MIN_PASSWORD_LENGTH", 8);
    }

    public boolean requirePasswordUppercase() {
        return getBooleanConfig("REQUIRE_PASSWORD_UPPERCASE", true);
    }

    public boolean requirePasswordNumber() {
        return getBooleanConfig("REQUIRE_PASSWORD_NUMBER", true);
    }

    public boolean requirePasswordSpecialChar() {
        return getBooleanConfig("REQUIRE_PASSWORD_SPECIAL_CHAR", true);
    }

    // ── Generic type-safe getters with defaults ──────────────────────────

    private int getIntConfig(String key, int defaultValue) {
        Optional<SystemConfiguration> config = configRepository.findByConfigKey(key);
        if (config.isPresent()) {
            try {
                return config.get().getIntValue();
            } catch (NumberFormatException e) {
                logConfigError(key, e);
            }
        }
        return defaultValue;
    }

    private long getLongConfig(String key, long defaultValue) {
        Optional<SystemConfiguration> config = configRepository.findByConfigKey(key);
        if (config.isPresent()) {
            try {
                return config.get().getLongValue();
            } catch (NumberFormatException e) {
                logConfigError(key, e);
            }
        }
        return defaultValue;
    }

    private String getStringConfig(String key, String defaultValue) {
        Optional<SystemConfiguration> config = configRepository.findByConfigKey(key);
        if (config.isPresent()) {
            String value = config.get().getStringValue();
            return value != null && !value.isBlank() ? value : defaultValue;
        }
        return defaultValue;
    }

    private boolean getBooleanConfig(String key, boolean defaultValue) {
        Optional<SystemConfiguration> config = configRepository.findByConfigKey(key);
        if (config.isPresent()) {
            return config.get().getBooleanValue() != null ? config.get().getBooleanValue() : defaultValue;
        }
        return defaultValue;
    }

    private void logConfigError(String key, Exception e) {
        System.err.println("Failed to parse configuration key '" + key + "': " + e.getMessage());
    }

    // ── Update config value at runtime ───────────────────────────────────

    public void setConfig(String key, String value, String dataType) {
        Optional<SystemConfiguration> existing = configRepository.findByConfigKey(key);
        if (existing.isPresent()) {
            SystemConfiguration config = existing.get();
            config.setConfigValue(value);
            config.setDataType(dataType);
            configRepository.save(config);
        } else {
            SystemConfiguration newConfig = new SystemConfiguration(key, value, dataType, "");
            configRepository.save(newConfig);
        }
    }
}

