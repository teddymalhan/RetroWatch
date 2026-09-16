package com.richwavelet.backend.api;

import com.richwavelet.backend.model.VideoUpload;
import com.richwavelet.backend.model.UploadStatus;
import com.richwavelet.backend.repository.VideoUploadRepository;
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
import java.util.Set;

@RestController
@RequestMapping("/api/protected/videos")
public class VideoController {

    private static final Logger logger = LoggerFactory.getLogger(VideoController.class);
    private static final String BUCKET = "videos";
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "video/mp4",
            "video/quicktime",
            "video/x-msvideo",
            "video/webm"
    );
    private static final long MAX_FILE_SIZE = 500 * 1024 * 1024; // 500MB

    private final VideoUploadRepository videoUploadRepository;
    private final ObjectStorage storageService;

    public VideoController(VideoUploadRepository videoUploadRepository, ObjectStorage storageService) {
        this.videoUploadRepository = videoUploadRepository;
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        String userId = getUserId(authentication);
        logger.info("Uploading video for user: {}", userId);

        // Validate file
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body("File size exceeds maximum allowed (500MB)");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            return ResponseEntity.badRequest()
                    .body("Invalid file type. Allowed: MP4, MOV, AVI, WebM");
        }

        try {
            // Ensure user folder exists
            storageService.ensureUserFolderExists(userId, BUCKET);

            // Upload to storage
            String storagePath = storageService.uploadVideo(userId, file, BUCKET);
            String fileUrl = storageService.getPublicUrl(BUCKET, storagePath);

            // Save to database
            VideoUpload videoUpload = new VideoUpload();
            videoUpload.setUserId(userId);
            videoUpload.setFileName(storageService.sanitizeFileName(file.getOriginalFilename()));
            videoUpload.setFileUrl(fileUrl);
            videoUpload.setStoragePath(storagePath);
            videoUpload.setFileSize(file.getSize());
            videoUpload.setUploadedAt(OffsetDateTime.now());
            videoUpload.setStatus(UploadStatus.READY);

            VideoUpload saved = videoUploadRepository.save(videoUpload);
            logger.info("Video uploaded successfully: {}", saved.getId());

            return ResponseEntity.ok(saved);

        } catch (IOException e) {
            logger.error("Error uploading video: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading video: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<VideoUpload>> getVideos(Authentication authentication) {
        String userId = getUserId(authentication);
        List<VideoUpload> videos = videoUploadRepository.findByUserIdOrderByUploadedAtDesc(userId);
        return ResponseEntity.ok(videos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getVideo(@PathVariable Long id, Authentication authentication) {
        String userId = getUserId(authentication);

        return videoUploadRepository.findById(id)
                .filter(video -> video.getUserId().equals(userId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteVideo(@PathVariable Long id, Authentication authentication) {
        String userId = getUserId(authentication);

        return videoUploadRepository.findById(id)
                .filter(video -> video.getUserId().equals(userId))
                .map(video -> {
                    try {
                        // Delete from storage
                        if (video.getStoragePath() != null) {
                            storageService.deleteFromStorage(BUCKET, video.getStoragePath());
                        }
                        // Delete from database
                        videoUploadRepository.delete(video);
                        return ResponseEntity.ok().body("Video deleted");
                    } catch (IOException e) {
                        logger.error("Error deleting video: {}", e.getMessage());
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("Error deleting video");
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String getUserId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return jwt.getSubject();
    }
}
