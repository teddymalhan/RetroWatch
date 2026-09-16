package com.richwavelet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richwavelet.backend.dto.AdInsertionPoint;
import com.richwavelet.backend.dto.GeminiAnalysisResult;
import com.richwavelet.backend.dto.SceneBreak;
import com.richwavelet.backend.model.ShaderStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Video analysis for the processing pipeline, backed by {@link GeminiClient}.
 *
 * <p>Previously this talked to Vertex AI through the Google Cloud SDK with Application
 * Default Credentials. It now uses the provider-agnostic REST client, so it runs anywhere
 * the app runs — including a self-hosted Gemini-compatible endpoint.
 */
@Service
public class GeminiService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);

    /** Gemini accepts inline media up to ~20 MB per request. */
    private static final long DEFAULT_INLINE_MAX_BYTES = 20L * 1024 * 1024;

    @Value("${gemini.inline-max-bytes:20971520}")
    private long inlineMaxBytes = DEFAULT_INLINE_MAX_BYTES;

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public GeminiService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Read a video from disk and encode it for inline inclusion in a Gemini request.
     *
     * @return Base64 encoded video data
     */
    public String encodeVideo(Path videoPath, String displayName) throws IOException {
        long size = Files.size(videoPath);
        long limit = inlineMaxBytes > 0 ? inlineMaxBytes : DEFAULT_INLINE_MAX_BYTES;

        if (size > limit) {
            throw new IOException(String.format(
                    "Video %s is %d bytes, above the %d byte inline limit accepted by the Gemini "
                            + "generateContent API. Trim the source video or raise gemini.inline-max-bytes "
                            + "if the configured endpoint supports larger payloads.",
                    displayName, size, limit));
        }

        byte[] videoBytes = Files.readAllBytes(videoPath);
        logger.info("Encoded video for Gemini: {} ({} bytes)", displayName, videoBytes.length);
        return Base64.getEncoder().encodeToString(videoBytes);
    }

    /**
     * Analyze a video for scene breaks and ad insertion points.
     *
     * @param base64VideoData video content produced by {@link #encodeVideo(Path, String)}
     */
    public GeminiAnalysisResult analyzeVideo(String base64VideoData, ShaderStyle style) throws IOException {
        logger.info("Analyzing video with Gemini for {} style", style);

        byte[] videoBytes = Base64.getDecoder().decode(base64VideoData);
        String prompt = buildAnalysisPrompt(style);

        String responseText;
        try {
            responseText = geminiClient.generateJson(prompt, videoBytes, "video/mp4", buildResponseSchema());
        } catch (IOException e) {
            logger.error("Gemini video analysis failed: {}", e.getMessage(), e);
            throw new IOException("Gemini analysis failed: " + e.getMessage(), e);
        }

        return parseAnalysisResponse(responseText);
    }

    /**
     * Build the response schema so Gemini returns a conforming JSON object.
     */
    private ObjectNode buildResponseSchema() {
        return GeminiSchemas.object()
                .prop("sceneBreaks", GeminiSchemas.arrayOf(
                        GeminiSchemas.object()
                                .prop("startTime", GeminiSchemas.string())
                                .prop("endTime", GeminiSchemas.string())
                                .prop("description", GeminiSchemas.string())
                                .require("startTime", "endTime", "description")
                                .build()))
                .prop("adInsertionPoints", GeminiSchemas.arrayOf(
                        GeminiSchemas.object()
                                .prop("timestamp", GeminiSchemas.string())
                                .prop("priority", GeminiSchemas.integer())
                                .prop("reason", GeminiSchemas.string())
                                .require("timestamp", "priority", "reason")
                                .build()))
                .prop("videoSummary", GeminiSchemas.string())
                .require("sceneBreaks", "adInsertionPoints", "videoSummary")
                .build();
    }

    private String buildAnalysisPrompt(ShaderStyle style) {
        return """
            You are a video analysis assistant specialized in identifying optimal advertisement insertion points for retro TV-style video productions.

            Analyze this video and provide:

            1. **Scene Breaks**: Identify 5-10 natural scene transitions or major content shifts. For each, provide:
               - Start and end timestamps (format: "M:SS" or "H:MM:SS")
               - Brief description of the scene content

            2. **Ad Insertion Points**: Identify 2-5 optimal locations to insert advertisements, considering:
               - Natural pauses or transitions (avoid cutting mid-sentence or mid-action)
               - Spacing (ads should not be too close together)
               - Viewer attention patterns (after hook moments, before climax)

               For each insertion point, provide:
               - Timestamp (format: "M:SS" or "H:MM:SS")
               - Priority score (1-10, where 10 = ideal spot)
               - Brief reason why this is a good insertion point

            3. **Video Summary**: One sentence describing the overall video content for logging purposes.

            The video will be transformed with retro %s effects to look like 80s/90s TV content.
            Consider how commercial breaks worked in that era - typically every 5-8 minutes of content.

            Return ONLY the JSON object as specified in the schema.
            """.formatted(style.name());
    }

    private GeminiAnalysisResult parseAnalysisResponse(String responseText) throws IOException {
        // Gemini returns the JSON directly when structured output is in use.
        JsonNode structured = objectMapper.readTree(responseText);

        // Parse scene breaks
        List<SceneBreak> sceneBreaks = new ArrayList<>();
        JsonNode sceneBreaksNode = structured.path("sceneBreaks");
        if (sceneBreaksNode.isArray()) {
            for (JsonNode node : sceneBreaksNode) {
                sceneBreaks.add(new SceneBreak(
                        node.path("startTime").asText(),
                        node.path("endTime").asText(),
                        node.path("description").asText()
                ));
            }
        }

        // Parse ad insertion points
        List<AdInsertionPoint> adInsertionPoints = new ArrayList<>();
        JsonNode adPointsNode = structured.path("adInsertionPoints");
        if (adPointsNode.isArray()) {
            for (JsonNode node : adPointsNode) {
                adInsertionPoints.add(new AdInsertionPoint(
                        node.path("timestamp").asText(),
                        node.path("priority").asInt(),
                        node.path("reason").asText()
                ));
            }
        }

        // Sort ad insertion points by priority (highest first)
        adInsertionPoints.sort((a, b) -> Integer.compare(b.priority(), a.priority()));

        String videoSummary = structured.path("videoSummary").asText("");

        logger.info("Gemini analysis complete: {} scenes, {} ad points",
                sceneBreaks.size(), adInsertionPoints.size());

        return new GeminiAnalysisResult(sceneBreaks, adInsertionPoints, videoSummary);
    }
}
