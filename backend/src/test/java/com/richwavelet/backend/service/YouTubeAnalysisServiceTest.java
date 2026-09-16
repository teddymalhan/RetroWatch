package com.richwavelet.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richwavelet.backend.dto.VideoAnalysisResult;
import com.richwavelet.backend.dto.YouTubeMetadata;
import com.richwavelet.backend.model.VideoAnalysis;
import com.richwavelet.backend.repository.VideoAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YouTubeAnalysisServiceTest {

    private static final String YOUTUBE_METADATA_RESPONSE = """
            {"items":[{"snippet":{"title":"Test Video","description":"Test Description",\
            "categoryId":"10","tags":["retro","tv"]},"contentDetails":{"duration":"PT3M33S"}}]}
            """;

    private static final String GEMINI_ANALYSIS_RESPONSE = """
            {"categories":["entertainment"],"topics":["retro tv"],"sentiment":"positive",\
            "adBreakSuggestions":[{"timestamp":120,"priority":8,"reason":"Natural break",\
            "suggestedAdCategories":["food"]}]}
            """;

    @Mock
    private VideoAnalysisRepository videoAnalysisRepository;

    @Mock
    private GeminiClient geminiClient;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private YouTubeAnalysisService youTubeAnalysisService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(youTubeAnalysisService, "youtubeApiKey", "test-api-key");
        ReflectionTestUtils.setField(youTubeAnalysisService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(youTubeAnalysisService, "restTemplate", restTemplate);
    }

    @Test
    void testExtractVideoId_StandardUrl() {
        String videoId = youTubeAnalysisService.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", videoId);
    }

    @Test
    void testExtractVideoId_ShortUrl() {
        String videoId = youTubeAnalysisService.extractVideoId("https://youtu.be/dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", videoId);
    }

    @Test
    void testExtractVideoId_EmbedUrl() {
        String videoId = youTubeAnalysisService.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ");
        assertEquals("dQw4w9WgXcQ", videoId);
    }

    @Test
    void testExtractVideoId_WithAdditionalParams() {
        String videoId = youTubeAnalysisService.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s");
        assertEquals("dQw4w9WgXcQ", videoId);
    }

    @Test
    void testExtractVideoId_InvalidUrl() {
        assertThrows(IllegalArgumentException.class, () -> {
            youTubeAnalysisService.extractVideoId("https://www.example.com/video");
        });
    }

    @Test
    void testExtractVideoId_NullUrl() {
        assertThrows(IllegalArgumentException.class, () -> {
            youTubeAnalysisService.extractVideoId(null);
        });
    }

    @Test
    void testFetchMetadata_Success() throws Exception {
        String videoId = "dQw4w9WgXcQ";

        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(YOUTUBE_METADATA_RESPONSE);

        YouTubeMetadata metadata = youTubeAnalysisService.fetchMetadata(videoId);

        assertNotNull(metadata);
        assertEquals(videoId, metadata.videoId());
        assertEquals("Test Video", metadata.title());
        assertEquals(213, metadata.durationSeconds());
    }

    @Test
    void testFetchMetadata_EmptyItemsFails() {
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("{\"items\":[]}");

        assertThrows(java.io.IOException.class, () -> youTubeAnalysisService.fetchMetadata("dQw4w9WgXcQ"));
    }

    @Test
    void testAnalyze_NewVideo() throws Exception {
        String youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        String videoId = "dQw4w9WgXcQ";

        // Mock repository - no existing analysis
        when(videoAnalysisRepository.findByVideoId(videoId)).thenReturn(Optional.empty());

        // Mock that repository will save the entity
        when(videoAnalysisRepository.save(any(VideoAnalysis.class))).thenAnswer(invocation -> {
            VideoAnalysis va = invocation.getArgument(0);
            ReflectionTestUtils.setField(va, "id", 1L);
            return va;
        });

        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(YOUTUBE_METADATA_RESPONSE);
        when(geminiClient.generateJson(anyString(), any())).thenReturn(GEMINI_ANALYSIS_RESPONSE);

        VideoAnalysisResult result = youTubeAnalysisService.analyze(youtubeUrl);

        assertNotNull(result);
        assertEquals(videoId, result.videoId());
        assertEquals(youtubeUrl, result.youtubeUrl());
        assertEquals(List.of("entertainment"), result.categories());
        assertEquals(1, result.adBreakSuggestions().size());

        // Verify that we saved the analysis
        verify(videoAnalysisRepository, times(1)).save(any(VideoAnalysis.class));
    }

    @Test
    void testAnalyze_CachedVideo() throws Exception {
        String youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        String videoId = "dQw4w9WgXcQ";

        // Mock existing analysis in database
        VideoAnalysis existingAnalysis = new VideoAnalysis(videoId, youtubeUrl);
        existingAnalysis.setTitle("Test Video");
        existingAnalysis.setDescription("Test Description");
        existingAnalysis.setDurationSeconds(213);
        existingAnalysis.setCategories(List.of("Music", "Entertainment"));
        existingAnalysis.setTopics(List.of("80s music", "pop"));
        existingAnalysis.setSentiment("positive");
        existingAnalysis.setAdBreakSuggestions("[]");

        when(videoAnalysisRepository.findByVideoId(videoId)).thenReturn(Optional.of(existingAnalysis));

        VideoAnalysisResult result = youTubeAnalysisService.analyze(youtubeUrl);

        assertNotNull(result);
        assertEquals(videoId, result.videoId());
        assertEquals("Test Video", result.title());
        assertEquals(213, result.durationSeconds());

        // Verify that we did NOT save (used cached version)
        verify(videoAnalysisRepository, never()).save(any(VideoAnalysis.class));
    }

    @Test
    void testAnalyze_InvalidUrl() {
        String invalidUrl = "https://www.example.com/video";

        assertThrows(IllegalArgumentException.class, () -> {
            youTubeAnalysisService.analyze(invalidUrl);
        });
    }

    @Test
    void testGetCachedAnalysis_Exists() {
        String videoId = "dQw4w9WgXcQ";

        VideoAnalysis cachedAnalysis = new VideoAnalysis(videoId, "https://youtube.com/watch?v=" + videoId);
        cachedAnalysis.setTitle("Cached Video");

        when(videoAnalysisRepository.findByVideoId(videoId)).thenReturn(Optional.of(cachedAnalysis));

        Optional<VideoAnalysisResult> result = youTubeAnalysisService.getCachedAnalysis(videoId);

        assertTrue(result.isPresent());
        assertEquals("Cached Video", result.get().title());
    }

    @Test
    void testGetCachedAnalysis_NotExists() {
        String videoId = "nonexistent";

        when(videoAnalysisRepository.findByVideoId(videoId)).thenReturn(Optional.empty());

        Optional<VideoAnalysisResult> result = youTubeAnalysisService.getCachedAnalysis(videoId);

        assertFalse(result.isPresent());
    }

    @Test
    void testBuildGeminiPromptForVideoAnalysis() {
        String title = "Amazing Tech Review";
        String description = "In this video, we review the latest gadgets";
        int durationSeconds = 600;

        String prompt = (String) ReflectionTestUtils.invokeMethod(
            youTubeAnalysisService,
            "buildGeminiPromptForVideoAnalysis",
            title,
            description,
            durationSeconds
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains(title));
        assertTrue(prompt.contains(description));
        assertTrue(prompt.contains("categories"));
        assertTrue(prompt.contains("adBreakSuggestions"));
    }

    @Test
    void testConvertToDto() {
        VideoAnalysis entity = new VideoAnalysis("test123", "https://youtube.com/watch?v=test123");
        entity.setTitle("Test Video");
        entity.setDescription("Test Description");
        entity.setDurationSeconds(300);
        entity.setCategories(List.of("Technology", "Education"));
        entity.setTopics(List.of("programming", "tutorial"));
        entity.setSentiment("positive");
        entity.setAdBreakSuggestions("[{\"timestamp\":120,\"priority\":8,\"reason\":\"Natural break\",\"suggestedAdCategories\":[\"technology\"]}]");

        VideoAnalysisResult dto = (VideoAnalysisResult) ReflectionTestUtils.invokeMethod(
            youTubeAnalysisService,
            "convertToDto",
            entity
        );

        assertNotNull(dto);
        assertEquals("test123", dto.videoId());
        assertEquals("Test Video", dto.title());
        assertEquals(300, dto.durationSeconds());
        assertEquals(2, dto.categories().size());
        assertEquals(1, dto.adBreakSuggestions().size());
        assertEquals(120, dto.adBreakSuggestions().get(0).timestamp());
    }
}
