package com.axon.shell;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.shell.jline.PromptProvider;

/**
 * Configuration class responsible for customizing the user interface of the Spring Shell.
 * <p>
 * This configuration overrides default shell behaviors, specifically the CLI prompt
 * text and styling, to provide a branded "Axon" experience.
 * </p>
 * <p>
 * <strong>Note:</strong> Annotated with {@code proxyBeanMethods = false} for optimization,
 * as the beans defined here do not invoke other @Bean methods within the same configuration.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class ShellPromptConfiguration {

    private static final String PROMPT_TEXT = "axon > ";

    /**
     * Defines the custom {@link PromptProvider} for the application.
     * <p>
     * This bean configures the shell to display the prompt as "{@code axon > }"
     * rendered in a bright Cyan color using JLine's {@link AttributedString}.
     * </p>
     *
     * @return A {@link PromptProvider} lambda that constructs the styled prompt string.
     */
    @Bean
    public PromptProvider axonPrompt() {
        return () -> new AttributedString(
                PROMPT_TEXT,
                AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.CYAN)
        );
    }
}