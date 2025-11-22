package com.axon.service.api;

import com.axon.model.Lesson;
import java.util.List;
import java.util.Map;

/**
 * Defines the contract for technology-specific prompt engineering strategies.
 * <p>
 * Implementations of this interface serve as "Prompt Engineers" for specific domains
 * (e.g., Git, Docker, Kubernetes). They are responsible for constructing the specific
 * string instructions sent to the AI model. They do not execute the API call themselves;
 * rather, they prepare the context, persona, system instructions, and JSON schemas
 * required for the AI to generate valid content.
 * </p>
 */
public interface PromptService {

    /**
     * Retrieves the list of available learning modules supported by this specific technology service.
     * <p>
     * For example, a Git service might return a map containing:
     * <ul>
     *   <li>Key: "basics", Value: "Git 101: init, add, commit"</li>
     *   <li>Key: "branching", Value: "Advanced Branching & Merging"</li>
     * </ul>
     * </p>
     *
     * @return A map where the key is the CLI argument for the module, and the value is the human-readable description.
     */
    Map<String, String> getAvailableModules();

    /**
     * Returns the display name of the technology managed by this service.
     * <p>
     * This value is used for looking up the correct service in the registry and for
     * display purposes in the CLI header.
     * </p>
     *
     * @return The technology name (e.g., "Git", "Docker", "Linux").
     */
    String getTechnologyName();

    /**
     * Constructs the initial system prompt used to generate the core curriculum for a specific module.
     * <p>
     * The resulting string typically includes:
     * <ol>
     *   <li>The AI Persona (e.g., "You are an expert Git tutor").</li>
     *   <li>The specific topic requested.</li>
     *   <li>Strict instructions to return a JSON response adhering to the {@code LearningModule} schema.</li>
     * </ol>
     * </p>
     *
     * @param moduleKey The unique key identifying the module to generate (from {@link #getAvailableModules()}).
     * @return A formatted, self-contained prompt string ready to be sent to the AI service.
     */
    String buildInitialModulePrompt(String moduleKey);

    /**
     * Constructs a context-aware prompt to generate additional, advanced lessons based on what the user has already completed.
     * <p>
     * To prevent the AI from repeating content, the implementation should include the titles or concepts
     * of the {@code existingLessons} in the prompt instructions (e.g., "Do not repeat the following topics...").
     * </p>
     *
     * @param moduleKey      The key of the current active module.
     * @param existingLessons The list of lessons the user has already successfully finished.
     * @return A prompt string instructing the AI to append new, unique lessons to the curriculum.
     */
    String buildMoreLessonsPrompt(String moduleKey, List<Lesson> existingLessons);

    /**
     * Wraps a user's free-text question into a persona-driven prompt context.
     * <p>
     * This ensures the AI answers strictly within the domain of the technology. For example,
     * if the user asks "How do I remove a file?", the prompt ensures the AI answers regarding
     * "git rm" rather than "rm" (Linux) or `del` (Windows), based on the service implementation.
     * </p>
     *
     * @param question The raw question text provided by the user.
     * @return A formatted prompt that restricts the AI's scope to the current technology.
     */
    String buildQuestionPrompt(String question);

    /**
     * Constructs a prompt requesting a retrospective summary of the completed module.
     * <p>
     * The prompt instructs the AI to analyze the specific concepts covered in the {@code lessons} list
     * and generate a cohesive paragraph reinforcing the learning outcomes.
     * </p>
     *
     * @param moduleName The human-readable display name of the module (e.g., "Git Basics").
     * @param lessons    The list of all lessons completed in the module to be summarized.
     * @return A formatted prompt string requesting a text-based summary.
     */
    String buildSummaryPrompt(String moduleName, List<Lesson> lessons);
}