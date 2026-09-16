package com.richwavelet.backend.service;

import java.text.Normalizer;

/**
 * Filename handling shared by every {@link ObjectStorage} implementation, so object keys
 * look the same regardless of backend.
 */
public final class StorageNaming {

    private StorageNaming() {
    }

    /**
     * Strip accents and anything that is not alphanumeric, dot, dash or underscore.
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }

        // Normalize unicode characters
        String normalized = Normalizer.normalize(fileName, Normalizer.Form.NFD);
        String noAccents = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // Replace unsafe characters
        String safe = noAccents.replaceAll("[^A-Za-z0-9._-]", "_");

        // Collapse multiple underscores
        safe = safe.replaceAll("_+", "_");

        // Remove leading/trailing underscores
        safe = safe.replaceAll("^_+|_+$", "");

        return safe.isEmpty() ? "unnamed" : safe;
    }

    public static String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
