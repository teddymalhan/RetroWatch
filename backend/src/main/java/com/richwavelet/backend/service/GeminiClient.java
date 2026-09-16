package com.richwavelet.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;

/**
 * Minimal Gemini REST client.
 *
 * <p>Talks to the public {@code generateContent} endpoint over plain HTTPS with an API
 * key, so the application no longer needs the Vertex AI SDK, Application Default
 * Credentials, or a Google Cloud project. {@code gemini.base-url} is configurable, which
 * means the same code can point at a self-hosted Gemini-compatible gateway (LiteLLM,
 * Ollama behind a compatibility shim, vLLM, …) instead of Google's API.
 */
@Service
public class GeminiClient {

    private static final Logger logger = LoggerFactory.getLogger(GeminiClient.class);

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    @Value("${gemini.base-url:}")
    private String configuredBaseUrl;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    @Value("${gemini.timeout-seconds:600}")
    private long timeoutSeconds;

    /**
     * When true, the response schema is sent so the model returns conforming JSON. Set to
     * false for self-hosted gateways that reject {@code responseSchema}; the prompt still
     * asks for JSON and the response is unwrapped defensively either way.
     */
    @Value("${gemini.structured-output:true}")
    private boolean structuredOutput;

    public GeminiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(600))
                .writeTimeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * Generate content from a prompt plus optional inline media (video/audio/image).
     *
     * @return the model's raw text response
     */
    public String generateJson(String prompt, byte[] mediaBytes, String mediaMimeType, ObjectNode responseSchema)
            throws IOException {
        ObjectNode body = buildRequestBody(prompt, mediaBytes, mediaMimeType, responseSchema);
        return execute(body);
    }

    /**
     * Generate content from a text-only prompt.
     */
    public String generateJson(String prompt, ObjectNode responseSchema) throws IOException {
        return generateJson(prompt, null, null, responseSchema);
    }

    public String getModel() {
        return model;
    }

    private ObjectNode buildRequestBody(String prompt, byte[] mediaBytes, String mediaMimeType,
                                        ObjectNode responseSchema) {
        ObjectNode body = objectMapper.createObjectNode();

        ArrayNode parts = body.putArray("contents").addObject().put("role", "user").putArray("parts");

        if (mediaBytes != null && mediaBytes.length > 0) {
            parts.addObject()
                    .putObject("inline_data")
                    .put("mime_type", mediaMimeType == null ? "video/mp4" : mediaMimeType)
                    .put("data", Base64.getEncoder().encodeToString(mediaBytes));
        }

        parts.addObject().put("text", prompt);

        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        if (structuredOutput && responseSchema != null) {
            generationConfig.set("responseSchema", responseSchema);
        }

        return body;
    }

    private String execute(ObjectNode body) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IOException("Gemini API key is not configured. Set GEMINI_API_KEY "
                    + "(or point gemini.base-url at a self-hosted gateway that does not need one).");
        }

        String url = resolveBaseUrl() + "/v1beta/models/" + model + ":generateContent";

        try {
            return call(url, body);
        } catch (SchemaRejectedException e) {
            // The endpoint does not understand responseSchema. Retry once without it — the
            // prompt already asks for JSON, so a compatible gateway still returns JSON.
            logger.warn("Gemini endpoint rejected responseSchema; retrying without structured output: {}", e.getMessage());
            removeSchema(body);
            return call(url, body);
        }
    }

    private String call(String url, ObjectNode body) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .build();

        OkHttpClient client = httpClient.newBuilder()
                .readTimeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                .build();

        logger.debug("Calling Gemini model {} at {}", model, url);

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                String message = "Gemini request failed with HTTP " + response.code() + ": " + truncate(responseBody);
                if (response.code() == 400 && mentionsSchema(responseBody)) {
                    throw new SchemaRejectedException(message);
                }
                throw new IOException(message);
            }

            return extractText(responseBody);
        }
    }

    private String extractText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");

        if (candidates.isEmpty()) {
            JsonNode blockReason = root.path("promptFeedback").path("blockReason");
            if (!blockReason.isMissingNode() && !blockReason.isNull()) {
                throw new IOException("Gemini blocked the request: " + blockReason.asText());
            }
            throw new IOException("Gemini returned no candidates: " + truncate(responseBody));
        }

        JsonNode candidate = candidates.get(0);
        String finishReason = candidate.path("finishReason").asText("");
        if (!finishReason.isBlank() && !"STOP".equals(finishReason)) {
            logger.warn("Gemini finished with reason {} (response may be incomplete)", finishReason);
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            }
        }

        if (text.isEmpty()) {
            throw new IOException("Gemini response contained no text parts: " + truncate(responseBody));
        }

        return stripCodeFences(text.toString());
    }

    /**
     * Some self-hosted gateways wrap JSON answers in markdown fences despite the mime type
     * request. Strip them so downstream parsing sees bare JSON.
     */
    static String stripCodeFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }

        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private static boolean mentionsSchema(String responseBody) {
        String lower = responseBody == null ? "" : responseBody.toLowerCase();
        return lower.contains("responseschema") || lower.contains("response_schema");
    }

    private static void removeSchema(ObjectNode body) {
        JsonNode generationConfig = body.path("generationConfig");
        if (generationConfig instanceof ObjectNode config) {
            config.remove("responseSchema");
        }
    }

    private String resolveBaseUrl() {
        String baseUrl = (configuredBaseUrl == null || configuredBaseUrl.isBlank())
                ? DEFAULT_BASE_URL
                : configuredBaseUrl.trim();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 800 ? value : value.substring(0, 800) + "...";
    }

    /** Signals a schema-less retry is worth attempting. */
    private static final class SchemaRejectedException extends IOException {
        SchemaRejectedException(String message) {
            super(message);
        }
    }
}
