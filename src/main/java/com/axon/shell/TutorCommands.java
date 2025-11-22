package com.axon.shell;

import com.axon.model.Lesson;
import com.axon.service.api.PromptService;
import com.axon.service.api.TutorialStateService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.springframework.boot.info.BuildProperties;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The primary Spring Shell component responsible for the interactive CLI tutorial interface.
 * <p>
 * This class handles all user commands (e.g., {@code start}, {@code next}, {@code practice}),
 * manages the visual presentation of lessons using JLine {@link AttributedString}, and orchestrates
 * the flow of the tutorial by interacting with the {@link TutorialStateService}.
 * </p>
 * <p>
 * It implements a stateful shell experience where the availability and behavior of commands
 * often depend on the current active lesson or technology context.
 * </p>
 */
@ShellComponent
@RequiredArgsConstructor
public class TutorCommands {

    private final BuildProperties buildProperties;
    private final TutorialStateService stateService;
    private final Terminal terminal;
    private final List<PromptService> promptServices;

    /**
     * Lazily initialized map of available technology services, keyed by technology name (lowercase).
     */
    private Map<String, PromptService> promptServiceMap;

    /**
     * Indicates if the user is currently expected to type a practice command.
     */
    @Getter
    private boolean inPracticeMode = false;

    /**
     * Tracks the last active technology (e.g., "git", "docker") to determine syntax highlighting rules.
     */
    private String lastUsedTechnology = null;

    /**
     * Holds the specific lesson object currently being practiced to validate user input.
     */
    private Lesson currentPracticeLesson = null;

    // --- UI STYLES (Constants) ---
    private static final AttributedStyle STYLE_HEADER = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();
    private static final AttributedStyle STYLE_LABEL = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.CYAN);
    private static final AttributedStyle STYLE_KEY = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.YELLOW);
    private static final AttributedStyle STYLE_INFO = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).italic();
    private static final AttributedStyle STYLE_ERROR = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.RED);
    private static final AttributedStyle STYLE_SUCCESS = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.GREEN).bold();
    private static final AttributedStyle STYLE_LESSON_TITLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold();
    private static final AttributedStyle STYLE_COMMAND = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.GREEN);
    private static final AttributedStyle STYLE_CONCEPT = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).italic();
    private static final AttributedStyle STYLE_OUTPUT = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE);

    // Tech-specific Styles (Syntax Highlighting)
    private static final AttributedStyle STYLE_GIT_BRANCH = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold();
    private static final AttributedStyle STYLE_GIT_FILE = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.CYAN);
    private static final AttributedStyle STYLE_GIT_COMMIT = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    private static final AttributedStyle STYLE_DOCKER_IMAGE = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.CYAN);
    private static final AttributedStyle STYLE_DOCKER_CONTAINER = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    private static final AttributedStyle STYLE_DOCKER_VOLUME = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);

    // --- COMMANDS ---

    /**
     * Validates a user's attempt at a practice exercise.
     * <p>
     * Compares the input string against the expected command in the current lesson.
     * If successful, it automatically advances to the next lesson.
     * </p>
     *
     * @param userInput The command string typed by the user. Users should wrap complex commands
     *                  containing spaces in quotes (e.g., {@code p "git commit -m 'msg'"}).
     */
    @ShellMethod(key = {"p", "practice"}, value = "Practice the command for the current lesson.")
    public void practice(@ShellOption(value = "", help = "The full command to practice, enclosed in quotes.") String userInput) {
        if (!inPracticeMode || currentPracticeLesson == null) {
            printInfo("There is no active practice exercise. Use 'next' to find one.");
            return;
        }

        // Robust null check to prevent NPE on empty input
        var input = userInput != null ? userInput : "";

        if (input.equals(currentPracticeLesson.practiceCommand())) {
            printSuccess("\nCorrect! Well done.");
            next(); // Automatically advance on success
        } else {
            printError("Not quite. Please try again. Type 'hint' if you're stuck.");
        }
    }

    /**
     * Displays a categorized list of all supported technologies and their specific learning modules.
     */
    @ShellMethod(key = "list", value = "List all available technologies and their learning modules.")
    public void list() {
        printHeader("\nAvailable Learning Tracks:");

        getPromptServiceMap().forEach((techKey, service) -> {
            printLabel("\n" + service.getTechnologyName() + " Modules:");
            service.getAvailableModules().forEach((key, name) -> {
                var formattedLine = new AttributedStringBuilder()
                        .append("  - ")
                        .style(STYLE_KEY).append(String.format("%-12s", key))
                        .style(AttributedStyle.DEFAULT).append(" | ").append(name)
                        .toAnsi();
                terminal.writer().println(formattedLine);
            });
        });
        printInfo("\nType 'start [technology] [module_key]' to begin (e.g., 'start git basics').");
    }

    /**
     * Initializes a specific learning module based on the provided technology and module key.
     * <p>
     * This triggers an interaction with the backend service to generate or retrieve the lesson plan.
     * </p>
     *
     * @param technology The technology identifier (e.g., "git", "docker").
     * @param moduleKey  The specific topic identifier (e.g., "basics", "advanced").
     */
    @ShellMethod(key = "start", value = "Start a new learning module for a specific technology.")
    public void start(
            @ShellOption(help = "The technology (e.g., 'git').") String technology,
            @ShellOption(help = "The module key (e.g., 'basics').") String moduleKey
    ) {
        var techKey = technology.toLowerCase();
        if (!getPromptServiceMap().containsKey(techKey)) {
            printError("Error: Unknown technology '" + technology + "'.");
            return;
        }
        try {
            printInfo("Please wait, generating your personalized lesson plan from the AI...");
            stateService.startModule(techKey, moduleKey);
            this.lastUsedTechnology = techKey;
            resetPracticeState();
            displayCurrentLesson();
        } catch (Exception e) {
            printError("Fatal Error: " + e.getMessage());
        }
    }

    /**
     * Advances the tutorial to the next lesson in the sequence.
     * <p>
     * Resets any active practice mode state before displaying the new lesson.
     * </p>
     */
    @ShellMethod(key = "next", value = "Proceed to the next lesson in the current module.")
    public void next() {
        resetPracticeState();
        stateService.getNextLesson();
        displayCurrentLesson();
    }

    /**
     * Moves the tutorial back to the immediately preceding lesson.
     */
    @ShellMethod(key = "prev", value = "Return to the previous lesson.")
    public void prev() {
        resetPracticeState();
        Optional<Lesson> lessonOpt = stateService.getPreviousLesson();
        if (lessonOpt.isPresent()) {
            displayCurrentLesson();
        } else {
            printInfo("You are already on the first lesson.");
        }
    }

    /**
     * Prints the Table of Contents (TOC) for the currently active module.
     * <p>
     * Displays a numbered list of lesson titles, which can be used with the {@code goto} command.
     * </p>
     */
    @ShellMethod(key = "toc", value = "Show the table of contents for the current module.")
    public void toc() {
        var lessons = stateService.getCurrentModuleLessons();
        if (lessons.isEmpty()) {
            printInfo("No active module. Use 'start' to begin.");
            return;
        }
        printHeader("\nTable of Contents:");
        for (int i = 0; i < lessons.size(); i++) {
            terminal.writer().println("[%d] %s".formatted(i + 1, lessons.get(i).title()));
        }
        printSeparator();
    }

    /**
     * Jumps directly to a specific lesson index.
     *
     * @param lessonNumber The 1-based index of the lesson (corresponding to the {@code toc} output).
     */
    @ShellMethod(key = "goto", value = "Jump to a specific lesson number.")
    public void goTo(@ShellOption(help = "The lesson number from the 'toc'.") int lessonNumber) {
        resetPracticeState();
        Optional<Lesson> lessonOpt = stateService.goToLesson(lessonNumber);
        if (lessonOpt.isEmpty()) {
            printError("Error: Invalid lesson number. Use 'toc' to see the list.");
        } else {
            displayCurrentLesson();
        }
    }

    /**
     * Bypasses the mandatory practice requirement for the current lesson (if any) and moves next.
     */
    @ShellMethod(key = "skip", value = "Skip the current practice exercise and move to the next lesson.")
    public void skip() {
        if (inPracticeMode) {
            printInfo("Skipping exercise...");
        }
        next();
    }

    /**
     * Provides a helpful hint for the current practice exercise, if available.
     */
    @ShellMethod(key = "hint", value = "Get a hint for the current practice exercise.")
    public void hint() {
        if (!inPracticeMode || currentPracticeLesson == null) {
            printInfo("There is no active practice exercise.");
            return;
        }
        var hintText = currentPracticeLesson.hint();
        if (hintText == null || hintText.isBlank()) {
            printInfo("Sorry, no hint is available for this lesson.");
        } else {
            printInfo("Hint: " + hintText);
        }
    }

    /**
     * Requests the generation of additional, advanced lessons for the current technology.
     * <p>
     * This command is only available after the current module has been completed.
     * </p>
     */
    @ShellMethod(key = "more", value = "Generate more lessons for the current topic after completing a module.")
    public void more() {
        if (!stateService.isModuleComplete()) {
            printError("You must finish the current set of lessons before requesting more.");
            return;
        }
        try {
            printInfo("Generating more advanced lessons... this may take a moment.");
            stateService.appendMoreLessons();
            printSuccess("\nNew lessons have been added! Type 'next' to continue.");
        } catch (Exception e) {
            printError("Error: Could not generate more lessons. " + e.getMessage());
        }
    }

    /**
     * Sends a free-text question to the AI tutor regarding the current context.
     *
     * @param questionParts The words forming the question (captured as an array to avoid quoting requirements).
     */
    @ShellMethod(key = "ask", value = "Ask the AI for help about the current technology.")
    public void ask(@ShellOption(arity = Integer.MAX_VALUE, help = "Your question.") String[] questionParts) {
        if (questionParts == null || questionParts.length == 0) {
            printError("Please provide a question after the 'ask' command.");
            return;
        }
        try {
            var question = String.join(" ", questionParts);
            printInfo("Asking the AI tutor for help...");
            var answer = stateService.answerQuestion(question);

            printSection("AI TUTOR'S RESPONSE", answer);
        } catch (Exception e) {
            printError("Fatal Error: Could not get an answer from the AI. " + e.getMessage());
        }
    }

    /**
     * Displays the current status of the tutorial (e.g., "Lesson 3/10 in Git Basics").
     */
    @ShellMethod(key = "status", value = "Check your current tutorial progress.")
    public void status() {
        printLabel(stateService.getStatus());
    }

    /**
     * Generates and displays an AI-driven summary of the content covered in the current module so far.
     */
    @ShellMethod(key = "summary", value = "Generate an AI summary of the completed module.")
    public void summary() {
        try {
            var summaryText = stateService.generateSummary();
            printSection("AI-POWERED MODULE SUMMARY", summaryText);
        } catch (Exception e) {
            printError("Error: " + e.getMessage());
        }
    }

    /**
     * Outputs the current version of the CLI application from build properties.
     */
    @ShellMethod(key = "version", value = "Display the application version.")
    public void version() {
        terminal.writer().println("axon-cli version " + buildProperties.getVersion());
        terminal.writer().flush();
    }

    // --- INTERNAL HELPERS ---

    /**
     * Lazily populates and retrieves the map of prompt services.
     *
     * @return A map where keys are lowercased technology names and values are the service instances.
     */
    private Map<String, PromptService> getPromptServiceMap() {
        if (promptServiceMap == null) {
            promptServiceMap = promptServices.stream()
                    .collect(Collectors.toMap(s -> s.getTechnologyName().toLowerCase(), Function.identity()));
        }
        return promptServiceMap;
    }

    /**
     * Resets the internal state flags associated with interactive practice exercises.
     */
    private void resetPracticeState() {
        this.inPracticeMode = false;
        this.currentPracticeLesson = null;
    }

    /**
     * Retrieves the current lesson from the state service and renders it to the terminal.
     * <p>
     * Handles two main states:
     * 1. Module Complete: Displays a completion message.
     * 2. Active Lesson: Renders title, concept, output, and sets up practice mode if applicable.
     * </p>
     */
    private void displayCurrentLesson() {
        Optional<Lesson> lessonOpt = stateService.getCurrentLesson();

        if (lessonOpt.isEmpty()) {
            resetPracticeState();
            var completionMsg = new AttributedStringBuilder()
                    .append("\nCongratulations, you have completed the module!\n", STYLE_SUCCESS)
                    .append("Type 'more' to generate more lessons or 'summary' for a review.", STYLE_INFO)
                    .toAnsi();
            terminal.writer().println(completionMsg);
        } else {
            var lesson = lessonOpt.get();
            terminal.writer().println(formatLessonForDisplay(lesson));

            if (lesson.practiceCommand() != null && !lesson.practiceCommand().isBlank()) {
                this.inPracticeMode = true;
                this.currentPracticeLesson = lesson;

                var practiceExample = "p '" + lesson.practiceCommand() + "'";
                terminal.writer().println(new AttributedStringBuilder()
                        .append("\n▶️ ", AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN))
                        .append("PRACTICE: Use the 'p' command with quotes. E.g., ", STYLE_INFO)
                        .style(STYLE_COMMAND).append(practiceExample)
                        .toAnsi()
                );
            } else {
                resetPracticeState();
                printInfo("\nType 'next' to continue or 'ask [question]' for help.");
            }
        }
        terminal.writer().flush();
    }

    /**
     * Constructs the styled ANSI string representation of a Lesson object.
     *
     * @param lesson The lesson data to format.
     * @return The Ansi string ready for terminal output.
     */
    private String formatLessonForDisplay(Lesson lesson) {
        var separator = "─".repeat(terminal.getWidth());
        var builder = new AttributedStringBuilder()
                .append("\n").style(STYLE_HEADER).append(separator).append("\n")
                .style(STYLE_LESSON_TITLE).append("Lesson: ").append(lesson.title()).append("\n")
                .style(STYLE_HEADER).append(separator).append("\n\n")
                .style(STYLE_LABEL).append("[CONCEPT]: ").style(STYLE_CONCEPT).append(lesson.concept()).append("\n\n");

        if (lesson.command() != null && !lesson.command().isBlank()) {
            builder.style(STYLE_LABEL).append("[COMMAND]:\n")
                    .style(STYLE_COMMAND).append("  ").append(lesson.command()).append("\n\n");
        }
        if (lesson.example_output() != null && !lesson.example_output().isBlank()) {
            var colorizedOutput = parseAndColorize(lesson.example_output());
            builder.style(STYLE_LABEL).append("[EXAMPLE OUTPUT]:\n").append(colorizedOutput).append("\n");
        }
        builder.style(STYLE_HEADER).append(separator);
        return builder.toAnsi();
    }

    /**
     * Parses the example output text and applies context-aware syntax highlighting.
     * <p>
     * Looks for specific XML-like tags (e.g., {@code <branch>main</branch>}) in the text
     * and applies colors based on the {@code lastUsedTechnology}.
     * </p>
     *
     * @param text The raw text containing semantic tags.
     * @return An {@link AttributedString} with styles applied and tags removed.
     */
    private AttributedString parseAndColorize(String text) {
        if (lastUsedTechnology == null) {
            // Fallback logic if technology is unknown, defaults based on current status
            lastUsedTechnology = stateService.getStatus().contains("Docker") ? "docker" : "git";
        }

        Pattern pattern = switch (lastUsedTechnology) {
            case "git" -> Pattern.compile("<(branch|file|commit)>(.*?)</\\1>");
            case "docker" -> Pattern.compile("<(image|container|volume)>(.*?)</\\1>");
            case "linux" -> Pattern.compile("<(path|user|pid)>(.*?)</\\1>");
            case "kubernetes" -> Pattern.compile("<(resource|type|namespace)>(.*?)</\\1>");
            default -> null;
        };

        if (pattern == null) {
            return new AttributedString(text, STYLE_OUTPUT);
        }

        var builder = new AttributedStringBuilder();
        var matcher = pattern.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            builder.style(STYLE_OUTPUT).append(text.substring(lastEnd, matcher.start()));
            var tagType = matcher.group(1);
            var content = matcher.group(2);
            addTechnologySpecificColoring(builder, tagType, content);
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            builder.style(STYLE_OUTPUT).append(text.substring(lastEnd));
        }
        return builder.toAttributedString();
    }

    /**
     * Helper method to apply specific styles based on the tag type and technology.
     */
    private void addTechnologySpecificColoring(AttributedStringBuilder builder, String tagType, String content) {
        // Enhanced Switch (Expression/Statement hybrid)
        switch (lastUsedTechnology) {
            case "git" -> {
                switch (tagType) {
                    case "branch" -> builder.style(STYLE_GIT_BRANCH).append(content);
                    case "file" -> builder.style(STYLE_GIT_FILE).append(content);
                    case "commit" -> builder.style(STYLE_GIT_COMMIT).append(content);
                    default -> builder.style(STYLE_OUTPUT).append(content);
                }
            }
            case "docker" -> {
                switch (tagType) {
                    case "image" -> builder.style(STYLE_DOCKER_IMAGE).append(content);
                    case "container" -> builder.style(STYLE_DOCKER_CONTAINER).append(content);
                    case "volume" -> builder.style(STYLE_DOCKER_VOLUME).append(content);
                    default -> builder.style(STYLE_OUTPUT).append(content);
                }
            }
            case "linux" -> {
                switch (tagType) {
                    case "path" -> builder.style(STYLE_GIT_FILE).append(content);
                    case "user" -> builder.style(STYLE_DOCKER_CONTAINER).append(content);
                    case "pid" -> builder.style(STYLE_GIT_COMMIT).append(content);
                    default -> builder.style(STYLE_OUTPUT).append(content);
                }
            }
            case "kubernetes" -> {
                switch (tagType) {
                    case "resource" -> builder.style(STYLE_DOCKER_CONTAINER).append(content);
                    case "type" -> builder.style(STYLE_KEY).append(content);
                    case "namespace" -> builder.style(STYLE_GIT_BRANCH).append(content);
                    default -> builder.style(STYLE_OUTPUT).append(content);
                }
            }
            default -> builder.style(STYLE_OUTPUT).append(content);
        }
    }

    // --- PRINTER UTILITIES ---

    private void printHeader(String text) {
        terminal.writer().println(new AttributedString(text, STYLE_HEADER).toAnsi());
        printSeparator();
    }

    private void printLabel(String text) {
        terminal.writer().println(new AttributedString(text, STYLE_LABEL).toAnsi());
        terminal.writer().flush();
    }

    private void printInfo(String text) {
        terminal.writer().println(new AttributedString(text, STYLE_INFO).toAnsi());
        terminal.writer().flush();
    }

    private void printError(String text) {
        terminal.writer().println(new AttributedString(text, STYLE_ERROR).toAnsi());
        terminal.writer().flush();
    }

    private void printSuccess(String text) {
        terminal.writer().println(new AttributedString(text, STYLE_SUCCESS).toAnsi());
        terminal.writer().flush();
    }

    private void printSeparator() {
        terminal.writer().println("─".repeat(40));
    }

    private void printSection(String title, String body) {
        String separator = "─".repeat(terminal.getWidth());
        terminal.writer().println("\n" + separator);
        terminal.writer().println(new AttributedString(title + ":", STYLE_HEADER).toAnsi());
        terminal.writer().println(separator + "\n");
        terminal.writer().println(new AttributedString(body, STYLE_CONCEPT).toAnsi());
        terminal.writer().println("\n" + separator);
        terminal.writer().flush();
    }
}