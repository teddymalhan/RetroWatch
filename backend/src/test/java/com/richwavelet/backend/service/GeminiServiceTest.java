package com.richwavelet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richwavelet.backend.dto.AdInsertionPoint;
import com.richwavelet.backend.dto.GeminiAnalysisResult;
import com.richwavelet.backend.model.ShaderStyle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    @Mock
    private GeminiClient geminiClient;

    private GeminiService geminiService;

    private Path tempVideoFile;

    @BeforeEach
    void setUp() throws Exception {
        geminiService = new GeminiService(geminiClient);
        ReflectionTestUtils.setField(geminiService, "inlineMaxBytes", 20L * 1024 * 1024);

        tempVideoFile = Files.createTempFile("test-video", ".mp4");
        Files.write(tempVideoFile, "test video content".getBytes());
    }

    @Test
    void testBuildAnalysisPrompt_CRT() {
        String prompt = (String) ReflectionTestUtils.invokeMethod(
                geminiService, "buildAnalysisPrompt", ShaderStyle.CRT
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains("CRT"));
        assertTrue(prompt.contains("Scene Breaks"));
        assertTrue(prompt.contains("Ad Insertion Points"));
    }

    @Test
    void testBuildAnalysisPrompt_VHS() {
        String prompt = (String) ReflectionTestUtils.invokeMethod(
                geminiService, "buildAnalysisPrompt", ShaderStyle.VHS
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains("VHS"));
    }

    @Test
    void testBuildAnalysisPrompt_ARCADE() {
        String prompt = (String) ReflectionTestUtils.invokeMethod(
                geminiService, "buildAnalysisPrompt", ShaderStyle.ARCADE
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains("ARCADE"));
    }

    @Test
    void testBuildResponseSchema() {
        Object schema = ReflectionTestUtils.invokeMethod(geminiService, "buildResponseSchema");

        assertNotNull(schema);
        JsonNode node = (JsonNode) schema;
        assertEquals("OBJECT", node.path("type").asText());
        assertEquals("ARRAY", node.path("properties").path("sceneBreaks").path("type").asText());
        assertEquals("STRING",
                node.path("properties").path("adInsertionPoints").path("items")
                        .path("properties").path("timestamp").path("type").asText());
        assertEquals(3, node.path("required").size());
    }

    @Test
    void testEncodeVideo_ReturnsBase64() throws Exception {
        String encoded = geminiService.encodeVideo(tempVideoFile, "test.mp4");

        assertNotNull(encoded);
        assertEquals("test video content", new String(java.util.Base64.getDecoder().decode(encoded)));
    }

    @Test
    void testEncodeVideo_RejectsOversizedVideo() throws Exception {
        ReflectionTestUtils.setField(geminiService, "inlineMaxBytes", 4L);

        IOException thrown = assertThrows(IOException.class,
                () -> geminiService.encodeVideo(tempVideoFile, "test.mp4"));

        assertTrue(thrown.getMessage().contains("inline limit"), thrown.getMessage());
    }

    @Test
    void testAnalyzeVideo_DelegatesToClient() throws Exception {
        String modelResponse = """
            {"sceneBreaks":[{"startTime":"0:00","endTime":"1:30","description":"Opening scene"}],\
            "adInsertionPoints":[{"timestamp":"1:30","priority":8,"reason":"Natural transition"}],\
            "videoSummary":"Test video"}
            """;
        when(geminiClient.generateJson(anyString(), any(byte[].class), eq("video/mp4"), any()))
                .thenReturn(modelResponse);

        GeminiAnalysisResult result = geminiService.analyzeVideo("dGVzdA==", ShaderStyle.CRT);

        assertEquals(1, result.sceneBreaks().size());
        assertEquals(1, result.adInsertionPoints().size());
        assertEquals("Test video", result.videoSummary());
    }

    @Test
    void testParseAnalysisResponse() throws Exception {
        String responseJson = """
            {"sceneBreaks":[{"startTime":"0:00","endTime":"1:30","description":"Opening scene"}],\
            "adInsertionPoints":[{"timestamp":"1:30","priority":8,"reason":"Natural transition"}],\
            "videoSummary":"Test video"}
            """;

        GeminiAnalysisResult result = (GeminiAnalysisResult) ReflectionTestUtils.invokeMethod(
                geminiService, "parseAnalysisResponse", responseJson
        );

        assertNotNull(result);
        assertEquals(1, result.sceneBreaks().size());
        assertEquals(1, result.adInsertionPoints().size());
        assertEquals("Test video", result.videoSummary());
        assertEquals("0:00", result.sceneBreaks().get(0).startTime());
        assertEquals("1:30", result.adInsertionPoints().get(0).timestamp());
        assertEquals(8, result.adInsertionPoints().get(0).priority());
    }

    @Test
    void testParseAnalysisResponse_MultipleScenes() throws Exception {
        String responseJson = """
            {"sceneBreaks":[{"startTime":"0:00","endTime":"1:30","description":"Scene 1"},\
            {"startTime":"1:30","endTime":"3:00","description":"Scene 2"}],\
            "adInsertionPoints":[{"timestamp":"1:30","priority":9,"reason":"Good spot"}],\
            "videoSummary":"Multi-scene video"}
            """;

        GeminiAnalysisResult result = (GeminiAnalysisResult) ReflectionTestUtils.invokeMethod(
                geminiService, "parseAnalysisResponse", responseJson
        );

        assertNotNull(result);
        assertEquals(2, result.sceneBreaks().size());
        assertEquals(1, result.adInsertionPoints().size());
    }

    @Test
    void testParseAnalysisResponse_SortedByPriority() throws Exception {
        String responseJson = """
            {"sceneBreaks":[],"adInsertionPoints":[\
            {"timestamp":"2:00","priority":5,"reason":"Low"},\
            {"timestamp":"1:00","priority":9,"reason":"High"},\
            {"timestamp":"3:00","priority":7,"reason":"Medium"}],\
            "videoSummary":"Test"}
            """;

        GeminiAnalysisResult result = (GeminiAnalysisResult) ReflectionTestUtils.invokeMethod(
                geminiService, "parseAnalysisResponse", responseJson
        );

        assertNotNull(result);
        List<AdInsertionPoint> points = result.adInsertionPoints();
        assertEquals(3, points.size());
        // Should be sorted by priority descending
        assertEquals(9, points.get(0).priority());
        assertEquals(7, points.get(1).priority());
        assertEquals(5, points.get(2).priority());
    }

    @Test
    void testParseAnalysisResponse_ToleratesUnknownFields() throws Exception {
        String responseJson = """
            {"sceneBreaks":[],"adInsertionPoints":[],"videoSummary":"","extra":"ignored"}
            """;

        GeminiAnalysisResult result = (GeminiAnalysisResult) ReflectionTestUtils.invokeMethod(
                geminiService, "parseAnalysisResponse", responseJson
        );

        assertNotNull(result);
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(mapper.readTree(responseJson).has("extra"));
        assertEquals("", result.videoSummary());
    }
}
