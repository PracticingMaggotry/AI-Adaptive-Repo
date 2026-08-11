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

/** S3-compatible object storage abstraction (Supabase Storage) for handout files and diagram images. */
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