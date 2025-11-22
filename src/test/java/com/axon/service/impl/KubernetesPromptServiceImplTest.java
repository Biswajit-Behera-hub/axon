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
 * Unit tests for {@link KubernetesPromptServiceImpl}.
 * <p>
 * Ensures that the Kubernetes-specific prompt engineering logic, including ChatML formatting,
 * XML tag injection for syntax highlighting, and module topic mapping, functions correctly.
 * </p>
 */
class KubernetesPromptServiceImplTest {

    // No mocks required as the service has no dependencies
    private final KubernetesPromptServiceImpl promptService = new KubernetesPromptServiceImpl();

    @Test
    @DisplayName("getTechnologyName should return 'Kubernetes'")
    void testGetTechnologyName() {
        assertThat(promptService.getTechnologyName()).isEqualTo("Kubernetes");
    }

    @Test
    @DisplayName("getAvailableModules should return the correct Kubernetes module map")
    void testGetAvailableModules() {
        var modules = promptService.getAvailableModules();

        assertThat(modules)
                .hasSize(4)
                .containsEntry("core", "Kubernetes Core Concepts")
                .containsEntry("workloads", "Managing Workloads")
                .containsEntry("config", "Configuration & Secrets")
                .containsEntry("discovery", "Service Discovery");
    }

    @Test
    @DisplayName("buildInitialModulePrompt should generate valid ChatML with K8s specific instructions")
    void testBuildInitialModulePromptSuccess() {
        // Act
        var result = promptService.buildInitialModulePrompt("core");

        // Assert
        assertThat(result)
                // 1. Verify ChatML Headers and JSON Constraint
                .contains("<|im_start|>system")
                .contains("only outputs valid JSON")
                .contains("<|im_end|>")
                // 2. Verify Topic Injection (mapped from "core")
                .contains("core concepts of Kubernetes")
                .contains("covering Pods, Deployments")
                // 3. Verify K8s-Specific XML Tags
                .contains("<resource>...</resource>")
                .contains("<type>...</type>")
                .contains("<namespace>...</namespace>")
                // 4. Verify Structure
                .contains("curriculum generation bot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "docker", "", "  "})
    @NullSource
    @DisplayName("buildInitialModulePrompt should throw exception for invalid or null keys")
    void testBuildInitialModulePromptFailure(String invalidKey) {
        assertThatThrownBy(() -> promptService.buildInitialModulePrompt(invalidKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Kubernetes module key");
    }

    @Test
    @DisplayName("buildMoreLessonsPrompt should include previously learned commands as negative constraints")
    void testBuildMoreLessonsPrompt() {
        // Arrange
        var existingLessons = List.of(
                new Lesson("Pod Basics", "Running a pod", "kubectl run nginx", "", "", ""),
                new Lesson("Get Pods", "Listing resources", "kubectl get pods", "", "", "")
        );

        // Act
        var result = promptService.buildMoreLessonsPrompt("workloads", existingLessons);

        // Assert
        assertThat(result)
                // 1. Verify Topic Injection (mapped from "workloads")
                .contains("managing application workloads")
                // 2. Verify Negative Constraints (Crucial logic check)
                .contains("CRITICAL: The user has already learned these commands")
                .contains("`kubectl run nginx`")
                .contains("`kubectl get pods`")
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
    @DisplayName("buildQuestionPrompt should set expert Kubernetes persona and disable JSON constraint")
    void testBuildQuestionPrompt() {
        // Arrange
        String question = "What is a ReplicaSet?";

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
                .contains("expert Kubernetes engineer")
                // Input check
                .contains(question);
    }

    @Test
    @DisplayName("buildSummaryPrompt should list lesson titles in the prompt context")
    void testBuildSummaryPrompt() {
        // Arrange
        String moduleName = "K8s Config";
        var lessons = List.of(
                new Lesson("ConfigMaps", "Env vars", "kubectl create configmap", "", "", ""),
                new Lesson("Secrets", "Sensitive data", "kubectl create secret", "", "", "")
        );

        // Act
        var result = promptService.buildSummaryPrompt(moduleName, lessons);

        // Assert
        assertThat(result)
                .contains("concise study guides")
                .contains("learning module named \"K8s Config\"")
                .contains("ConfigMaps")
                .contains("Secrets");
    }
}