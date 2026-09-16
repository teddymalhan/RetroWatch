package com.richwavelet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GeminiClientTest {

    private GeminiClient geminiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        geminiClient = new GeminiClient();
        ReflectionTestUtils.setField(geminiClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(geminiClient, "model", "gemini-2.0-flash");
        ReflectionTestUtils.setField(geminiClient, "configuredBaseUrl", "");
        ReflectionTestUtils.setField(geminiClient, "structuredOutput", true);
    }

    @Test
    void testBuildRequestBody_IncludesInlineMediaAndPrompt() {
        ObjectNode schema = GeminiSchemas.object()
                .prop("summary", GeminiSchemas.string())
                .require("summary")
                .build();

        ObjectNode body = (ObjectNode) ReflectionTestUtils.invokeMethod(
                geminiClient, "buildRequestBody", "describe this", "video bytes".getBytes(), "video/mp4", schema);

        assertNotNull(body);
        JsonNode parts = body.path("contents").get(0).path("parts");
        assertEquals(2, parts.size());
        assertEquals("video/mp4", parts.get(0).path("inline_data").path("mime_type").asText());
        assertEquals(Base64.getEncoder().encodeToString("video bytes".getBytes()),
                parts.get(0).path("inline_data").path("data").asText());
        assertEquals("describe this", parts.get(1).path("text").asText());
        assertEquals("application/json", body.path("generationConfig").path("responseMimeType").asText());
        assertTrue(body.path("generationConfig").has("responseSchema"));
    }

    @Test
    void testBuildRequestBody_TextOnlyOmitsMedia() {
        ObjectNode body = (ObjectNode) ReflectionTestUtils.invokeMethod(
                geminiClient, "buildRequestBody", "just text", null, null, null);

        JsonNode parts = body.path("contents").get(0).path("parts");
        assertEquals(1, parts.size());
        assertEquals("just text", parts.get(0).path("text").asText());
    }

    @Test
    void testGenerateJson_WithoutApiKeyFails() {
        ReflectionTestUtils.setField(geminiClient, "apiKey", "");

        IOException thrown = assertThrows(IOException.class,
                () -> geminiClient.generateJson("hi", null));

        assertTrue(thrown.getMessage().contains("API key"), thrown.getMessage());
    }

    @Test
    void testExtractText_UnwrapsCandidateParts() throws Exception {
        String response = """
            {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":1}"}]},"finishReason":"STOP"}]}
            """;

        String text = (String) ReflectionTestUtils.invokeMethod(geminiClient, "extractText", response);

        assertEquals("{\"a\":1}", text);
        assertEquals(1, objectMapper.readTree(text).path("a").asInt());
    }

    @Test
    void testExtractText_ReportsBlockedPrompt() throws Exception {
        String response = """
            {"promptFeedback":{"blockReason":"SAFETY"}}
            """;

        Method extractText = GeminiClient.class.getDeclaredMethod("extractText", String.class);
        extractText.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> extractText.invoke(geminiClient, response));

        assertInstanceOf(IOException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("SAFETY"), thrown.getCause().getMessage());
    }

    @Test
    void testStripCodeFences_RemovesMarkdownWrapper() {
        String fenced = "```json\n{\"a\":1}\n```";

        assertEquals("{\"a\":1}", GeminiClient.stripCodeFences(fenced));
    }

    @Test
    void testStripCodeFences_LeavesBareJsonAlone() {
        assertEquals("{\"a\":1}", GeminiClient.stripCodeFences("{\"a\":1}"));
    }

    @Test
    void testResolveBaseUrl_StripsTrailingSlashAndDefaults() {
        assertEquals("https://generativelanguage.googleapis.com",
                ReflectionTestUtils.invokeMethod(geminiClient, "resolveBaseUrl"));

        ReflectionTestUtils.setField(geminiClient, "configuredBaseUrl", "http://litellm.internal:4000/");

        assertEquals("http://litellm.internal:4000",
                ReflectionTestUtils.invokeMethod(geminiClient, "resolveBaseUrl"));
    }
}
