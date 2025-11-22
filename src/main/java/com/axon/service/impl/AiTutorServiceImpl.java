package com.axon.service.impl;

import com.axon.model.LearningModule;
import com.axon.service.api.AiTutorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;

/**
 * Default implementation of the {@link AiTutorService} communicating with an OpenAI-compatible API.
 * <p>
 * This service manages the lifecycle of AI requests, including payload construction,
 * HTTP execution, and the parsing of both structured (JSON) and unstructured (Text) responses.
 * It is currently configured to use the Qwen model via the Fireworks AI provider.
 * </p>
 */
@Service
public class AiTutorServiceImpl implements AiTutorService {

    private static final Logger log = LoggerFactory.getLogger(AiTutorServiceImpl.class);

    private static final String MODEL_NAME = "accounts/fireworks/models/qwen3-coder-30b-a3b-instruct";
    private static final MediaType MEDIA_TYPE_JSON = MediaType.get("application/json; charset=utf-8");
    private static final long TIMEOUT_SECONDS = 60;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiToken;

    /**
     * Initializes the AI service with the necessary HTTP client configuration and API credentials.
     *
     * @param objectMapper The Jackson mapper used for serializing requests and deserializing responses.
     * @param apiUrl       The target URL for the AI provider's chat completion endpoint.
     * @param apiToken     The authorization token (Bearer token) for the API.
     */
    public AiTutorServiceImpl(ObjectMapper objectMapper,
                              @Value("${app.ai.api-url}") String apiUrl,
                              @Value("${app.fireworks.api-key}") String apiToken) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiToken = apiToken;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .writeTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation enforces a strict extraction logic to isolate JSON content
     * from potentially conversational AI responses (e.g., Markdown code blocks).
     * </p>
     */
    @Override
    public LearningModule generateModuleFromPrompt(String prompt, int maxTokens) {
        log.info("Generating learning module with max tokens: {}", maxTokens);

        var rawApiResponse = executeAiQuery(prompt, maxTokens, 0.0);
        var cleanJson = extractJson(rawApiResponse);

        try {
            return objectMapper.readValue(cleanJson, LearningModule.class);
        } catch (JsonProcessingException e) {
            log.error("JSON Parsing failed. \nInput JSON: {}\nError: {}", cleanJson, e.getMessage());
            throw new RuntimeException("Failed to parse AI module response. The AI did not return valid JSON.", e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Uses a slightly higher temperature (0.1) to allow for more natural, conversational phrasing
     * while maintaining technical accuracy.
     * </p>
     */
    @Override
    public String answerQuestionFromPrompt(String prompt, int maxTokens) {
        log.debug("Asking AI question with max tokens: {}", maxTokens);
        return executeAiQuery(prompt, maxTokens, 0.1);
    }

    /**
     * Executes the HTTP POST request to the AI provider.
     *
     * @param prompt      The input string to send to the model.
     * @param maxTokens   The hard limit on the number of tokens the model generates.
     * @param temperature Controls randomness: 0.0 for deterministic output, higher for creative output.
     * @return The raw content string from the AI's response message.
     * @throws RuntimeException If the HTTP request fails or the response cannot be parsed.
     */
    private String executeAiQuery(String prompt, int maxTokens, double temperature) {
        try {
            var payload = createPayload(prompt, maxTokens, temperature);
            var requestBodyJson = objectMapper.writeValueAsString(payload);

            var body = RequestBody.create(requestBodyJson, MEDIA_TYPE_JSON);
            var request = new Request.Builder()
                    .url(this.apiUrl)
                    .header("Authorization", "Bearer " + this.apiToken)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                var responseBody = response.body();
                var responseString = responseBody != null ? responseBody.string() : "";

                if (!response.isSuccessful()) {
                    log.error("AI API Error. Code: {}, Body: {}", response.code(), responseString);
                    throw new RuntimeException("API call failed with code " + response.code());
                }

                JsonNode responseNode = objectMapper.readTree(responseString);
                return responseNode.path("choices")
                        .get(0)
                        .path("message")
                        .path("content")
                        .asText();
            }
        } catch (IOException e) {
            log.error("Network or I/O error during AI execution: {}", e.getMessage());
            throw new RuntimeException("Could not get a response from the AI: " + e.getMessage(), e);
        }
    }

    /**
     * Constructs the specific JSON structure required by the Chat Completion API.
     *
     * @param prompt      The user instruction.
     * @param maxTokens   Token generation limit.
     * @param temperature Randomness setting.
     * @return An {@link ObjectNode} representing the request body.
     */
    private ObjectNode createPayload(String prompt, int maxTokens, double temperature) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", MODEL_NAME);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        payload.set("messages", messages);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", temperature);

        return payload;
    }

    /**
     * Locates and extracts the JSON substring from a raw string.
     * <p>
     * LLMs often wrap JSON output in Markdown blocks (e.g., ```json ... ```) or include
     * conversational preambles. This method finds the first opening brace and the last
     * closing brace to isolate the valid JSON object.
     * </p>
     *
     * @param text The raw string returned by the AI.
     * @return The substring containing only the JSON object.
     * @throws RuntimeException If no valid JSON structure (matching curly braces) is found.
     */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new RuntimeException("AI returned empty response.");
        }

        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');

        if (firstBrace != -1 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }

        log.error("Invalid JSON format received from AI: {}", text);
        throw new RuntimeException("Could not find valid JSON object ({} pattern) in the AI output.");
    }
}