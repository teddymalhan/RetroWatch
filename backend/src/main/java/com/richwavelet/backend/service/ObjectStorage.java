package com.richwavelet.backend.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Object storage abstraction for user media (source videos, ads, processed videos).
 *
 * <p>Which implementation is active is chosen by {@code storage.backend}:
 * <ul>
 *   <li>{@code supabase} (default) — Supabase Storage, via {@link StorageService}</li>
 *   <li>{@code s3} — any S3-compatible endpoint (MinIO, Ceph, Garage, …) on your own
 *       hardware, via {@link S3StorageService}</li>
 * </ul>
 *
 * <p>Paths are always {@code <userId>/<unique-name>} inside a bucket, so switching
 * backends keeps the same layout and the {@code storage_path} column stays valid.
 */
public interface ObjectStorage {

    /**
     * Store an uploaded file and return its storage path (not a URL).
     */
    String uploadVideo(String userId, MultipartFile file, String bucket) throws IOException;

    /**
     * Store raw bytes at an explicit path.
     */
    void uploadToStorage(String bucket, String path, byte[] data, String contentType) throws IOException;

    /**
     * Read an object into a local file. Implementations must work for private buckets
     * (i.e. without relying on the object being publicly readable).
     */
    void downloadFromStorage(String bucket, String storagePath, Path destination) throws IOException;

    /**
     * Download an already-resolved URL to a local file.
     */
    void downloadFile(String fileUrl, Path destination) throws IOException;

    /**
     * URL that a browser can use to fetch the object.
     */
    String getPublicUrl(String bucket, String storagePath);

    /**
     * Delete an object. Deleting something that is already gone must not fail.
     */
    void deleteFromStorage(String bucket, String storagePath) throws IOException;

    /**
     * Prepare whatever per-user container the backend needs.
     */
    void ensureUserFolderExists(String userId, String bucket);

    /**
     * Store a processed video produced locally by the FFmpeg pipeline.
     *
     * @return the storage path (not a URL)
     */
    String uploadProcessedVideo(String userId, Path localPath, String outputFileName) throws IOException;

    /**
     * Normalise an uploaded filename for safe use in an object key.
     */
    String sanitizeFileName(String fileName);

    /**
     * Return the extension of a filename including the leading dot, or "" when absent.
     */
    String getFileExtension(String fileName);
}
