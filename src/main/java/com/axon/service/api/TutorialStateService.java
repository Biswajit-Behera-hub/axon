package com.axon.service.api;

import com.axon.model.Lesson;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the runtime state of the user's educational session.
 * <p>
 * This service acts as the central controller for the learning experience. It is responsible for:
 * <ul>
 *   <li>Orchestrating the creation of learning modules via the {@link AiTutorService}.</li>
 *   <li>Tracking the user's progress (current lesson index) within a module.</li>
 *   <li>Handling navigation (next, previous, jump-to).</li>
 *   <li>Managing the context for ad-hoc Q&A and summary generation.</li>
 * </ul>
 * </p>
 */
public interface TutorialStateService {

    /**
     * Initializes a new learning session for the specified technology and topic.
     * <p>
     * This method triggers the AI generation process. It retrieves the appropriate
     * {@link PromptService}, constructs the prompt, calls the AI, and populates the
     * internal state with the resulting list of lessons.
     * </p>
     *
     * @param technology The technology identifier (e.g., "git").
     * @param moduleKey  The specific module topic identifier (e.g., "basics").
     * @throws RuntimeException if the technology is unknown or the AI generation fails.
     */
    void startModule(String technology, String moduleKey);

    /**
     * Retrieves the lesson currently active in the user's session.
     *
     * @return An {@link Optional} containing the current {@link Lesson}, or empty if
     *         no module has been started or if the module is complete.
     */
    Optional<Lesson> getCurrentLesson();

    /**
     * Advances the internal pointer to the next lesson and returns it.
     * <p>
     * If the user is currently on the last lesson, this method marks the module as complete
     * and returns an empty Optional.
     * </p>
     *
     * @return An {@link Optional} containing the next {@link Lesson}, or empty if the module has ended.
     */
    Optional<Lesson> getNextLesson();

    /**
     * Returns a human-readable string describing the current progress.
     *
     * @return A formatted string (e.g., "Lesson 3/10 in Git Basics" or "No active module").
     */
    String getStatus();

    /**
     * Checks if the user has reached the end of the current list of lessons.
     *
     * @return {@code true} if the current index is past the last lesson; {@code false} otherwise.
     */
    boolean isModuleComplete();

    /**
     * Triggers the AI to generate additional advanced lessons and appends them to the current module.
     * <p>
     * This is typically used when a user finishes a module but wants to continue learning
     * deeper concepts within the same session.
     * </p>
     */
    void appendMoreLessons();

    /**
     * Sends a user's question to the AI within the context of the current technology.
     *
     * @param question The text of the user's question.
     * @return The AI's plain-text response.
     */
    String answerQuestion(String question);

    /**
     * Retrieves the list of available modules for the technology currently loaded in the session.
     *
     * @return A map of module keys to descriptions, or an empty map if no technology is active.
     */
    Map<String, String> getAvailableModulesForCurrentTechnology();

    // --- NAVIGATION AND SUMMARY ---

    /**
     * Moves the internal progress pointer backward by one position.
     *
     * @return An {@link Optional} containing the previous {@link Lesson}.
     *         Returns {@code Optional.empty()} if the user is already at the first lesson.
     */
    Optional<Lesson> getPreviousLesson();

    /**
     * Jumps the internal progress pointer to a specific lesson index.
     * <p>
     * This method validates the bounds of the provided number against the total number of lessons.
     * </p>
     *
     * @param lessonNumber The 1-based lesson number (e.g., 1 for the first lesson).
     * @return An {@link Optional} containing the target {@link Lesson}, or {@code Optional.empty()}
     *         if the provided number is invalid (less than 1 or greater than total lessons).
     */
    Optional<Lesson> goToLesson(int lessonNumber);

    /**
     * Retrieves the complete, immutable list of lessons for the currently active module.
     * <p>
     * This is primarily used for displaying a Table of Contents (TOC) to the user.
     * </p>
     *
     * @return A {@link List} of all {@link Lesson} objects in the current session.
     *         Returns an empty list if no module is currently active.
     */
    List<Lesson> getCurrentModuleLessons();

    /**
     * Generates a cohesive summary of the material covered in the current module.
     * <p>
     * This method aggregates the concepts from the completed lessons and sends a request
     * to the AI to synthesize a review paragraph.
     * </p>
     *
     * @return A string containing the AI-generated summary.
     * @throws IllegalStateException if the module has not yet been completed.
     */
    String generateSummary();
}