package com.axon.service.impl;

import com.axon.model.Lesson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DockerPromptServiceImpl}.
 * <p>
 * Ensures that the Docker-specific prompt engineering logic, including ChatML formatting,
 * XML tag injection, and topic mapping, functions correctly.
 * </p>
 */
class DockerPromptServiceImplTest {

    // No mocks needed as this class has no dependencies
    private final DockerPromptServiceImpl promptService = new DockerPromptServiceImpl();

    @Test
    @DisplayName("getTechnologyName should return 'Docker'")
    void testGetTechnologyName() {
        assertThat(promptService.getTechnologyName()).isEqualTo("Docker");
    }

    @Test
    @DisplayName("getAvailableModules should return the correct Docker module map")
    void testGetAvailableModules() {
        var modules = promptService.getAvailableModules();

        assertThat(modules)
                .hasSize(4)
                .containsEntry("basics", "Docker Basics: First Containers")
                .containsEntry("images", "Building and Managing Images")
                .containsEntry("volumes", "Persistent Data with Volumes")
                .containsEntry("networking", "Container Networking");
    }

    @Test
    @DisplayName("buildInitialModulePrompt should generate valid ChatML with Docker specific instructions")
    void testBuildInitialModulePromptSuccess() {
        // Act
        var result = promptService.buildInitialModulePrompt("basics");

        // Assert
        assertThat(result)
                // Verify ChatML structure
                .contains("<|im_start|>system")
                .contains("only outputs valid JSON") // Json constraint
                .contains("<|im_end|>")
                // Verify Topic Injection
                .contains("absolute basics of Docker")
                .contains("running containers")
                // Verify Docker Specific XML Tags
                .contains("<image>...</image>")
                .contains("<container>...</container>")
                .contains("<volume>...</volume>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "kubernets", "", " "})
    @DisplayName("buildInitialModulePrompt should throw exception for invalid keys")
    void testBuildInitialModulePromptFailure(String invalidKey) {
        assertThatThrownBy(() -> promptService.buildInitialModulePrompt(invalidKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Docker module key");
    }

    @Test
    @DisplayName("buildMoreLessonsPrompt should include previously learned commands")
    void testBuildMoreLessonsPrompt() {
        // Arrange
        var existingLessons = List.of(
                new Lesson("Intro", "C1", "docker run", "", "", ""),
                new Lesson("Ps", "C2", "docker ps", "", "", "")
        );

        // Act
        var result = promptService.buildMoreLessonsPrompt("images", existingLessons);

        // Assert
        assertThat(result)
                // Verify Context Injection
                .contains("building and managing Docker images") // Topic for 'images'
                // Verify Negative Constraints
                .contains("CRITICAL: The user has already learned these commands")
                .contains("`docker run`")
                .contains("`docker ps`")
                // Verify Format
                .contains("<|im_start|>system")
                .contains("only outputs valid JSON");
    }

    @Test
    @DisplayName("buildQuestionPrompt should set expert Docker persona")
    void testBuildQuestionPrompt() {
        // Arrange
        String question = "How do I remove all stopped containers?";

        // Act
        var result = promptService.buildQuestionPrompt(question);

        // Assert
        assertThat(result)
                .contains("<|im_start|>system")
                .doesNotContain("only outputs valid JSON") // Should be text mode
                .contains("expert Docker tutor")
                .contains(question);
    }

    @Test
    @DisplayName("buildSummaryPrompt should list lesson titles in prompt")
    void testBuildSummaryPrompt() {
        // Arrange
        String moduleName = "Docker Basics";
        var lessons = List.of(
                new Lesson("Running Nginx", "C1", "cmd1", "", "", ""),
                new Lesson("Stopping Containers", "C2", "cmd2", "", "", "")
        );

        // Act
        var result = promptService.buildSummaryPrompt(moduleName, lessons);

        // Assert
        assertThat(result)
                .contains("concise study guides")
                .contains("learning module named \"Docker Basics\"")
                .contains("Running Nginx")
                .contains("Stopping Containers");
    }
}