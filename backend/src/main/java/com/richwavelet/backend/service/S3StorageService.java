package com.richwavelet.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3-compatible object storage, so media can live on the VPS instead of a hosted service.
 *
 * <p>Works with MinIO, Ceph RGW, Garage, SeaweedFS or real S3 — anything speaking the S3
 * API. Enabled with {@code storage.backend=s3}; the shipped docker-compose stack runs MinIO
 * alongside the app and sets these properties for you.
 *
 * <p>Buckets are created on demand, and objects are uploaded privately. {@link #getPublicUrl}
 * returns a pre-signed URL unless {@code storage.s3.public-base-url} is configured for a
 * bucket that is deliberately public.
 */
@Service
@ConditionalOnProperty(name = "storage.backend", havingValue = "s3")
public class S3StorageService implements ObjectStorage {

    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3;
    private final S3Presigner presigner;

    @Value("${storage.s3.public-base-url:}")
    private String publicBaseUrl;

    @Value("${storage.s3.presign-expiry-seconds:3600}")
    private long presignExpirySeconds;

    @Value("${storage.s3.auto-create-buckets:true}")
    private boolean autoCreateBuckets;

    /** Buckets already confirmed to exist, so we only pay for the check once per boot. */
    private final ConcurrentHashMap<String, Boolean> knownBuckets = new ConcurrentHashMap<>();

    public S3StorageService(
            @Value("${storage.s3.endpoint:}") String endpoint,
            @Value("${storage.s3.region:us-east-1}") String region,
            @Value("${storage.s3.access-key:}") String accessKey,
            @Value("${storage.s3.secret-key:}") String secretKey,
            @Value("${storage.s3.path-style-access:true}") boolean pathStyleAccess) {

        var clientBuilder = S3Client.builder()
                .region(Region.of(region))
                // Path-style addressing is what MinIO and most self-hosted gateways expect.
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());

        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());

        if (endpoint != null && !endpoint.isBlank()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
            presignerBuilder.endpointOverride(URI.create(endpoint));
        }

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
            clientBuilder.credentialsProvider(credentials);
            presignerBuilder.credentialsProvider(credentials);
        }

        this.s3 = clientBuilder.build();
        this.presigner = presignerBuilder.build();

        logger.info("S3 object storage enabled (endpoint: {}, path-style: {})",
                endpoint == null || endpoint.isBlank() ? "aws-default" : endpoint, pathStyleAccess);
    }

    @Override
    public String uploadVideo(String userId, MultipartFile file, String bucket) throws IOException {
        String uniqueName = UUID.randomUUID() + "-" + StorageNaming.sanitizeFileName(file.getOriginalFilename());
        String storagePath = userId + "/" + uniqueName;

        uploadToStorage(bucket, storagePath, file.getBytes(), file.getContentType());

        logger.info("Uploaded video to {}/{}", bucket, storagePath);
        return storagePath;
    }

    @Override
    public void uploadToStorage(String bucket, String path, byte[] data, String contentType) throws IOException {
        ensureBucket(bucket);

        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(path)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            throw new IOException("Upload failed: " + e.statusCode() + " - " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    @Override
    public void downloadFromStorage(String bucket, String storagePath, Path destination) throws IOException {
        logger.info("Downloading {}/{} to {}", bucket, storagePath, destination);

        try {
            s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(storagePath).build(),
                    destination);
        } catch (S3Exception e) {
            throw new IOException("Download failed for " + bucket + "/" + storagePath + ": "
                    + e.awsErrorDetails().errorMessage(), e);
        }

        logger.info("Downloaded {} bytes to {}", Files.size(destination), destination);
    }

    @Override
    public void downloadFile(String fileUrl, Path destination) throws IOException {
        try (var in = new java.net.URL(fileUrl).openStream()) {
            Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public String getPublicUrl(String bucket, String storagePath) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            String base = publicBaseUrl.endsWith("/")
                    ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                    : publicBaseUrl;
            return base + "/" + bucket + "/" + storagePath;
        }

        // Private object: hand out a time-limited pre-signed URL instead.
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(Math.max(60, presignExpirySeconds)))
                        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(storagePath).build())
                        .build())
                .url()
                .toString();
    }

    @Override
    public void deleteFromStorage(String bucket, String storagePath) throws IOException {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storagePath).build());
        } catch (S3Exception e) {
            // S3 delete is idempotent; only surface real failures.
            if (e.statusCode() != 404) {
                throw new IOException("Delete failed: " + e.statusCode(), e);
            }
        }
    }

    @Override
    public void ensureUserFolderExists(String userId, String bucket) {
        // Object stores have no directories; make sure the bucket itself exists.
        ensureBucket(bucket);
    }

    @Override
    public String uploadProcessedVideo(String userId, Path localPath, String outputFileName) throws IOException {
        String storagePath = userId + "/" + outputFileName;

        uploadToStorage("processed-videos", storagePath, Files.readAllBytes(localPath), "video/mp4");

        logger.info("Uploaded processed video to processed-videos/{}", storagePath);
        return storagePath;
    }

    @Override
    public String sanitizeFileName(String fileName) {
        return StorageNaming.sanitizeFileName(fileName);
    }

    @Override
    public String getFileExtension(String fileName) {
        return StorageNaming.getFileExtension(fileName);
    }

    private void ensureBucket(String bucket) {
        if (knownBuckets.containsKey(bucket)) {
            return;
        }

        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            if (!autoCreateBuckets) {
                throw new IllegalStateException("Bucket '" + bucket + "' does not exist and "
                        + "storage.s3.auto-create-buckets is false", e);
            }
            logger.info("Creating missing bucket '{}'", bucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404 && autoCreateBuckets) {
                logger.info("Creating missing bucket '{}'", bucket);
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } else {
                throw e;
            }
        }

        knownBuckets.put(bucket, Boolean.TRUE);
    }
}
