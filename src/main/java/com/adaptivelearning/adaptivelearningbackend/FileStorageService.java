package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Local disk storage abstraction for handout files and diagram images. */
@Service
public class FileStorageService {

    private final Path storageRoot;

    public FileStorageService(@Value("${storage.root:/data/storage}") String storageRootPath) {
        this.storageRoot = Paths.get(storageRootPath);
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage root directory: " + storageRootPath, e);
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

    public void store(String key, InputStream inputStream, String contentType, long contentLength)
            throws IOException {
        Path filePath = resolveFilePath(key);
        Files.createDirectories(filePath.getParent());
        Files.copy(inputStream, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public void storeBytes(String key, byte[] bytes, String contentType) throws IOException {
        Path filePath = resolveFilePath(key);
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, bytes);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public byte[] load(String key) {
        try {
            Path filePath = resolveFilePath(key);
            if (!Files.exists(filePath)) {
                return null;
            }
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            return null;
        }
    }

    public InputStream openStream(String key) {
        try {
            Path filePath = resolveFilePath(key);
            if (!Files.exists(filePath)) {
                return null;
            }
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean exists(String key) {
        Path filePath = resolveFilePath(key);
        return Files.exists(filePath);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public void delete(String key) {
        try {
            Path filePath = resolveFilePath(key);
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            System.err.println("Local storage delete failed for key " + key + " (non-fatal): " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Path resolveFilePath(String key) {
        Path resolved = storageRoot.resolve(key).normalize();
        // Security: prevent directory traversal
        if (!resolved.startsWith(storageRoot)) {
            throw new SecurityException("Attempted path traversal: " + key);
        }
        return resolved;
    }
}

