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
 * Single abstraction over Supabase Storage (S3-compatible object storage).
 *
 * Every file the app writes/reads goes through this service. Callers use
 * logical "keys":
 *   - Handout files:   "materials/{storedFilename}"
 *   - Diagram images:  "materials/diagrams/{diagramFilename}"
 *
 * Supabase Storage is S3-compatible (Project Settings → Storage → S3
 * Connection), so the AWS SDK v2 works without modification — only the
 * endpoint, region, and credentials differ from a "real" AWS/R2 bucket.
 * Path-style addressing (forcePathStyle) is required because Supabase's
 * S3 gateway does not support virtual-hosted-style bucket URLs.
 *
 * Required environment variables (set in Railway service variables):
 *   SUPABASE_S3_ENDPOINT    — https://<project-ref>.supabase.co/storage/v1/s3
 *   SUPABASE_S3_ACCESS_KEY  — S3 access key id (Storage → S3 Connection → New access key)
 *   SUPABASE_S3_SECRET_KEY  — S3 secret access key
 *   SUPABASE_S3_REGION      — the region shown on that same S3 Connection page (e.g. "us-east-1")
 *   SUPABASE_S3_BUCKET      — bucket name (e.g. "adaptive-learning-uploads")
 *
 * The bucket must exist in advance (create it under Storage in the
 * Supabase dashboard). It can be left "private" — nothing in this app
 * relies on Supabase's public URL scheme; every read goes through this
 * service using the S3 credentials, same as the old R2 setup.
 */
@Service
public class FileStorageService {

    private final S3Client s3;
    private final String bucket;

    public FileStorageService(
            @Value("${supabase.s3.endpoint}") String endpoint,
            @Value("${supabase.s3.access-key}") String accessKey,
            @Value("${supabase.s3.secret-key}") String secretKey,
            @Value("${supabase.s3.region}") String region,
            @Value("${supabase.s3.bucket}") String bucket) {

        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                // Supabase's S3-compatible gateway requires path-style URLs
                // (https://endpoint/bucket/key) rather than virtual-hosted
                // style (https://bucket.endpoint/key), which is what R2/AWS
                // default to.
                .forcePathStyle(true)
                .build();
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

    public void storeBytes(String key, byte[] bytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        s3.putObject(request, RequestBody.fromBytes(bytes));
    }

    // ── Read ─────────────────────────────────────────────────────────────────

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

    public InputStream openStream(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            byte[] bytes = s3.getObject(request, ResponseTransformer.toBytes()).asByteArray();
            return new ByteArrayInputStream(bytes);
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

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

    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            // Already gone — treat as success
        } catch (Exception e) {
            System.err.println("Supabase delete failed for key " + key + " (non-fatal): " + e.getMessage());
        }
    }
}