package com.axon.service.impl;

import com.axon.model.Lesson;
import com.axon.service.api.PromptService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of {@link PromptService} specifically designed for the Docker technology stack.
 * <p>
 * This class encapsulates the "Prompt Engineering" strategies required to generate high-quality
 * Docker curricula. It is responsible for:
 * <ul>
 *   <li>Defining the scope and depth of specific Docker modules (e.g., Images vs. Networking).</li>
 *   <li>Enforcing strict JSON output schemas to ensure the AI response can be parsed by the application.</li>
 *   <li>Injecting specific formatting instructions (like XML tags) to support CLI syntax highlighting.</li>
 *   <li>Formatting prompts using the ChatML structure required by the underlying LLM.</li>
 * </ul>
 * </p>
 */
@Service("dockerPromptService")
public class DockerPromptServiceImpl implements PromptService {

    /**
     * Internal mapping of module keys to their specific prompt context/topic descriptions.
     * <p>
     * These strings are injected directly into the system prompt to guide the AI's generation process.
     * They define the boundaries of what should be covered in a specific module.
     * </p>
     */
    private static final Map<String, String> MODULE_TOPICS = Map.of(
            "basics", "the absolute basics of Docker, covering running containers, `ps`, `logs`, and `stop`",
            "images", "building and managing Docker images, covering `build`, `tag`, `push`, `pull`, and `rmi`",
            "volumes", "managing persistent data with Docker volumes, covering `volume create`, `ls`, `inspect`, and bind mounts",
            "networking", "Docker container networking, covering bridge networks, port mapping, and `network create`"
    );

    /**
     * Public-facing mapping of module keys to their human-readable display titles.
     * <p>
     * These values are used by the CLI implementation to display the available curriculum list
     * to the user via the {@code list} command.
     * </p>
     */
    private static final Map<String, String> AVAILABLE_MODULES = Map.of(
            "basics", "Docker Basics: First Containers",
            "images", "Building and Managing Images",
            "volumes", "Persistent Data with Volumes",
            "networking", "Container Networking"
    );

    /**
     * ChatML format template for JSON-enforced responses.
     * <p>
     * This specific format ({@code <|im_start|>...}) is required by specific instruction-tuned models
     * (e.g., Qwen, ChatGLM) to correctly distinguish between system instructions and user requests.
     * </p>
     */
    private static final String CHAT_TEMPLATE_JSON = """
            <|im_start|>system
            You are a helpful assistant that only outputs valid JSON.<|im_end|>
            <|im_start|>user
            %s<|im_end|>
            <|im_start|>assistant
            """;

    /**
     * ChatML format template for free-text conversational responses.
     */
    private static final String CHAT_TEMPLATE_TEXT = """
            <|im_start|>system
            You are a helpful assistant.<|im_end|>
            <|im_start|>user
            %s<|im_end|>
            <|im_start|>assistant
            """;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTechnologyName() {
        return "Docker";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getAvailableModules() {
        return AVAILABLE_MODULES;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <strong>Docker Specifics:</strong> This method mandates specific XML tags (e.g., {@code <image>},
     * {@code <container>}) in the 'example_output' field. These tags are parsed by the CLI frontend
     * to apply specific colors (e.g., cyan for images, green for containers), improving the
     * educational experience.
     * </p>
     *
     * @param moduleKey The unique identifier for the requested module.
     * @return A fully formatted ChatML prompt string ready for the AI service.
     * @throws IllegalArgumentException if the {@code moduleKey} is not found in {@link #MODULE_TOPICS}.
     */
    @Override
    public String buildInitialModulePrompt(String moduleKey) {
        String topic = validateAndGetTopic(moduleKey);

        // formatted() is a Java 15+ instance method for cleaner string interpolation
        String promptContent = """
                You are a curriculum generation bot. Your only function is to output a single, valid JSON object.
                Generate a curriculum for a developer learning about '%s'.
                The "lessons" array must contain exactly 20 lesson objects.
                Each lesson object MUST contain "title", "concept", "command", "example_output", "practiceCommand", and "hint".
                
                - "practiceCommand": The exact command to practice, or "" if not applicable.
                - "hint": A helpful tip, or "" if not applicable.
                
                Inside "example_output", you MUST use these XML tags for colorization:
                - Image names: <image>...</image>
                - Container names/IDs: <container>...</container>
                - Volume/Network names: <volume>...</volume>
                
                Output only the raw JSON.
                """.formatted(topic);

        return formatToChatMl(promptContent, true);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method constructs a prompt that is context-aware. It explicitly lists the commands
     * the user has already learned (extracted from {@code existingLessons}) and instructs the
     * AI via a "negative constraint" (MUST NOT create lessons for...) to prevent repetition.
     * </p>
     *
     * @param moduleKey       The key of the current active module.
     * @param existingLessons The history of lessons the user has completed.
     * @return A formatted prompt string for generating extension lessons.
     */
    @Override
    public String buildMoreLessonsPrompt(String moduleKey, List<Lesson> existingLessons) {
        String topic = validateAndGetTopic(moduleKey);

        String completedCommands = existingLessons.stream()
                .map(Lesson::command)
                .map(cmd -> "`" + cmd + "`")
                .collect(Collectors.joining(", "));

        String promptContent = """
                You are a curriculum generation bot outputting a single, valid JSON object.
                Generate a new curriculum with 10 more lessons for a developer learning about '%s'.
                CRITICAL: The user has already learned these commands: %s. You MUST NOT create lessons for these commands.
                Introduce NEW, more advanced, or related commands and concepts.
                Each lesson object must contain "title", "concept", "command", "example_output", "practiceCommand", and "hint".
                
                - "practiceCommand": The exact command to practice, or "" if not applicable.
                - "hint": A helpful tip, or "" if not applicable.
                
                Use the required <image>, <container>, <volume> tags in the example_output.
                Output only the raw JSON.
                """.formatted(topic, completedCommands);

        return formatToChatMl(promptContent, true);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sets the persona to "expert Docker tutor" to ensure the answers are technically accurate
     * and relevant to the Docker ecosystem (e.g., prioritizing `docker rm` over standard Linux `rm`).
     * </p>
     */
    @Override
    public String buildQuestionPrompt(String question) {
        String promptContent = """
                You are an expert Docker tutor. Provide a clear, concise explanation for the following user question.
                Use markdown for code blocks and emphasis.
                Question: "%s"
                """.formatted(question);

        return formatToChatMl(promptContent, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String buildSummaryPrompt(String moduleName, List<Lesson> lessons) {
        String lessonTitles = lessons.stream()
                .map(Lesson::title)
                .collect(Collectors.joining(", "));

        String promptContent = """
                You are a helpful assistant who creates concise study guides.
                Generate a markdown-formatted summary for a learning module named "%s".
                The module covered these topics: %s.
                Organize the summary with clear headings for the key concepts. Do not summarize each lesson individually; synthesize the core ideas.
                """.formatted(moduleName, lessonTitles);

        return formatToChatMl(promptContent, false);
    }

    /**
     * Validates that the requested module key exists in the configuration map.
     *
     * @param moduleKey The key provided by the user.
     * @return The internal topic description associated with the key.
     * @throws IllegalArgumentException if the moduleKey is null or not found in the configuration.
     */
    private String validateAndGetTopic(String moduleKey) {

        if (moduleKey == null) { // <--- This check is mandatory
            throw new IllegalArgumentException("Invalid module key: null");
        }

        String topic = MODULE_TOPICS.get(moduleKey);
        if (topic == null) {
            throw new IllegalArgumentException("Invalid Docker module key: " + moduleKey);
        }
        return topic;
    }

    /**
     * Wraps the user content in the ChatML format required by specific LLMs.
     * <p>
     * This ensures the system instructions are strictly separated from user input in the
     * context window, preventing potential prompt injection issues and improving adherence to instructions.
     * </p>
     *
     * @param userContent  The main prompt instruction or user question.
     * @param isJsonOutput If {@code true}, injects a system instruction enforcing JSON output.
     *                     If {@code false}, uses a standard helpful assistant persona.
     * @return The fully formatted prompt string including special tokens.
     */
    private String formatToChatMl(String userContent, boolean isJsonOutput) {
        return isJsonOutput
                ? CHAT_TEMPLATE_JSON.formatted(userContent)
                : CHAT_TEMPLATE_TEXT.formatted(userContent);
    }
}