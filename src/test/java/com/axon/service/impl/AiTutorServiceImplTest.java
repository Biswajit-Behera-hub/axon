package com.axon.service.impl;

import com.axon.model.LearningModule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AiTutorServiceImpl}.
 * <p>
 * Uses {@link MockWebServer} to simulate the external AI API, ensuring full coverage
 * of HTTP networking, payload construction, and response parsing logic.
 * </p>
 */
class AiTutorServiceImplTest {

    private MockWebServer mockWebServer;
    private AiTutorServiceImpl aiTutorService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        // Spin up a local mock server
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Initialize service pointing to localhost mock server
        String baseUrl = mockWebServer.url("/").toString();
        String fakeApiKey = "test-api-key";

        aiTutorService = new AiTutorServiceImpl(objectMapper, baseUrl, fakeApiKey);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("generateModuleFromPrompt should parse valid JSON embedded in Markdown")
    void testGenerateModuleSuccess() throws InterruptedException, JsonProcessingException {
        // Arrange
        String innerJson = """
            {
                "moduleName": "Git Basics",
                "lessons": []
            }
            """;

        // Simulating how LLMs often wrap code in markdown
        String aiResponseContent = "Here is the JSON:\n```json\n" + innerJson + "\n```";

        mockWebServer.enqueue(createMockResponse(200, aiResponseContent));

        // Act
        LearningModule result = aiTutorService.generateModuleFromPrompt("Create module", 100);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.moduleName()).isEqualTo("Git Basics");

        // Verify the Request sent to AI
        var recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer test-api-key");

        var requestBody = objectMapper.readTree(recordedRequest.getBody().readUtf8());
        assertThat(requestBody.get("model").asText()).contains("qwen");
        assertThat(requestBody.get("max_tokens").asInt()).isEqualTo(100);
        assertThat(requestBody.get("temperature").asDouble()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("answerQuestionFromPrompt should return raw text response")
    void testAnswerQuestionSuccess() {
        // Arrange
        String aiAnswer = "To list files in Git, use `git ls-files`.";
        mockWebServer.enqueue(createMockResponse(200, aiAnswer));

        // Act
        String result = aiTutorService.answerQuestionFromPrompt("How to list files?", 50);

        // Assert
        assertThat(result).isEqualTo(aiAnswer);
    }

    @Test
    @DisplayName("Should throw RuntimeException when API returns 500 error")
    void testApiError() {
        // Arrange
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        // Act & Assert
        assertThatThrownBy(() -> aiTutorService.answerQuestionFromPrompt("Hi", 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("API call failed with code 500");
    }

    @Test
    @DisplayName("Should throw RuntimeException when AI returns invalid JSON")
    void testInvalidJsonFromAi() {
        // Arrange
        // AI refuses to generate JSON or returns plain text without braces
        String aiResponseContent = "I cannot generate a curriculum for that topic.";
        mockWebServer.enqueue(createMockResponse(200, aiResponseContent));

        // Act & Assert
        assertThatThrownBy(() -> aiTutorService.generateModuleFromPrompt("Bad prompt", 100))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not find valid JSON object");
    }

    @Test
    @DisplayName("Should throw RuntimeException when JSON structure is malformed")
    void testMalformedJson() {
        // Arrange
        // JSON has braces but is syntactically invalid (missing quote)
        String malformedJson = "{ \"moduleName\": Git Basics }";
        mockWebServer.enqueue(createMockResponse(200, malformedJson));

        // Act & Assert
        assertThatThrownBy(() -> aiTutorService.generateModuleFromPrompt("Prompt", 100))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse AI module response");
    }

    @Test
    @DisplayName("Should handle network timeouts/failures")
    void testNetworkFailure() {
        // Arrange
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        // Act & Assert
        assertThatThrownBy(() -> aiTutorService.answerQuestionFromPrompt("Hi", 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Could not get a response from the AI");
    }

    @Test
    @DisplayName("extractJson should find JSON amidst conversational text")
    void testComplexJsonExtraction() throws JsonProcessingException {
        // Arrange
        String innerJson = """
            {
                "moduleName": "Hidden",
                "lessons": []
            }
            """;
        String complexResponse = """
                Sure! Here is your output.
                
                Note: This is a complex topic.
                
                {
                    "moduleName": "Hidden",
                    "lessons": []
                }
                
                Let me know if you need anything else!
                """;

        mockWebServer.enqueue(createMockResponse(200, complexResponse));

        // Act
        LearningModule result = aiTutorService.generateModuleFromPrompt("test", 100);

        // Assert
        assertThat(result.moduleName()).isEqualTo("Hidden");
    }

    // --- Helper Methods ---

    /**
     * Helper to construct the exact JSON structure the service expects from the AI provider (OpenAI/Fireworks format).
     */
    private MockResponse createMockResponse(int statusCode, String content) {
        try {
            // Structure: { "choices": [ { "message": { "content": "..." } } ] }
            var messageNode = objectMapper.createObjectNode();
            messageNode.put("content", content);

            var choiceNode = objectMapper.createObjectNode();
            choiceNode.set("message", messageNode);

            var choicesArray = objectMapper.createArrayNode();
            choicesArray.add(choiceNode);

            var rootNode = objectMapper.createObjectNode();
            rootNode.set("choices", choicesArray);

            return new MockResponse()
                    .setResponseCode(statusCode)
                    .setHeader("Content-Type", "application/json")
                    .setBody(objectMapper.writeValueAsString(rootNode));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}