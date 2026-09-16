package com.richwavelet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richwavelet.backend.dto.AdBreakSuggestion;
import com.richwavelet.backend.dto.VideoAnalysisResult;
import com.richwavelet.backend.dto.YouTubeMetadata;
import com.richwavelet.backend.model.VideoAnalysis;
import com.richwavelet.backend.repository.VideoAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class YouTubeAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeAnalysisService.class);

    // YouTube URL patterns
    private static final Pattern YOUTUBE_PATTERN_1 = Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})");
    private static final Pattern YOUTUBE_PATTERN_2 = Pattern.compile("youtube\\.com/embed/([a-zA-Z0-9_-]{11})");

    @Value("${youtube.api.key}")
    private String youtubeApiKey;

    private final VideoAnalysisRepository videoAnalysisRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final GeminiClient geminiClient;

    public YouTubeAnalysisService(VideoAnalysisRepository videoAnalysisRepository, GeminiClient geminiClient) {
        this.videoAnalysisRepository = videoAnalysisRepository;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = new RestTemplate();
        this.geminiClient = geminiClient;
    }

    /**
     * Extract video ID from various YouTube URL formats
     */
    public String extractVideoId(String youtubeUrl) {
        if (youtubeUrl == null || youtubeUrl.isEmpty()) {
            throw new IllegalArgumentException("YouTube URL cannot be null or empty");
        }

        Matcher matcher = YOUTUBE_PATTERN_1.matcher(youtubeUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }

        matcher = YOUTUBE_PATTERN_2.matcher(youtubeUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new IllegalArgumentException("Invalid YouTube URL format: " + youtubeUrl);
    }

    /**
     * Fetch video metadata from YouTube Data API v3
     */
    public YouTubeMetadata fetchMetadata(String videoId) throws IOException {
        logger.info("Fetching YouTube metadata for video: {}", videoId);

        String url = String.format(
                "https://www.googleapis.com/youtube/v3/videos?id=%s&part=snippet,contentDetails&key=%s",
                videoId, youtubeApiKey
        );

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            JsonNode items = root.path("items");
            if (items.isEmpty()) {
                throw new IOException("Video not found: " + videoId);
            }

            JsonNode item = items.get(0);
            JsonNode snippet = item.path("snippet");
            JsonNode contentDetails = item.path("contentDetails");

            String title = snippet.path("title").asText();
            String description = snippet.path("description").asText();
            String category = snippet.path("categoryId").asText();
            String duration = contentDetails.path("duration").asText();

            // Parse ISO 8601 duration (PT#M#S format)
            int durationSeconds = parseDuration(duration);

            // Parse tags
            JsonNode tagsNode = snippet.path("tags");
            String[] tags = new String[0];
            if (tagsNode.isArray()) {
                List<String> tagList = new ArrayList<>();
                tagsNode.forEach(tag -> tagList.add(tag.asText()));
                tags = tagList.toArray(new String[0]);
            }

            logger.info("Fetched metadata for video: {} ({}s)", title, durationSeconds);

            return new YouTubeMetadata(videoId, title, description, durationSeconds, category, tags);
        } catch (Exception e) {
            logger.error("Failed to fetch YouTube metadata: {}", e.getMessage(), e);
            throw new IOException("Failed to fetch YouTube metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Parse ISO 8601 duration string (e.g., PT1M30S -> 90 seconds)
     */
    private int parseDuration(String duration) {
        Pattern pattern = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");
        Matcher matcher = pattern.matcher(duration);

        if (!matcher.matches()) {
            return 0;
        }

        int hours = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : 0;
        int minutes = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
        int seconds = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;

        return hours * 3600 + minutes * 60 + seconds;
    }

    /**
     * Analyze YouTube video using metadata and Gemini AI
     */
    public VideoAnalysisResult analyze(String youtubeUrl) throws IOException {
        String videoId = extractVideoId(youtubeUrl);

        // Check if we have cached analysis
        Optional<VideoAnalysis> cached = videoAnalysisRepository.findByVideoId(videoId);
        if (cached.isPresent()) {
            logger.info("Returning cached analysis for video: {}", videoId);
            return convertToDto(cached.get());
        }

        // Fetch metadata from YouTube
        YouTubeMetadata metadata = fetchMetadata(videoId);

        // Analyze with Gemini
        VideoAnalysisResult analysis = analyzeWithGemini(metadata);

        // Save to database
        VideoAnalysis entity = new VideoAnalysis(videoId, youtubeUrl);
        entity.setTitle(metadata.title());
        entity.setDescription(metadata.description());
        entity.setDurationSeconds(metadata.durationSeconds());
        entity.setCategories(analysis.categories());
        entity.setTopics(analysis.topics());
        entity.setSentiment(analysis.sentiment());
        entity.setAdBreakSuggestions(serializeAdBreakSuggestions(analysis.adBreakSuggestions()));

        videoAnalysisRepository.save(entity);

        logger.info("Saved analysis for video: {}", videoId);

        return analysis;
    }

    /**
     * Get cached analysis if available
     */
    public Optional<VideoAnalysisResult> getCachedAnalysis(String videoId) {
        return videoAnalysisRepository.findByVideoId(videoId)
                .map(this::convertToDto);
    }

    /**
     * Analyze video content using Gemini AI
     */
    private VideoAnalysisResult analyzeWithGemini(YouTubeMetadata metadata) throws IOException {
        logger.info("Analyzing video with Gemini: {}", metadata.title());

        String prompt = buildGeminiPromptForVideoAnalysis(
                metadata.title(),
                metadata.description(),
                metadata.durationSeconds()
        );

        String responseText;
        try {
            responseText = geminiClient.generateJson(prompt, buildVideoAnalysisSchema());
        } catch (Exception e) {
            logger.error("Gemini analysis failed: {}", e.getMessage(), e);
            throw new IOException("Gemini analysis failed: " + e.getMessage(), e);
        }

        return parseGeminiResponse(metadata, responseText);
    }

    /**
     * Build the response schema for video analysis
     */
    private ObjectNode buildVideoAnalysisSchema() {
        return GeminiSchemas.object()
                .prop("categories", GeminiSchemas.stringArray())
                .prop("topics", GeminiSchemas.stringArray())
                .prop("sentiment", GeminiSchemas.string())
                .prop("adBreakSuggestions", GeminiSchemas.arrayOf(
                        GeminiSchemas.object()
                                .prop("timestamp", GeminiSchemas.integer())
                                .prop("priority", GeminiSchemas.integer())
                                .prop("reason", GeminiSchemas.string())
                                .prop("suggestedAdCategories", GeminiSchemas.stringArray())
                                .require("timestamp", "priority", "reason", "suggestedAdCategories")
                                .build()))
                .require("categories", "topics", "sentiment", "adBreakSuggestions")
                .build();
    }

    /**
     * Build Gemini prompt for video analysis
     */
    private String buildGeminiPromptForVideoAnalysis(String title, String description, int durationSeconds) {
        return String.format("""
            Analyze this YouTube video metadata to suggest ad break points.

            Title: %s
            Description: %s
            Duration: %d seconds

            Return JSON:

            {
              "categories": ["content_category1", "content_category2"],
              "topics": ["main_topic1", "main_topic2"],
              "sentiment": "positive" | "negative" | "neutral" | "mixed",
              "adBreakSuggestions": [
                {
                  "timestamp": seconds,
                  "priority": 1-10,
                  "reason": "Why this is a good break point",
                  "suggestedAdCategories": ["matching_category1"]
                }
              ]
            }

            Suggest breaks at natural topic transitions. For a %d second video,
            suggest approximately %d break points (one per 3 minutes).
            Avoid breaks in the first 30 seconds or last 30 seconds.

            Categories: automotive, food, beverage, technology, fashion, home, health,
            entertainment, finance, travel, education, retail, sports, gaming, beauty, pets, kids, business
            """, title, description, durationSeconds, durationSeconds, durationSeconds / 180);
    }

    /**
     * Parse Gemini response into VideoAnalysisResult
     */
    private VideoAnalysisResult parseGeminiResponse(YouTubeMetadata metadata, String responseText) throws IOException {
        JsonNode root = objectMapper.readTree(responseText);

        // Parse categories
        List<String> categories = new ArrayList<>();
        JsonNode categoriesNode = root.path("categories");
        if (categoriesNode.isArray()) {
            categoriesNode.forEach(node -> categories.add(node.asText()));
        }

        // Parse topics
        List<String> topics = new ArrayList<>();
        JsonNode topicsNode = root.path("topics");
        if (topicsNode.isArray()) {
            topicsNode.forEach(node -> topics.add(node.asText()));
        }

        String sentiment = root.path("sentiment").asText("neutral");

        // Parse ad break suggestions
        List<AdBreakSuggestion> adBreakSuggestions = new ArrayList<>();
        JsonNode suggestionsNode = root.path("adBreakSuggestions");
        if (suggestionsNode.isArray()) {
            for (JsonNode node : suggestionsNode) {
                List<String> suggestedCategories = new ArrayList<>();
                JsonNode categoriesArray = node.path("suggestedAdCategories");
                if (categoriesArray.isArray()) {
                    categoriesArray.forEach(cat -> suggestedCategories.add(cat.asText()));
                }

                adBreakSuggestions.add(new AdBreakSuggestion(
                        node.path("timestamp").asInt(),
                        node.path("reason").asText(),
                        node.path("priority").asInt(),
                        suggestedCategories
                ));
            }
        }

        return new VideoAnalysisResult(
                metadata.videoId(),
                "https://www.youtube.com/watch?v=" + metadata.videoId(),
                metadata.title(),
                metadata.description(),
                metadata.durationSeconds(),
                categories,
                topics,
                sentiment,
                adBreakSuggestions
        );
    }

    /**
     * Convert VideoAnalysis entity to DTO
     */
    private VideoAnalysisResult convertToDto(VideoAnalysis entity) {
        List<AdBreakSuggestion> suggestions = deserializeAdBreakSuggestions(entity.getAdBreakSuggestions());

        return new VideoAnalysisResult(
                entity.getVideoId(),
                entity.getYoutubeUrl(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getDurationSeconds() != null ? entity.getDurationSeconds() : 0,
                entity.getCategories() != null ? entity.getCategories() : List.of(),
                entity.getTopics() != null ? entity.getTopics() : List.of(),
                entity.getSentiment(),
                suggestions
        );
    }

    /**
     * Serialize ad break suggestions to JSON string
     */
    private String serializeAdBreakSuggestions(List<AdBreakSuggestion> suggestions) {
        try {
            return objectMapper.writeValueAsString(suggestions);
        } catch (Exception e) {
            logger.error("Failed to serialize ad break suggestions", e);
            return "[]";
        }
    }

    /**
     * Deserialize ad break suggestions from JSON string
     */
    private List<AdBreakSuggestion> deserializeAdBreakSuggestions(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            List<AdBreakSuggestion> suggestions = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode node : root) {
                    List<String> categories = new ArrayList<>();
                    JsonNode categoriesNode = node.path("suggestedAdCategories");
                    if (categoriesNode.isArray()) {
                        categoriesNode.forEach(cat -> categories.add(cat.asText()));
                    }

                    suggestions.add(new AdBreakSuggestion(
                            node.path("timestamp").asInt(),
                            node.path("reason").asText(),
                            node.path("priority").asInt(),
                            categories
                    ));
                }
            }

            return suggestions;
        } catch (Exception e) {
            logger.error("Failed to deserialize ad break suggestions", e);
            return List.of();
        }
    }
}