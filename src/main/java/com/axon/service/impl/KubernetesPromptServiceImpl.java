package com.axon.service.impl;

import com.axon.model.Lesson;
import com.axon.service.api.PromptService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service responsible for constructing Kubernetes-specific AI prompts.
 * <p>
 * This implementation contains the "Prompt Engineering" logic for Kubernetes. It defines
 * the curriculum structure, enforces strict JSON output schemas, and injects
 * context-specific instructions (like XML tags for syntax highlighting) into the
 * prompt sent to the LLM.
 * </p>
 * <p>
 * <strong>Note:</strong> This class is compliant with Java 11 syntax.
 * </p>
 */
@Service("kubernetesPromptService")
public class KubernetesPromptServiceImpl implements PromptService {

    /**
     * Internal mapping of module keys to their specific prompt context/topic descriptions.
     */
    private static final Map<String, String> MODULE_TOPICS = Map.of(
            "core", "the core concepts of Kubernetes, covering Pods, Deployments, and Services with kubectl",
            "workloads", "managing application workloads, covering scaling, rollouts, and rollbacks of Deployments",
            "config", "configuring applications with ConfigMaps and Secrets",
            "discovery", "service discovery and basic networking in Kubernetes using Services and Labels"
    );

    /**
     * Public-facing mapping of module keys to their human-readable display titles.
     */
    private static final Map<String, String> AVAILABLE_MODULES = Map.of(
            "core", "Kubernetes Core Concepts",
            "workloads", "Managing Workloads",
            "config", "Configuration & Secrets",
            "discovery", "Service Discovery"
    );

    // ChatML-style formatting templates (Using String concatenation for Java 11 compatibility)
    private static final String CHAT_TEMPLATE_JSON =
            "<|im_start|>system\n" +
                    "You are a helpful assistant that only outputs valid JSON.<|im_end|>\n" +
                    "<|im_start|>user\n" +
                    "%s<|im_end|>\n" +
                    "<|im_start|>assistant\n";

    private static final String CHAT_TEMPLATE_TEXT =
            "<|im_start|>system\n" +
                    "You are a helpful assistant.<|im_end|>\n" +
                    "<|im_start|>user\n" +
                    "%s<|im_end|>\n" +
                    "<|im_start|>assistant\n";

    @Override
    public String getTechnologyName() {
        return "Kubernetes";
    }

    @Override
    public Map<String, String> getAvailableModules() {
        return AVAILABLE_MODULES;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <strong>Kubernetes Specifics:</strong> Instructions include requirements for {@code <resource>},
     * {@code <type>}, and {@code <namespace>} XML tags in the output to enable
     * CLI colorization in the parser.
     * </p>
     */
    @Override
    public String buildInitialModulePrompt(String moduleKey) {
        String topic = validateAndGetTopic(moduleKey);

        String promptTemplate =
                "You are a curriculum generation bot. Your only function is to output a single, valid JSON object.\n" +
                        "Generate a curriculum for a developer learning about '%s'.\n" +
                        "The \"lessons\" array must contain exactly 20 lesson objects.\n" +
                        "Each lesson object MUST contain \"title\", \"concept\", \"command\", \"example_output\", \"practiceCommand\", and \"hint\".\n" +
                        "\n" +
                        "- \"practiceCommand\": The exact command to practice, or \"\" if not applicable.\n" +
                        "- \"hint\": A helpful tip, or \"\" if not applicable.\n" +
                        "\n" +
                        "Inside \"example_output\", you MUST use these XML tags for colorization:\n" +
                        "- Resource names (like a pod or deployment name): <resource>...</resource>\n" +
                        "- Resource types (like 'pod', 'deployment', 'service'): <type>...</type>\n" +
                        "- Namespaces: <namespace>...</namespace>\n" +
                        "\n" +
                        "Output only the raw JSON.";

        String content = String.format(promptTemplate, topic);
        return formatToChatMl(content, true);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Includes a negative constraint based on existing lessons to ensure the AI generates new material.
     * </p>
     */
    @Override
    public String buildMoreLessonsPrompt(String moduleKey, List<Lesson> existingLessons) {
        String topic = validateAndGetTopic(moduleKey);

        String completedCommands = existingLessons.stream()
                .map(Lesson::command)
                .map(cmd -> "`" + cmd + "`")
                .collect(Collectors.joining(", "));

        String promptTemplate =
                "You are a curriculum generation bot outputting a single, valid JSON object.\n" +
                        "Generate a new curriculum with 10 more lessons for a developer learning about '%s'.\n" +
                        "CRITICAL: The user has already learned these commands: %s. You MUST NOT create lessons for these commands.\n" +
                        "Introduce NEW, more advanced, or related commands and concepts.\n" +
                        "Each lesson object must contain \"title\", \"concept\", \"command\", \"example_output\", \"practiceCommand\", and \"hint\".\n" +
                        "\n" +
                        "- \"practiceCommand\": The exact command to practice, or \"\" if not applicable.\n" +
                        "- \"hint\": A helpful tip, or \"\" if not applicable.\n" +
                        "\n" +
                        "Use the required <resource>, <type>, <namespace> tags in the example_output.\n" +
                        "Output only the raw JSON.";

        String content = String.format(promptTemplate, topic, completedCommands);
        return formatToChatMl(content, true);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sets the persona to "expert Kubernetes engineer" to ensure technical accuracy.
     * </p>
     */
    @Override
    public String buildQuestionPrompt(String question) {
        String promptTemplate =
                "You are an expert Kubernetes engineer and tutor. Provide a clear, concise explanation for the following user question.\n" +
                        "Use markdown for code blocks and emphasis.\n" +
                        "Question: \"%s\"";

        String content = String.format(promptTemplate, question);
        return formatToChatMl(content, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String buildSummaryPrompt(String moduleName, List<Lesson> lessons) {
        String lessonTitles = lessons.stream()
                .map(Lesson::title)
                .collect(Collectors.joining(", "));

        String promptTemplate =
                "You are a helpful assistant who creates concise study guides.\n" +
                        "Generate a markdown-formatted summary for a learning module named \"%s\".\n" +
                        "The module covered these topics: %s.\n" +
                        "Organize the summary with clear headings for the key concepts. Do not summarize each lesson individually; synthesize the core ideas.";

        String content = String.format(promptTemplate, moduleName, lessonTitles);
        return formatToChatMl(content, false);
    }

    /**
     * Validates that the requested module key exists in the configuration.
     *
     * @param moduleKey The key provided by the user.
     * @return The topic description.
     * @throws IllegalArgumentException if the key is invalid or null.
     */
    private String validateAndGetTopic(String moduleKey) {
        if (moduleKey == null || !MODULE_TOPICS.containsKey(moduleKey)) {
            throw new IllegalArgumentException("Invalid Kubernetes module key: " + moduleKey);
        }
        return MODULE_TOPICS.get(moduleKey);
    }

    /**
     * Wraps the user content in the ChatML format.
     *
     * @param userContent  The main prompt instruction.
     * @param isJsonOutput Whether the system instruction should enforce JSON output.
     * @return The fully formatted prompt string.
     */
    private String formatToChatMl(String userContent, boolean isJsonOutput) {
        return isJsonOutput
                ? String.format(CHAT_TEMPLATE_JSON, userContent)
                : String.format(CHAT_TEMPLATE_TEXT, userContent);
    }
}