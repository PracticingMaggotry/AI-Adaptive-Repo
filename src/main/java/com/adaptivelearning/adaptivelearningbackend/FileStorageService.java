package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Single abstraction over Cloudflare R2 (S3-compatible object storage).
 *
 * Every file the app used to write to the local {@code uploads/} directory
 * now goes through this service instead. Callers use logical "keys":
 *   - Handout files:   "materials/{storedFilename}"
 *   - Diagram images:  "materials/diagrams/{diagramFilename}"
 *
 * R2 is S3-compatible, so the AWS SDK v2 works without modification.
 * The only R2-specific detail is the endpoint URL format:
 *   https://{accountId}.r2.cloudflarestorage.com
 *
 * Required environment variables (set in Railway service variables):
 *   R2_ENDPOINT     — https://<account-id>.r2.cloudflarestorage.com
 *   R2_ACCESS_KEY   — R2 API token Access Key ID
 *   R2_SECRET_KEY   — R2 API token Secret Access Key
 *   R2_BUCKET       — bucket name (e.g. "adaptive-learning-uploads")
 *
 * The bucket must exist in advance (create it in the R2 dashboard).
 * Public access is NOT needed — all reads go through this service.
 *
 * Cloudflare R2 free tier: 10 GB storage, 1M Class-A ops, 10M Class-B ops
 * per month — more than enough for a student learning app.
 */
@Service
public class FileStorageService {

    private final S3Client s3;
    private final String bucket;

    public FileStorageService(
            @Value("${r2.endpoint}") String endpoint,
            @Value("${r2.access-key}") String accessKey,
            @Value("${r2.secret-key}") String secretKey,
            @Value("${r2.bucket}") String bucket) {

        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // R2 requires a region value but ignores it; "auto" is the R2 recommendation
                .region(Region.of("auto"))
                .build();
    }

    // ── Key helpers ──────────────────────────────────────────────────────────

    /** Key used to store a handout file in R2. */
    public static String handoutKey(String storedFilename) {
        return "materials/" + storedFilename;
    }

    /** Key used to store an extracted diagram image in R2. */
    public static String diagramKey(String diagramFilename) {
        return "materials/diagrams/" + diagramFilename;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Uploads a file from an {@link InputStream} to R2.
     *
     * @param key         object key in the bucket (use {@link #handoutKey} /
     *                    {@link #diagramKey} helpers)
     * @param inputStream content to upload — the stream is fully consumed and closed
     * @param contentType MIME type stored as object metadata (used when serving)
     * @param contentLength exact byte count; required by the AWS SDK for streaming uploads
     */
    public void store(String key, InputStream inputStream, String contentType, long contentLength)
            throws IOException {
        byte[] bytes;
        try (inputStream) {
            bytes = inputStream.readAllBytes();
        }
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3.putObject(request, RequestBody.fromBytes(bytes));
    }

    /**
     * Uploads raw bytes directly (used for diagram PNG images assembled in memory).
     */
    public void storeBytes(String key, byte[] bytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3.putObject(request, RequestBody.fromBytes(bytes));
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Downloads an object and returns its bytes.
     * Returns {@code null} if the object does not exist.
     */
    public byte[] load(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3.getObject(request, ResponseTransformer.toBytes()).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * Opens a streaming {@link InputStream} for the given key.
     * Returns {@code null} if the object does not exist.
     * <p>
     * The caller MUST close the returned stream. Use try-with-resources.
     */
    public InputStream openStream(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            // Load fully into memory and wrap — avoids leaking the HTTP connection
            // if callers forget to close, and keeps things simple for text extraction
            // which needs to read the bytes multiple times (PDFBox, POI).
            byte[] bytes = s3.getObject(request, ResponseTransformer.toBytes()).asByteArray();
            return new ByteArrayInputStream(bytes);
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    /**
     * Returns {@code true} if the given key exists in R2.
     * Used only to populate the "fileStillExists" field in admin content review.
     */
    public boolean exists(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            s3.getObject(request, ResponseTransformer.toBytes());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /**
     * Deletes an object from R2. No-ops silently if the key does not exist
     * (matches the old {@code Files.deleteIfExists} semantics).
     */
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            // Already gone — treat as success, same as Files.deleteIfExists
        } catch (Exception e) {
            System.err.println("R2 delete failed for key " + key + " (non-fatal): " + e.getMessage());
        }
    }
}