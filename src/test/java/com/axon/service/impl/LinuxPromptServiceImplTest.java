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
 * Unit tests for {@link LinuxPromptServiceImpl}.
 * <p>
 * Ensures that the Linux-specific prompt engineering logic, including ChatML formatting,
 * XML tag injection for syntax highlighting, and module topic mapping, functions correctly.
 * </p>
 */
class LinuxPromptServiceImplTest {

    // Service has no dependencies, so we test the POJO directly
    private final LinuxPromptServiceImpl promptService = new LinuxPromptServiceImpl();

    @Test
    @DisplayName("getTechnologyName should return 'Linux'")
    void testGetTechnologyName() {
        assertThat(promptService.getTechnologyName()).isEqualTo("Linux");
    }

    @Test
    @DisplayName("getAvailableModules should return the correct Linux module map")
    void testGetAvailableModules() {
        var modules = promptService.getAvailableModules();

        assertThat(modules)
                .hasSize(4)
                .containsEntry("files", "Linux Files & Directories")
                .containsEntry("permissions", "Understanding Permissions")
                .containsEntry("processes", "Process Management")
                .containsEntry("text", "Text Processing & Pipes");
    }

    @Test
    @DisplayName("buildInitialModulePrompt should generate valid ChatML with Linux specific instructions")
    void testBuildInitialModulePromptSuccess() {
        // Act
        var result = promptService.buildInitialModulePrompt("files");

        // Assert
        assertThat(result)
                // 1. Verify ChatML Headers and JSON Constraint
                .contains("<|im_start|>system")
                .contains("only outputs valid JSON")
                .contains("<|im_end|>")
                // 2. Verify Topic Injection (mapped from "files")
                .contains("basic file system navigation")
                .contains("covering ls, cd, pwd")
                // 3. Verify Linux-Specific XML Tags
                .contains("<path>...</path>")
                .contains("<user>...</user>")
                .contains("<pid>...</pid>")
                // 4. Verify Context
                .contains("developer learning about 'basic file system navigation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "windows", "", "  "})
    @NullSource
    @DisplayName("buildInitialModulePrompt should throw exception for invalid or null keys")
    void testBuildInitialModulePromptFailure(String invalidKey) {
        assertThatThrownBy(() -> promptService.buildInitialModulePrompt(invalidKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Linux module key");
    }

    @Test
    @DisplayName("buildMoreLessonsPrompt should include previously learned commands as negative constraints")
    void testBuildMoreLessonsPrompt() {
        // Arrange
        var existingLessons = List.of(
                new Lesson("List Files", "Listing", "ls -la", "", "", ""),
                new Lesson("Change Dir", "Navigation", "cd /var", "", "", "")
        );

        // Act
        var result = promptService.buildMoreLessonsPrompt("permissions", existingLessons);

        // Assert
        assertThat(result)
                // 1. Verify Topic Injection (mapped from "permissions")
                .contains("managing file permissions")
                // 2. Verify Negative Constraints (Crucial logic check)
                .contains("CRITICAL: The user has already learned these commands")
                .contains("`ls -la`")
                .contains("`cd /var`")
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
    @DisplayName("buildQuestionPrompt should set expert SysAdmin persona and disable JSON constraint")
    void testBuildQuestionPrompt() {
        // Arrange
        String question = "How do I find a process by name?";

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
                .contains("expert Linux System Administrator")
                // Input check
                .contains(question);
    }

    @Test
    @DisplayName("buildSummaryPrompt should list lesson titles in the prompt context")
    void testBuildSummaryPrompt() {
        // Arrange
        String moduleName = "Linux Processes";
        var lessons = List.of(
                new Lesson("Top Command", "Monitoring", "top", "", "", ""),
                new Lesson("Kill Command", "Termination", "kill -9", "", "", "")
        );

        // Act
        var result = promptService.buildSummaryPrompt(moduleName, lessons);

        // Assert
        assertThat(result)
                .contains("concise study guides")
                .contains("learning module named \"Linux Processes\"")
                .contains("Top Command")
                .contains("Kill Command");
    }
}