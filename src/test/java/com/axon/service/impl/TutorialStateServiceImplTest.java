package com.axon.service.impl;

import com.axon.model.LearningModule;
import com.axon.model.Lesson;
import com.axon.service.api.AiTutorService;
import com.axon.service.api.PromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TutorialStateServiceImplTest {

    @Mock private ObjectMapper objectMapper;
    @Mock private AiTutorService aiTutorService;
    @Mock private ApplicationContext context;
    @Mock private PromptService promptService;

    private TutorialStateServiceImpl tutorialStateService;

    private static final String TECH_GIT = "git";
    private static final String MODULE_BASICS = "basics";
    private static final Lesson LESSON_1 = new Lesson("Intro", "Concept 1", "git init", "Initialized", "", "");
    private static final Lesson LESSON_2 = new Lesson("Staging", "Concept 2", "git add", "Staged", "git add .", "Use dot");
    private static final LearningModule MOCK_MODULE = new LearningModule("Git Basics", List.of(LESSON_1, LESSON_2));

    @BeforeEach
    void setUp() {
        tutorialStateService = new TutorialStateServiceImpl(objectMapper, aiTutorService, context);
    }

    @Test
    @DisplayName("startModule should initialize state, generate content, and save progress")
    void testStartModuleSuccess() throws IOException {
        setupPromptServiceMock();
        when(promptService.buildInitialModulePrompt(MODULE_BASICS)).thenReturn("prompt");
        when(aiTutorService.generateModuleFromPrompt(anyString(), anyInt())).thenReturn(MOCK_MODULE);

        tutorialStateService.startModule(TECH_GIT, MODULE_BASICS);

        verify(context).getBean("gitPromptService", PromptService.class);
        verify(aiTutorService).generateModuleFromPrompt(eq("prompt"), anyInt());

        ArgumentCaptor<TutorialStateServiceImpl.Progress> progressCaptor = ArgumentCaptor.forClass(TutorialStateServiceImpl.Progress.class);
        verify(objectMapper).writeValue(any(File.class), progressCaptor.capture());

        TutorialStateServiceImpl.Progress savedProgress = progressCaptor.getValue();
        assertThat(savedProgress.currentTechnology()).isEqualTo(TECH_GIT);
        assertThat(savedProgress.currentModuleKey()).isEqualTo(MODULE_BASICS);
        assertThat(savedProgress.currentLessonIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("startModule should throw exception for unknown technology")
    void testStartModuleUnknownTechnology() {
        when(context.getBean("cobolPromptService", PromptService.class))
                .thenThrow(new NoSuchBeanDefinitionException("No bean"));

        assertThatThrownBy(() -> tutorialStateService.startModule("cobol", "basics"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Technology 'cobol' is not supported");
    }

    @Test
    @DisplayName("startModule should throw exception for invalid module key")
    void testStartModuleInvalidKey() {
        setupPromptServiceMock();

        assertThatThrownBy(() -> tutorialStateService.startModule(TECH_GIT, "quantum-physics"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown module key");
    }

    @Test
    @DisplayName("Navigation should handle next, prev, and completion correctly")
    void testNavigationFlow() throws IOException {
        setupPromptServiceMock();
        when(aiTutorService.generateModuleFromPrompt(any(), anyInt())).thenReturn(MOCK_MODULE);
        tutorialStateService.startModule(TECH_GIT, MODULE_BASICS);

        assertThat(tutorialStateService.getCurrentLesson()).contains(LESSON_1);
        assertThat(tutorialStateService.isModuleComplete()).isFalse();

        Optional<Lesson> nextLesson = tutorialStateService.getNextLesson();
        assertThat(nextLesson).contains(LESSON_2);

        verify(objectMapper, times(2)).writeValue(any(File.class), any());

        Optional<Lesson> finished = tutorialStateService.getNextLesson();
        assertThat(finished).isEmpty();
        assertThat(tutorialStateService.isModuleComplete()).isTrue();

        tutorialStateService.goToLesson(2);
        Optional<Lesson> prev = tutorialStateService.getPreviousLesson();
        assertThat(prev).contains(LESSON_1);
    }

    @Test
    @DisplayName("goToLesson should jump to valid index and save")
    void testGoToLesson() throws IOException {
        setupPromptServiceMock();
        when(aiTutorService.generateModuleFromPrompt(any(), anyInt())).thenReturn(MOCK_MODULE);
        tutorialStateService.startModule(TECH_GIT, MODULE_BASICS);

        Optional<Lesson> lesson = tutorialStateService.goToLesson(2);

        assertThat(lesson).contains(LESSON_2);
        ArgumentCaptor<TutorialStateServiceImpl.Progress> captor = ArgumentCaptor.forClass(TutorialStateServiceImpl.Progress.class);
        verify(objectMapper, atLeastOnce()).writeValue(any(File.class), captor.capture());
        assertThat(captor.getValue().currentLessonIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("goToLesson should return empty for invalid indices")
    void testGoToLessonInvalid() {
        setupPromptServiceMock();
        when(aiTutorService.generateModuleFromPrompt(any(), anyInt())).thenReturn(MOCK_MODULE);
        tutorialStateService.startModule(TECH_GIT, MODULE_BASICS);

        assertThat(tutorialStateService.goToLesson(99)).isEmpty();
        assertThat(tutorialStateService.goToLesson(0)).isEmpty();
    }

    @Test
    @DisplayName("answerQuestion should delegate to AI service")
    void testAnswerQuestion() {
        setupPromptServiceMock();
        when(aiTutorService.generateModuleFromPrompt(any(), anyInt())).thenReturn(MOCK_MODULE);
        tutorialStateService.startModule(TECH_GIT, MODULE_BASICS);

        when(promptService.buildQuestionPrompt("How to commit?")).thenReturn("Q_PROMPT");
        when(aiTutorService.answerQuestionFromPrompt(eq("Q_PROMPT"), anyInt())).thenReturn("Git commit answer");

        String answer = tutorialStateService.answerQuestion("How to commit?");

        assertThat(answer).isEqualTo("Git commit answer");
    }

    @Test
    @DisplayName("generateSummary should fail if module is not complete")
    void testSummaryIncomplete() {
        setupPromptServiceMock();
        when(aiTutorService.generateModuleFromPrompt(any(), anyInt())).thenReturn(MOCK_MODULE);
        tutorialStateService.startModule(TECH_GIT, MODULE_BASICS);

        assertThatThrownBy(() -> tutorialStateService.generateSummary())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only be generated after completing");
    }

    @Test
    @DisplayName("appendMoreLessons should merge new lessons and update state")
    void testAppendMoreLessons() {
        setupPromptServiceMock();
        when(aiTutorService.generateModuleFromPrompt(any(), anyInt())).thenReturn(MOCK_MODULE);
        tutorialStateService.startModule(TECH_GIT, MODULE_BASICS);

        tutorialStateService.getNextLesson();
        tutorialStateService.getNextLesson();
        assertThat(tutorialStateService.isModuleComplete()).isTrue();

        Lesson newLesson = new Lesson("Advanced", "Rebase", "git rebase", "", "", "");
        LearningModule extensionModule = new LearningModule("Advanced Git", List.of(newLesson));

        when(promptService.buildMoreLessonsPrompt(eq(MODULE_BASICS), anyList())).thenReturn("EXT_PROMPT");
        when(aiTutorService.generateModuleFromPrompt(eq("EXT_PROMPT"), anyInt())).thenReturn(extensionModule);

        tutorialStateService.appendMoreLessons();

        assertThat(tutorialStateService.getCurrentModuleLessons()).hasSize(3);
        assertThat(tutorialStateService.getCurrentModuleLessons().get(2)).isEqualTo(newLesson);
    }

    // Use lenient() to avoid UnnecessaryStubbingException ---
    private void setupPromptServiceMock() {
        lenient().when(context.getBean("gitPromptService", PromptService.class)).thenReturn(promptService);
        lenient().when(promptService.getAvailableModules()).thenReturn(Map.of(MODULE_BASICS, "Git Basics"));
        lenient().when(promptService.getTechnologyName()).thenReturn("Git");
    }
}