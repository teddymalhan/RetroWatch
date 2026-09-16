package com.richwavelet.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richwavelet.backend.dto.AdBreakSuggestion;
import com.richwavelet.backend.dto.AnalyzeVideoRequest;
import com.richwavelet.backend.dto.VideoAnalysisResult;
import com.richwavelet.backend.service.YouTubeAnalysisService;
import com.richwavelet.backend.support.TestAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisControllerTest {

    @Mock
    private YouTubeAnalysisService youTubeAnalysisService;

    @Mock
    private Authentication authentication;

    @Mock
    private Jwt jwt;

    @InjectMocks
    private VideoAnalysisController videoAnalysisController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private String userId = "user123";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(videoAnalysisController)
                .addFilter(new TestAuthenticationFilter(authentication), "/*")
                .build();
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn(userId);
    }

    @Test
    void testAnalyzeVideo_Success() throws Exception {
        String youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        AnalyzeVideoRequest request = new AnalyzeVideoRequest(youtubeUrl);

        List<AdBreakSuggestion> suggestions = List.of(
                new AdBreakSuggestion(120, "Natural transition", 8, List.of("technology")),
                new AdBreakSuggestion(300, "Mid-point break", 6, List.of("gaming"))
        );

        VideoAnalysisResult result = new VideoAnalysisResult(
                "dQw4w9WgXcQ",
                youtubeUrl,
                "Test Video Title",
                "Test video description",
                600,
                List.of("Entertainment", "Music"),
                List.of("80s music", "pop"),
                "positive",
                suggestions
        );

        when(youTubeAnalysisService.analyze(youtubeUrl)).thenReturn(result);

        mockMvc.perform(post("/api/protected/video/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$.title").value("Test Video Title"))
                .andExpect(jsonPath("$.durationSeconds").value(600))
                .andExpect(jsonPath("$.categories[0]").value("Entertainment"))
                .andExpect(jsonPath("$.topics[0]").value("80s music"))
                .andExpect(jsonPath("$.sentiment").value("positive"))
                .andExpect(jsonPath("$.adBreakSuggestions[0].timestamp").value(120))
                .andExpect(jsonPath("$.adBreakSuggestions[0].priority").value(8));

        verify(youTubeAnalysisService, times(1)).analyze(youtubeUrl);
    }

    @Test
    void testAnalyzeVideo_InvalidUrl() throws Exception {
        String invalidUrl = "https://www.example.com/video";
        AnalyzeVideoRequest request = new AnalyzeVideoRequest(invalidUrl);

        when(youTubeAnalysisService.analyze(invalidUrl))
                .thenThrow(new IllegalArgumentException("Invalid YouTube URL format"));

        mockMvc.perform(post("/api/protected/video/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(youTubeAnalysisService, times(1)).analyze(invalidUrl);
    }

    @Test
    void testAnalyzeVideo_ServiceError() throws Exception {
        String youtubeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        AnalyzeVideoRequest request = new AnalyzeVideoRequest(youtubeUrl);

        when(youTubeAnalysisService.analyze(youtubeUrl))
                .thenThrow(new RuntimeException("YouTube API error"));

        mockMvc.perform(post("/api/protected/video/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());

        verify(youTubeAnalysisService, times(1)).analyze(youtubeUrl);
    }

    @Test
    void testGetCachedAnalysis_Found() throws Exception {
        String videoId = "dQw4w9WgXcQ";

        VideoAnalysisResult result = new VideoAnalysisResult(
                videoId,
                "https://www.youtube.com/watch?v=" + videoId,
                "Cached Video",
                "Cached description",
                300,
                List.of("Technology"),
                List.of("tutorial"),
                "neutral",
                List.of()
        );

        when(youTubeAnalysisService.getCachedAnalysis(videoId))
                .thenReturn(Optional.of(result));

        mockMvc.perform(get("/api/protected/video/analysis/" + videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(videoId))
                .andExpect(jsonPath("$.title").value("Cached Video"))
                .andExpect(jsonPath("$.durationSeconds").value(300));

        verify(youTubeAnalysisService, times(1)).getCachedAnalysis(videoId);
    }

    @Test
    void testGetCachedAnalysis_NotFound() throws Exception {
        String videoId = "nonexistent";

        when(youTubeAnalysisService.getCachedAnalysis(videoId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/protected/video/analysis/" + videoId))
                .andExpect(status().isNotFound());

        verify(youTubeAnalysisService, times(1)).getCachedAnalysis(videoId);
    }

    @Test
    void testAnalyzeVideo_NullUrl() throws Exception {
        AnalyzeVideoRequest request = new AnalyzeVideoRequest(null);

        mockMvc.perform(post("/api/protected/video/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(youTubeAnalysisService, never()).analyze(anyString());
    }

    @Test
    void testAnalyzeVideo_EmptyUrl() throws Exception {
        AnalyzeVideoRequest request = new AnalyzeVideoRequest("");

        mockMvc.perform(post("/api/protected/video/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(youTubeAnalysisService, never()).analyze(anyString());
    }
}