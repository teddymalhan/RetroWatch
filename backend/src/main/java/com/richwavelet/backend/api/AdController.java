package com.richwavelet.backend.api;

import com.richwavelet.backend.model.AdUpload;
import com.richwavelet.backend.repository.AdUploadRepository;
import com.richwavelet.backend.service.AdAnalysisService;
import com.richwavelet.backend.service.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/protected/ads")
public class AdController {
    private static final Logger logger = LoggerFactory.getLogger(AdController.class);
    private static final String BUCKET = "ads";
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "video/mp4",
            "video/quicktime",
            "video/x-msvideo",
            "video/webm"
    );
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB for ads

    private final AdUploadRepository adUploadRepository;
    private final ObjectStorage storageService;
    private final AdAnalysisService adAnalysisService;

    public AdController(
            AdUploadRepository adUploadRepository,
            ObjectStorage storageService,
            AdAnalysisService adAnalysisService) {
        this.adUploadRepository = adUploadRepository;
        this.storageService = storageService;
        this.adAnalysisService = adAnalysisService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAd(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        String userId = getUserId(authentication);
        logger.info("Uploading ad for user: {}", userId);

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body("Ad file size exceeds maximum allowed (100MB)");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest()
                    .body("Invalid file type. Allowed: MP4, MOV, AVI, WebM");
        }

        try {
            storageService.ensureUserFolderExists(userId, BUCKET);

            String storagePath = storageService.uploadVideo(userId, file, BUCKET);
            String fileUrl = storageService.getPublicUrl(BUCKET, storagePath);

            AdUpload adUpload = new AdUpload();
            adUpload.setUserId(userId);
            adUpload.setFileName(storageService.sanitizeFileName(file.getOriginalFilename()));
            adUpload.setFileUrl(fileUrl);
            adUpload.setStoragePath(storagePath);
            adUpload.setFileSize(file.getSize());
            adUpload.setUploadedAt(OffsetDateTime.now());

            AdUpload saved = adUploadRepository.save(adUpload);
            logger.info("Ad uploaded successfully: {}", saved.getId());

            // Trigger async analysis
            adAnalysisService.analyzeAdAsync(saved.getId());

            return ResponseEntity.ok(saved);

        } catch (IOException e) {
            logger.error("Error uploading ad: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading ad: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<AdUpload>> getAds(Authentication authentication) {
        String userId = getUserId(authentication);
        List<AdUpload> ads = adUploadRepository.findByUserIdOrderByUploadedAtDesc(userId);
        return ResponseEntity.ok(ads);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getAd(@PathVariable String id, Authentication authentication) {
        String userId = getUserId(authentication);

        return adUploadRepository.findById(id)
                .filter(ad -> ad.getUserId().equals(userId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAd(@PathVariable String id, Authentication authentication) {
        String userId = getUserId(authentication);

        return adUploadRepository.findById(id)
                .filter(ad -> ad.getUserId().equals(userId))
                .map(ad -> {
                    try {
                        if (ad.getStoragePath() != null) {
                            storageService.deleteFromStorage(BUCKET, ad.getStoragePath());
                        }
                        adUploadRepository.delete(ad);
                        return ResponseEntity.ok().body("Ad deleted");
                    } catch (IOException e) {
                        logger.error("Error deleting ad: {}", e.getMessage());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("Error deleting ad");
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<?> triggerAnalysis(@PathVariable String id, Authentication authentication) {
        String userId = getUserId(authentication);

        return adUploadRepository.findById(id)
                .filter(ad -> ad.getUserId().equals(userId))
                .map(ad -> {
                    if ("analyzing".equals(ad.getAnalysisStatus())) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Analysis already in progress"));
                    }

                    // Reset status and trigger analysis
                    ad.setAnalysisStatus("pending");
                    adUploadRepository.save(ad);
                    adAnalysisService.analyzeAdAsync(ad.getId());

                    logger.info("Triggered analysis for ad {}", id);
                    return ResponseEntity.ok(Map.of(
                            "status", "queued",
                            "message", "Analysis started"
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/metadata")
    public ResponseEntity<?> getAdMetadata(@PathVariable String id, Authentication authentication) {
        String userId = getUserId(authentication);

        return adUploadRepository.findById(id)
                .filter(ad -> ad.getUserId().equals(userId))
                .map(ad -> adAnalysisService.getMetadata(id)
                        .map(metadata -> ResponseEntity.ok((Object) metadata))
                        .orElse(ResponseEntity.ok(Map.of(
                                "status", ad.getAnalysisStatus(),
                                "message", "Analysis not yet complete"
                        ))))
                .orElse(ResponseEntity.notFound().build());
    }

    private String getUserId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getSubject();
    }
}
