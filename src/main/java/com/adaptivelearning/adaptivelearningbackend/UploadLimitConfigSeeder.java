package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Makes sure the MAX_UPLOAD_BYTES row exists in system_configuration so System > Configuration can edit it.
 *
 * That page only edits existing rows (PUT never creates one), and it locks a setting whose row is missing. Until now
 * nothing created this row, so the upload size was stuck on the built-in default. This runs once on startup and
 * inserts the row at the same default the backend already uses (10 MB). It never touches an existing row, so a value
 * an admin has saved is never overwritten.
 *
 * Done in Java rather than a Flyway SQL file because on an empty database Flyway runs before Hibernate has created
 * the table, so an INSERT in a migration would fail there.
 */
@Component
public class UploadLimitConfigSeeder implements CommandLineRunner {

    static final String KEY = "MAX_UPLOAD_BYTES";
    static final String DEFAULT_BYTES = String.valueOf(10L * 1024 * 1024);

    private final SystemConfigurationRepository configRepository;

    public UploadLimitConfigSeeder(SystemConfigurationRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Override
    public void run(String... args) {
        try {
            if (configRepository.findByConfigKey(KEY).isEmpty()) {
                configRepository.save(new SystemConfiguration(KEY, DEFAULT_BYTES, "LONG",
                        "Maximum size of one uploaded file, in bytes."));
            }
        } catch (Exception e) {
            // Seeding is a convenience; never stop the app from starting over it.
            System.err.println("Could not seed " + KEY + ": " + e.getMessage());
        }
    }
}
