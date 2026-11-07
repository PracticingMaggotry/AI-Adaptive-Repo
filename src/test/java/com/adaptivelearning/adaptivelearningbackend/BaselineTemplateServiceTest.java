package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BaselineTemplateServiceTest {

    @Mock BaselineTemplateRepository templates;
    @Mock QuestionRepository questionRepository;
    @Mock StudyScopeRepository scopeRepository;
    @Mock QuestionSetService questionSetService;
    @InjectMocks BaselineTemplateService service;

    private static Question question() {
        Question q = new Question();
        q.setDifficulty("Easy");
        q.setQuestionText("What does ATP store?");
        q.setOptionA("Energy"); q.setOptionB("Water"); q.setOptionC("Oxygen"); q.setOptionD("Protein");
        q.setCorrectAnswer("A");
        q.setHint("Think energy currency");
        q.setExplanation("ATP stores usable energy.");
        q.setType("MCQ");
        q.setConceptKey("atp");
        q.setPurpose("INITIAL_COVERAGE");
        q.setBloomLevel(2);
        q.setCategory("Recall");
        q.setOwnerId("first@student.test");
        return q;
    }

    @Test
    void aSavedFirstQuizIsCopiedToTheNextStudentWithTheirOwnOwnerAndScope() {
        service.save("n1:abc", "Easy", List.of(question()));
        ArgumentCaptor<BaselineTemplate> saved = ArgumentCaptor.forClass(BaselineTemplate.class);
        verify(templates).save(saved.capture());

        when(templates.findByContentHashAndTierAndPromptVersion("n1:abc", "Easy", AiPolicy.PROMPT_VERSION))
                .thenReturn(Optional.of(saved.getValue()));
        StudyScope scope = mock(StudyScope.class);
        when(scope.getId()).thenReturn(42L);
        when(questionSetService.replaceSet(anyString(), anyString(), anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());

        assertEquals(1, service.apply("n1:abc", "Easy", "second@student.test", "Photosynthesis", scope));

        @SuppressWarnings("unchecked") ArgumentCaptor<List<Question>> copies = ArgumentCaptor.forClass(List.class);
        verify(questionSetService).replaceSet(eq("second@student.test"), eq("Photosynthesis"), copies.capture());
        verify(questionRepository, never()).saveAll(any()); // never a bare append: the swap replaces the old set atomically
        Question c = copies.getValue().get(0);
        assertEquals("second@student.test", c.getOwnerId());
        assertEquals(42L, c.getScopeId());
        assertEquals("Photosynthesis", c.getTopic());
        assertEquals("What does ATP store?", c.getQuestionText());
        assertEquals("atp", c.getConceptKey());
        assertEquals(2, c.getBloomLevel());
        assertFalse(c.isArchived());
    }

    @Test
    void nothingIsSharedForUnhashableContentOrWhenNoTemplateExists() {
        service.save("unique-123", "Easy", List.of(question()));
        verify(templates, never()).save(any());
        assertEquals(0, service.apply("unique-123", "Easy", "x@y.test", "T", mock(StudyScope.class)));
        assertEquals(0, service.apply("n1:none", "Easy", "x@y.test", "T", mock(StudyScope.class)));
        verify(questionSetService, never()).replaceSet(any(), any(), any());
    }

    @Test
    void aFailedSaveIsNotSwallowedSoTheCallerDoesNotPayForAFreshQuiz() {
        service.save("n1:abc", "Easy", List.of(question()));
        ArgumentCaptor<BaselineTemplate> saved = ArgumentCaptor.forClass(BaselineTemplate.class);
        verify(templates).save(saved.capture());
        when(templates.findByContentHashAndTierAndPromptVersion("n1:abc", "Easy", AiPolicy.PROMPT_VERSION))
                .thenReturn(Optional.of(saved.getValue()));
        StudyScope scope = mock(StudyScope.class);
        when(questionSetService.replaceSet(anyString(), anyString(), anyList()))
                .thenThrow(new QuestionSetService.ReplaceFailedException("kept", null));

        assertThrows(QuestionSetService.ReplaceFailedException.class,
                () -> service.apply("n1:abc", "Easy", "second@student.test", "Photosynthesis", scope));
    }

    @Test
    void anExistingTemplateIsNotWrittenTwice() {
        when(templates.findByContentHashAndTierAndPromptVersion(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new BaselineTemplate("n1:abc", "Easy", AiPolicy.PROMPT_VERSION, "[]")));
        service.save("n1:abc", "Easy", List.of(question()));
        verify(templates, never()).save(any());
    }
}
