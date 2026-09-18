package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Local-filesystem object storage backed by a Railway persistent volume,
 * replacing the previous Supabase/S3-compatible implementation.
 *
 * Set the mount path via the "storage.root" property (default "/data/storage"),
 * matching wherever the Railway volume is mounted for this service. Keys are
 * relative paths (e.g. "materials/12345_handout.pdf") and are resolved under
 * storageRoot; parent directories are created on demand.
 */
@Service
public class FileStorageService {

    private final Path storageRoot;

    public FileStorageService(@Value("${storage.root:/data/storage}") String storageRootPath) {
        this.storageRoot = Paths.get(storageRootPath);
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new RuntimeException("Could not create storage root at " + storageRootPath, e);
        }
    }

    // ── Key helpers ──────────────────────────────────────────────────────────

    public static String handoutKey(String storedFilename) {
        return "materials/" + storedFilename;
    }

    public static String diagramKey(String diagramFilename) {
        return "materials/diagrams/" + diagramFilename;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /** contentType is accepted for interface parity with the old S3-backed version; the local filesystem has no notion of it. */
    public void store(String key, InputStream inputStream, String contentType, long contentLength) throws IOException {
        Path filePath = resolve(key);
        Files.createDirectories(filePath.getParent());
        try (inputStream) {
            Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void storeBytes(String key, byte[] bytes, String contentType) {
        try {
            Path filePath = resolve(key);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, bytes);
        } catch (IOException e) {
            throw new RuntimeException("Could not write file for key " + key, e);
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public byte[] load(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            System.err.println("Local storage read failed for key " + key + " (non-fatal): " + e.getMessage());
            return null;
        }
    }

    public InputStream openStream(String key) {
        byte[] bytes = load(key);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            System.err.println("Local storage delete failed for key " + key + " (non-fatal): " + e.getMessage());
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Resolves a key against storageRoot, rejecting any attempt to escape it via path traversal. */
    private Path resolve(String key) {
        Path resolved = storageRoot.resolve(key).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new SecurityException("Rejected storage key attempting path traversal: " + key);
        }
        return resolved;
    }
}