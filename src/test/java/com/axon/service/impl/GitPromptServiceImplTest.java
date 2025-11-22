package com.axon.service.impl;

import com.axon.model.Lesson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GitPromptServiceImpl}.
 * <p>
 * Ensures that the Git-specific prompt engineering logic, including ChatML formatting,
 * XML tag injection for syntax highlighting, and module topic mapping, functions correctly.
 * </p>
 */
class GitPromptServiceImplTest {

    // Direct instantiation is sufficient as the service has no dependencies
    private final GitPromptServiceImpl promptService = new GitPromptServiceImpl();

    @Test
    @DisplayName("getTechnologyName should return 'Git'")
    void testGetTechnologyName() {
        assertThat(promptService.getTechnologyName()).isEqualTo("Git");
    }

    @Test
    @DisplayName("getAvailableModules should return the correct Git module map")
    void testGetAvailableModules() {
        var modules = promptService.getAvailableModules();

        assertThat(modules)
                .hasSize(4)
                .containsEntry("basics", "Git Basics: The First Steps")
                .containsEntry("branching", "Mastering Git Branching")
                .containsEntry("remotes", "Working with Remote Repositories")
                .containsEntry("history", "Inspecting and Rewriting History");
    }

    @Test
    @DisplayName("buildInitialModulePrompt should generate valid ChatML with Git specific instructions")
    void testBuildInitialModulePromptSuccess() {
        // Act
        var result = promptService.buildInitialModulePrompt("basics");

        // Assert
        assertThat(result)
                // 1. Verify ChatML Headers and JSON Constraint
                .contains("<|im_start|>system")
                .contains("only outputs valid JSON")
                .contains("<|im_end|>")
                // 2. Verify Topic Injection (mapped from "basics")
                .contains("absolute basics of Git")
                .contains("covering init, add, commit")
                // 3. Verify Git-Specific XML Tags
                .contains("<branch>...</branch>")
                .contains("<file>...</file>")
                .contains("<commit>...</commit>")
                // 4. Verify Example Structure
                .contains("git add <filename>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "svn", "", "  "})
    @NullSource
    @DisplayName("buildInitialModulePrompt should throw exception for invalid or null keys")
    void testBuildInitialModulePromptFailure(String invalidKey) {
        assertThatThrownBy(() -> promptService.buildInitialModulePrompt(invalidKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Git module key"); // Checks specifically for "Git" in error
    }

    @Test
    @DisplayName("buildMoreLessonsPrompt should include previously learned commands as negative constraints")
    void testBuildMoreLessonsPrompt() {
        // Arrange
        var existingLessons = List.of(
                new Lesson("Init", "Start repo", "git init", "", "", ""),
                new Lesson("Commit", "Save changes", "git commit -m 'msg'", "", "", "")
        );

        // Act
        var result = promptService.buildMoreLessonsPrompt("history", existingLessons);

        // Assert
        assertThat(result)
                // 1. Verify Topic Injection (mapped from "history")
                .contains("inspecting and rewriting Git history")
                // 2. Verify Negative Constraints (Crucial logic check)
                .contains("CRITICAL: The user has already learned these commands")
                .contains("`git init`")
                .contains("`git commit -m 'msg'`")
                .contains("MUST NOT create lessons for these commands")
                // 3. Verify Format matches JSON requirement
                .contains("only outputs valid JSON");
    }

    @Test
    @DisplayName("buildMoreLessonsPrompt should fail fast for invalid module keys")
    void testBuildMoreLessonsPromptFailure() {
        var lessons = List.of(new Lesson("T", "C", "cmd", "", "", ""));

        assertThatThrownBy(() -> promptService.buildMoreLessonsPrompt("invalid-key", lessons))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("buildQuestionPrompt should set expert Git persona and disable JSON constraint")
    void testBuildQuestionPrompt() {
        // Arrange
        String question = "What is the difference between rebase and merge?";

        // Act
        var result = promptService.buildQuestionPrompt(question);

        // Assert
        assertThat(result)
                .contains("<|im_start|>system")
                // Should NOT contain the JSON enforcement message
                .doesNotContain("only outputs valid JSON")
                // Should contain the Text-mode message
                .contains("You are a helpful assistant.")
                // Persona check
                .contains("expert Git tutor")
                // Input check
                .contains(question);
    }

    @Test
    @DisplayName("buildSummaryPrompt should list lesson titles in the prompt context")
    void testBuildSummaryPrompt() {
        // Arrange
        String moduleName = "Git Branching";
        var lessons = List.of(
                new Lesson("Creating Branches", "Concept A", "git branch", "", "", ""),
                new Lesson("Merging", "Concept B", "git merge", "", "", "")
        );

        // Act
        var result = promptService.buildSummaryPrompt(moduleName, lessons);

        // Assert
        assertThat(result)
                .contains("concise study guides")
                .contains("learning module named \"Git Branching\"")
                .contains("Creating Branches")
                .contains("Merging");
    }
}