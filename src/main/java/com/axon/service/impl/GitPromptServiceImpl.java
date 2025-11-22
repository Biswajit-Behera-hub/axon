package com.axon.service.impl;

import com.axon.model.Lesson;
import com.axon.service.api.PromptService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of {@link PromptService} specifically designed for the Git version control system.
 * <p>
 * This class encapsulates the "Prompt Engineering" strategies required to generate high-quality
 * Git curricula. It is responsible for:
 * <ul>
 *   <li>Defining the scope of specific Git modules (e.g., Basics vs. Branching).</li>
 *   <li>Enforcing strict JSON output schemas to ensure the AI response can be parsed.</li>
 *   <li>Injecting specific formatting instructions (XML tags) to support CLI syntax highlighting for Git entities.</li>
 *   <li>Formatting prompts using the ChatML structure required by the underlying LLM.</li>
 * </ul>
 * </p>
 */
@Service("gitPromptService")
public class GitPromptServiceImpl implements PromptService {

    /**
     * Internal mapping of module keys to their specific prompt context/topic descriptions.
     * <p>
     * These descriptions are injected directly into the system prompt to guide the AI's generation process.
     * They define the boundaries of what should be covered in a specific module.
     * </p>
     */
    private static final Map<String, String> MODULE_TOPICS = Map.of(
            "basics", "the absolute basics of Git, covering init, add, commit, status, and log",
            "branching", "Git branching, covering create, switch, merge, and delete branches",
            "remotes", "working with remote Git repositories, covering clone, push, pull, and fetch",
            "history", "inspecting and rewriting Git history, covering rebase, amend, and reset"
    );

    /**
     * Public-facing mapping of module keys to their human-readable display titles.
     * <p>
     * These values are used by the CLI implementation to display the available curriculum list
     * to the user via the {@code list} command.
     * </p>
     */
    private static final Map<String, String> AVAILABLE_MODULES = Map.of(
            "basics", "Git Basics: The First Steps",
            "branching", "Mastering Git Branching",
            "remotes", "Working with Remote Repositories",
            "history", "Inspecting and Rewriting History"
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
        return "Git";
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
     * <strong>Git Specifics:</strong> This method mandates specific XML tags ({@code <branch>},
     * {@code <file>}, {@code <commit>}) in the 'example_output' field. These tags are parsed by
     * the CLI frontend to apply specific colors (e.g., Magenta for branches, Cyan for files),
     * improving the educational experience.
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
                The "lessons" array must contain exactly 30 lesson objects.
                Each lesson object MUST contain "title", "concept", "command", "example_output", "practiceCommand", and "hint".
                
                - "practiceCommand": This MUST be the *exact*, simple command the user should type to practice. For conceptual lessons, this can be an empty string "".
                - "hint": A short, helpful tip related to the command's syntax. For conceptual lessons, this can be an empty string "".
                
                Inside "example_output", you MUST use these XML tags for colorization:
                - Branch names: <branch>...</branch>
                - Filenames/paths: <file>...</file>
                - Commit hashes: <commit>...</commit>
                
                EXAMPLE LESSON OBJECT:
                {
                  "title": "Adding a File",
                  "concept": "The 'git add' command stages changes for the next commit.",
                  "command": "git add <filename>",
                  "example_output": "...",
                  "practiceCommand": "git add README.md",
                  "hint": "Don't forget to specify which file you want to add after the command."
                }
                
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
                Generate a new curriculum with 15 more lessons for a developer learning about '%s'.
                CRITICAL: The user has already learned these commands: %s. You MUST NOT create lessons for these commands.
                Introduce NEW, more advanced, or related commands and concepts.
                Each lesson object must contain "title", "concept", "command", "example_output", "practiceCommand", and "hint".
                
                - "practiceCommand": The exact command to practice, or "" if not applicable.
                - "hint": A helpful tip, or "" if not applicable.
                
                Use the required <branch>, <file>, <commit> tags in the example_output.
                Output only the raw JSON.
                """.formatted(topic, completedCommands);

        return formatToChatMl(promptContent, true);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sets the persona to "expert Git tutor" to ensure the answers are technically accurate
     * regarding version control concepts.
     * </p>
     */
    @Override
    public String buildQuestionPrompt(String question) {
        String promptContent = """
                You are an expert Git tutor. Provide a clear, concise explanation for the following user question.
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
        if (moduleKey == null) {
            throw new IllegalArgumentException("Invalid Git module key: null");
        }
        String topic = MODULE_TOPICS.get(moduleKey);
        if (topic == null) {
            throw new IllegalArgumentException("Invalid Git module key: " + moduleKey);
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