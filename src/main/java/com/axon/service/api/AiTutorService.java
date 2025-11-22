package com.axon.service.api;

import com.axon.model.LearningModule;

/**
 * Defines the core contract for interacting with Generative AI providers to create educational content.
 * <p>
 * This interface abstracts the underlying details of the Large Language Model (LLM) integration
 * (e.g., OpenAI, Azure, Local LLM). It provides specific methods for retrieving both
 * structured data (mapped to Java records) and unstructured conversational text.
 * </p>
 * <p>
 * Implementations of this service are responsible for handling HTTP communication,
 * authentication, and low-level error handling with the AI provider.
 * </p>
 */
public interface AiTutorService {

    /**
     * Sends a prompt to the AI specifically designed to generate a structured learning curriculum
     * and maps the response to a {@link LearningModule} object.
     * <p>
     * Implementations should ensure the AI is instructed to return valid JSON adhering to
     * the {@code LearningModule} schema. This method handles the parsing and deserialization
     * of that JSON response.
     * </p>
     *
     * @param prompt    The complete, context-enriched instructions to send to the AI.
     *                  This usually includes the system persona, the topic, and the JSON schema requirements.
     * @param maxTokens The maximum number of tokens to allow for the generation.
     *                  Higher values are typically required for full modules to prevent JSON truncation.
     * @return A {@link LearningModule} containing the generated lessons.
     * @throws RuntimeException (or a specific subclass) if the AI service is unavailable or if the
     *                          response cannot be parsed into a valid object.
     */
    LearningModule generateModuleFromPrompt(String prompt, int maxTokens);

    /**
     * Sends a prompt to the AI to retrieve a free-form text response.
     * <p>
     * This method is suitable for conversational interactions, such as answering user questions,
     * providing hints, or generating summaries where structured JSON is not required.
     * </p>
     *
     * @param prompt    The complete, formatted prompt containing the user's question or request.
     * @param maxTokens The maximum number of tokens for the response. Lower limits are often
     *                  sufficient for concise Q&A interactions.
     * @return The raw text string returned by the AI, stripped of any protocol metadata.
     */
    String answerQuestionFromPrompt(String prompt, int maxTokens);
}