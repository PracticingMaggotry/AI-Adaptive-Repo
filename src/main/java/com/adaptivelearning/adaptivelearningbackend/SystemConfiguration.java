package com.adaptivelearning.adaptivelearningbackend;

import jakarta.persistence.*;

@Entity
@Table(name = "system_configuration")
public class SystemConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String configKey;

    @Column(columnDefinition = "TEXT")
    private String configValue;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String dataType; // STRING, INTEGER, LONG, BOOLEAN, DOUBLE

    // ── Constructors ─────────────────────────────────────────────────────

    public SystemConfiguration() {
    }

    public SystemConfiguration(String configKey, String configValue, String dataType, String description) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.dataType = dataType;
        this.description = description;
    }

    // ── Getters & Setters ────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    // ── Type-safe getters ────────────────────────────────────────────────

    public Integer getIntValue() {
        return configValue != null ? Integer.parseInt(configValue) : null;
    }

    public Long getLongValue() {
        return configValue != null ? Long.parseLong(configValue) : null;
    }

    public Boolean getBooleanValue() {
        return configValue != null ? Boolean.parseBoolean(configValue) : null;
    }

    public Double getDoubleValue() {
        return configValue != null ? Double.parseDouble(configValue) : null;
    }

    public String getStringValue() {
        return configValue;
    }
}

