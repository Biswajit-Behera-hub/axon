package com.axon.service.impl;

import com.axon.model.LearningModule;
import com.axon.model.Lesson;
import com.axon.service.api.AiTutorService;
import com.axon.service.api.PromptService;
import com.axon.service.api.TutorialStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of {@link TutorialStateService} that manages the user's learning session.
 * <p>
 * This service handles state persistence (via a local JSON file), orchestrates the
 * generation of content via the {@link AiTutorService}, and manages the navigation
 * pointer (current lesson index).
 * </p>
 */
@Service
public class TutorialStateServiceImpl implements TutorialStateService {

    private static final Logger log = LoggerFactory.getLogger(TutorialStateServiceImpl.class);

    // Constants for configuration
    private static final String PROGRESS_FILENAME = ".axon-progress.json";
    private static final int MAX_TOKENS_MODULE = 5000;
    private static final int MAX_TOKENS_EXTENSION = 4000;
    private static final int MAX_TOKENS_SUMMARY = 2500;
    private static final Path PROGRESS_PATH = Path.of(System.getProperty("user.home"), PROGRESS_FILENAME);

    /**
     * DTO for persisting the user's current location in the curriculum.
     */
    public record Progress(String currentTechnology, String currentModuleKey, int currentLessonIndex) {}

    private final ObjectMapper objectMapper;
    private final AiTutorService aiTutorService;
    private final ApplicationContext context;

    // Mutable state
    private LearningModule currentModule;
    private Progress currentProgress;
    private PromptService currentPromptService;

    public TutorialStateServiceImpl(ObjectMapper objectMapper, AiTutorService aiTutorService, ApplicationContext context) {
        this.objectMapper = objectMapper;
        this.aiTutorService = aiTutorService;
        this.context = context;
    }

    /**
     * Attempt to resume the previous session on application startup.
     * <p>
     * It reads the local progress file and attempts to re-generate the module content
     * from the AI. If the AI service is unreachable, it logs a warning and starts with a clean state.
     * </p>
     */
    @PostConstruct
    public void loadProgress() {
        if (!Files.exists(PROGRESS_PATH)) {
            return;
        }

        try {
            log.info("Loading progress from {}", PROGRESS_PATH);
            var savedProgress = objectMapper.readValue(PROGRESS_PATH.toFile(), Progress.class);

            if (savedProgress != null) {
                log.info("Resuming previous session for {}...", savedProgress.currentTechnology());

                // Restore state
                this.currentPromptService = getPromptServiceFor(savedProgress.currentTechnology());
                var prompt = currentPromptService.buildInitialModulePrompt(savedProgress.currentModuleKey());

                // Regenerate content
                this.currentModule = aiTutorService.generateModuleFromPrompt(prompt, MAX_TOKENS_MODULE);
                this.currentProgress = savedProgress;
            }
        } catch (Exception e) {
            log.error("Failed to resume session. Starting fresh. Error: {}", e.getMessage());
            // Reset state on failure to ensure app remains usable
            this.currentProgress = null;
            this.currentModule = null;
            this.currentPromptService = null;
        }
    }

    @Override
    public void startModule(String technology, String moduleKey) {
        log.info("Starting new module: Technology={}, Key={}", technology, moduleKey);

        this.currentPromptService = getPromptServiceFor(technology);

        if (!currentPromptService.getAvailableModules().containsKey(moduleKey)) {
            throw new IllegalArgumentException("Unknown module key '" + moduleKey + "' for " + technology);
        }

        var prompt = currentPromptService.buildInitialModulePrompt(moduleKey);
        this.currentModule = aiTutorService.generateModuleFromPrompt(prompt, MAX_TOKENS_MODULE);

        // Initialize progress at lesson 0
        this.currentProgress = new Progress(technology, moduleKey, 0);
        saveProgress();
    }

    @Override
    public Optional<Lesson> getCurrentLesson() {
        if (currentModule == null || currentProgress == null || isModuleComplete()) {
            return Optional.empty();
        }
        // Safety check for index out of bounds
        if (currentProgress.currentLessonIndex() >= currentModule.lessons().size()) {
            return Optional.empty();
        }
        return Optional.of(currentModule.lessons().get(currentProgress.currentLessonIndex()));
    }

    @Override
    public Optional<Lesson> getNextLesson() {
        if (currentModule == null || isModuleComplete()) {
            return Optional.empty();
        }

        // Advance index
        int nextIndex = currentProgress.currentLessonIndex() + 1;
        this.currentProgress = new Progress(
                currentProgress.currentTechnology(),
                currentProgress.currentModuleKey(),
                nextIndex
        );

        saveProgress();
        return getCurrentLesson();
    }

    @Override
    public String getStatus() {
        if (currentProgress == null || currentPromptService == null) {
            return "No tutorial in progress. Use 'start' to begin.";
        }

        String techName = currentPromptService.getTechnologyName();
        String moduleName = currentProgress.currentModuleKey();
        int totalLessons = currentModule.lessons().size();

        if (isModuleComplete()) {
            return "You have completed all %d lessons of the '%s' module for %s!".formatted(
                    totalLessons, moduleName, techName);
        }

        return "Technology: %s | Module: '%s' | Lesson %d of %d.".formatted(
                techName, moduleName, currentProgress.currentLessonIndex() + 1, totalLessons);
    }

    @Override
    public boolean isModuleComplete() {
        if (currentModule == null || currentProgress == null) {
            return false;
        }
        return currentProgress.currentLessonIndex() >= currentModule.lessons().size();
    }

    @Override
    public void appendMoreLessons() {
        if (!isModuleComplete()) {
            throw new IllegalStateException("Finish current lessons first.");
        }
        if (currentModule == null) {
            throw new IllegalStateException("No active module.");
        }

        log.info("Generating extension lessons for {}", currentProgress.currentModuleKey());

        var prompt = currentPromptService.buildMoreLessonsPrompt(
                currentProgress.currentModuleKey(),
                currentModule.lessons()
        );

        var newLessonsModule = aiTutorService.generateModuleFromPrompt(prompt, MAX_TOKENS_EXTENSION);

        // Merge existing lessons with new ones
        List<Lesson> combinedLessons = new ArrayList<>(currentModule.lessons());
        combinedLessons.addAll(newLessonsModule.lessons());

        this.currentModule = new LearningModule(currentModule.moduleName(), combinedLessons);
        saveProgress();
    }

    @Override
    public String answerQuestion(String question) {
        if (currentPromptService == null) {
            throw new IllegalStateException("Cannot answer question without context. Please start a module first.");
        }
        var prompt = currentPromptService.buildQuestionPrompt(question);
        return aiTutorService.answerQuestionFromPrompt(prompt, MAX_TOKENS_SUMMARY);
    }

    @Override
    public Map<String, String> getAvailableModulesForCurrentTechnology() {
        if (currentPromptService == null) {
            return Map.of();
        }
        return currentPromptService.getAvailableModules();
    }

    @Override
    public Optional<Lesson> getPreviousLesson() {
        if (currentProgress == null || currentProgress.currentLessonIndex() <= 0) {
            return Optional.empty();
        }

        int prevIndex = currentProgress.currentLessonIndex() - 1;
        this.currentProgress = new Progress(
                currentProgress.currentTechnology(),
                currentProgress.currentModuleKey(),
                prevIndex
        );

        saveProgress();
        return getCurrentLesson();
    }

    @Override
    public Optional<Lesson> goToLesson(int lessonNumber) {
        // Convert 1-based CLI input to 0-based index
        int targetIndex = lessonNumber - 1;

        if (currentModule == null || targetIndex < 0 || targetIndex >= currentModule.lessons().size()) {
            return Optional.empty();
        }

        this.currentProgress = new Progress(
                currentProgress.currentTechnology(),
                currentProgress.currentModuleKey(),
                targetIndex
        );

        saveProgress();
        return getCurrentLesson();
    }

    @Override
    public List<Lesson> getCurrentModuleLessons() {
        return currentModule != null ? currentModule.lessons() : Collections.emptyList();
    }

    @Override
    public String generateSummary() {
        if (!isModuleComplete()) {
            throw new IllegalStateException("A summary can only be generated after completing all lessons in the module.");
        }
        if (currentModule == null) {
            throw new IllegalStateException("No active module to summarize.");
        }

        log.info("Generating summary for module: {}", currentProgress.currentModuleKey());

        String moduleName = currentPromptService.getAvailableModules().get(currentProgress.currentModuleKey());
        String prompt = currentPromptService.buildSummaryPrompt(moduleName, currentModule.lessons());

        return aiTutorService.answerQuestionFromPrompt(prompt, MAX_TOKENS_SUMMARY);
    }

    /**
     * Persists the current progress state to the local filesystem.
     */
    private void saveProgress() {
        try {
            objectMapper.writeValue(PROGRESS_PATH.toFile(), currentProgress);
        } catch (IOException e) {
            log.error("Failed to save progress to file: {}", e.getMessage());
        }
    }

    /**
     * Dynamically retrieves the correct {@link PromptService} bean based on the technology name.
     *
     * @param technology The technology identifier (e.g., "git", "docker").
     * @return The specific PromptService implementation.
     * @throws IllegalArgumentException If no service bean exists for the given technology.
     */
    private PromptService getPromptServiceFor(String technology) {
        String beanName = technology.toLowerCase() + "PromptService";
        try {
            return context.getBean(beanName, PromptService.class);
        } catch (NoSuchBeanDefinitionException e) {
            log.error("Service lookup failed for technology: {}", technology);
            throw new IllegalArgumentException("Technology '" + technology + "' is not supported.");
        }
    }
}