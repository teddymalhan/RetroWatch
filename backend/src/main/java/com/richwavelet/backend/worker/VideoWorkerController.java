package com.richwavelet.backend.worker;

import com.richwavelet.backend.dto.AdInsertionPoint;
import com.richwavelet.backend.dto.GeminiAnalysisResult;
import com.richwavelet.backend.dto.WorkerPayload;
import com.richwavelet.backend.model.*;
import com.richwavelet.backend.repository.AdUploadRepository;
import com.richwavelet.backend.repository.ProcessedVideoRepository;
import com.richwavelet.backend.repository.VideoUploadRepository;
import com.richwavelet.backend.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
public class VideoWorkerController {

    private static final Logger logger = LoggerFactory.getLogger(VideoWorkerController.class);

    /** Buckets matching the ones the upload controllers write to. */
    private static final String VIDEO_BUCKET = "videos";
    private static final String AD_BUCKET = "ads";

    @Value("${worker.auth-token:}")
    private String workerAuthToken;

    private final VideoUploadRepository videoUploadRepository;
    private final AdUploadRepository adUploadRepository;
    private final ProcessedVideoRepository processedVideoRepository;
    private final ObjectStorage storageService;
    private final GeminiService geminiService;
    private final VideoProcessingService videoProcessingService;
    private final ProcessingStatusService statusService;

    public VideoWorkerController(
            VideoUploadRepository videoUploadRepository,
            AdUploadRepository adUploadRepository,
            ProcessedVideoRepository processedVideoRepository,
            ObjectStorage storageService,
            GeminiService geminiService,
            VideoProcessingService videoProcessingService,
            ProcessingStatusService statusService) {
        this.videoUploadRepository = videoUploadRepository;
        this.adUploadRepository = adUploadRepository;
        this.processedVideoRepository = processedVideoRepository;
        this.storageService = storageService;
        this.geminiService = geminiService;
        this.videoProcessingService = videoProcessingService;
        this.statusService = statusService;
    }

    @PostMapping("/process-video-worker")
    public ResponseEntity<Map<String, Object>> processVideo(
            @RequestHeader(name = "X-Worker-Token", required = false) String tokenHeader,
            @RequestHeader(name = "Authorization", required = false) String authHeader,
            @RequestBody WorkerPayload payload) {

        String jobId = payload.jobId();
        String userId = payload.userId();

        logger.info("[worker] Processing video - jobId: {}, userId: {}, videoId: {}, style: {}",
                jobId, userId, payload.videoId(), payload.shaderStyle());

        // Verify the shared worker token issued by the dispatcher
        if (!verifyWorkerToken(tokenHeader != null ? tokenHeader : authHeader)) {
            logger.error("Worker token verification failed for job: {}", jobId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Path workDir = null;

        try {
            // Parse shader style
            ShaderStyle style = ShaderStyle.valueOf(payload.shaderStyle());

            // Update status: DOWNLOADING
            statusService.updateStatus(jobId, userId, ProcessingStage.DOWNLOADING,
                    "Downloading video files from storage...", 5);

            // Create work directory
            workDir = videoProcessingService.createWorkDir(userId);

            // Get video and ad uploads from database
            VideoUpload mainVideo = videoUploadRepository.findById(payload.videoId())
                    .orElseThrow(() -> new IllegalArgumentException("Video not found: " + payload.videoId()));

            List<AdUpload> ads = new ArrayList<>();
            if (payload.adIds() != null && !payload.adIds().isEmpty()) {
                ads = adUploadRepository.findAllById(payload.adIds());
            }

            // Download main video
            Path mainVideoPath = workDir.resolve("main-" + UUID.randomUUID() + ".mp4");
            downloadUpload(VIDEO_BUCKET, mainVideo.getStoragePath(), mainVideo.getFileUrl(), mainVideoPath);
            logger.info("Downloaded main video to: {}", mainVideoPath);

            // Download ads
            List<Path> adPaths = new ArrayList<>();
            for (AdUpload ad : ads) {
                Path adPath = workDir.resolve("ad-" + ad.getId() + "-" + UUID.randomUUID() + ".mp4");
                downloadUpload(AD_BUCKET, ad.getStoragePath(), ad.getFileUrl(), adPath);
                adPaths.add(adPath);
                logger.info("Downloaded ad {} to: {}", ad.getId(), adPath);
            }

            // Update status: ANALYZING
            statusService.updateStatus(jobId, userId, ProcessingStage.ANALYZING,
                    "Uploading video to Gemini for analysis...", 15);

            // Encode to Gemini and analyze
            String geminiVideoData = geminiService.encodeVideo(mainVideoPath, mainVideo.getFileName());

            statusService.updateStatus(jobId, userId, ProcessingStage.ANALYZING,
                    "Analyzing video for scene breaks and ad insertion points...", 25);

            GeminiAnalysisResult analysis = geminiService.analyzeVideo(geminiVideoData, style);
            logger.info("Gemini analysis complete: {} scene breaks, {} ad insertion points",
                    analysis.sceneBreaks().size(), analysis.adInsertionPoints().size());

            // Update status: APPLYING_EFFECTS
            statusService.updateStatus(jobId, userId, ProcessingStage.APPLYING_EFFECTS,
                    "Applying " + style.name() + " shader effects...", 40);

            // Apply shader effects
            Path shadedVideo = videoProcessingService.applyShaderEffects(mainVideoPath, style, workDir);
            logger.info("Applied shader effects, output: {}", shadedVideo);

            // Update status: INSERTING_ADS
            Path videoWithAds = shadedVideo;
            if (!adPaths.isEmpty() && !analysis.adInsertionPoints().isEmpty()) {
                statusService.updateStatus(jobId, userId, ProcessingStage.INSERTING_ADS,
                        "Inserting ads at optimal points...", 55);

                // Get top insertion points (limit to number of ads available)
                List<String> insertionTimestamps = analysis.adInsertionPoints().stream()
                        .limit(adPaths.size())
                        .map(AdInsertionPoint::timestamp)
                        .collect(Collectors.toList());

                videoWithAds = videoProcessingService.insertAds(shadedVideo, adPaths, insertionTimestamps, workDir);
                logger.info("Inserted {} ads, output: {}", insertionTimestamps.size(), videoWithAds);
            } else {
                logger.info("No ads to insert, skipping ad insertion step");
            }

            // Update status: ADDING_AUDIO_EFFECTS
            statusService.updateStatus(jobId, userId, ProcessingStage.ADDING_AUDIO_EFFECTS,
                    "Adding vintage crackly audio effects...", 70);

            Path finalVideo = videoProcessingService.addAudioEffects(videoWithAds, workDir);
            logger.info("Added audio effects, final output: {}", finalVideo);

            // Update status: UPLOADING
            statusService.updateStatus(jobId, userId, ProcessingStage.UPLOADING,
                    "Uploading processed video to storage...", 85);

            // Upload final video to Supabase
            String outputFileName = "retro-" + style.name().toLowerCase() + "-" + UUID.randomUUID() + ".mp4";
            String storagePath = storageService.uploadProcessedVideo(userId, finalVideo, outputFileName);
            String publicUrl = storageService.getPublicUrl("processed-videos", storagePath);

            logger.info("Uploaded processed video to: {}", publicUrl);

            // Save to database
            ProcessedVideo processed = new ProcessedVideo();
            processed.setUserId(userId);
            processed.setSourceVideoId(payload.videoId());
            processed.setShaderStyle(style);
            processed.setFileName(outputFileName);
            processed.setFileUrl(publicUrl);
            processed.setStoragePath(storagePath);
            processed.setAdInsertionPoints(formatAdInsertionPoints(analysis.adInsertionPoints()));
            processed.setVideoSummary(analysis.videoSummary());
            processed.setProcessedAt(OffsetDateTime.now());

            processedVideoRepository.save(processed);
            logger.info("Saved processed video record with ID: {}", processed.getId());

            // Update status: COMPLETED
            statusService.markCompleted(jobId, userId);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "jobId", jobId,
                    "processedVideoId", processed.getId(),
                    "fileUrl", publicUrl
            ));

        } catch (Exception e) {
            logger.error("Error processing video for job {}: {}", jobId, e.getMessage(), e);
            statusService.markFailed(jobId, userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", "error",
                            "jobId", jobId,
                            "error", e.getMessage()
                    ));
        } finally {
            // Clean up temp files
            if (workDir != null) {
                videoProcessingService.cleanupWorkDir(workDir);
            }
        }
    }

    /**
     * Fetch an uploaded object to local disk.
     *
     * <p>Uses the backend-agnostic storage path when the row has one, so private buckets
     * work on every backend. Rows written before {@code storage_path} existed only carry a
     * URL, which we fetch directly.
     */
    private void downloadUpload(String bucket, String storagePath, String fileUrl, Path destination)
            throws IOException {
        if (storagePath != null && !storagePath.isBlank()) {
            storageService.downloadFromStorage(bucket, storagePath, destination);
        } else {
            logger.warn("No storage path for {} — falling back to direct URL download", destination.getFileName());
            storageService.downloadFile(fileUrl, destination);
        }
    }

    /**
     * Verify the shared worker token sent by {@link JobDispatcher}.
     *
     * <p>The value comes from {@code worker.auth-token}. When it is unset the endpoint falls
     * back to unauthenticated access — intended only for local development, and logged loudly
     * so it is obvious in the startup output of a misconfigured deployment.
     */
    private boolean verifyWorkerToken(String presented) {
        if (workerAuthToken == null || workerAuthToken.isBlank()) {
            logger.warn("worker.auth-token is not configured, skipping worker authentication (development mode)");
            return true;
        }

        if (presented == null || presented.isBlank()) {
            logger.error("Missing worker token");
            return false;
        }

        String token = presented.startsWith("Bearer ") ? presented.substring(7) : presented;

        // Constant-time comparison so the token cannot be recovered through timing.
        boolean matches = MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                workerAuthToken.getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            logger.error("Invalid worker token");
            return false;
        }

        logger.debug("Worker token verified");
        return true;
    }

    /**
     * Format ad insertion points as a string for storage
     */
    private String formatAdInsertionPoints(List<AdInsertionPoint> points) {
        return points.stream()
                .map(p -> p.timestamp() + " (priority: " + p.priority() + ")")
                .collect(Collectors.joining(", "));
    }
}
