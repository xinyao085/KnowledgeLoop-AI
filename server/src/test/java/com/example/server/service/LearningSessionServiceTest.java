package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningSessionServiceTest {
    @Test void repairsThenFallsBackAndPreservesStateMachine() {
        var sessions = mock(LearningSessionMapper.class);
        var progress = mock(LearningProgressMapper.class);
        var documents = mock(DocumentService.class);
        var retrieval = mock(DocumentRetrievalService.class);
        var ai = mock(DeepSeekUtils.class);
        var service = new LearningSessionService(sessions, progress, documents, retrieval, ai,
                new ObjectMapper(), mock(TaskEventService.class));
        Document doc = new Document(); doc.setId(1L); doc.setStatus("READY");
        when(documents.requireOwned(1L, 2L)).thenReturn(doc);
        DocumentChunk chunk = new DocumentChunk(); chunk.setDocumentId(1L); chunk.setChunkKey("c");
        chunk.setContent("教材原文");
        when(retrieval.retrieve(eq(1L), anyString(), eq(4))).thenReturn(List.of(chunk));
        when(ai.planLearning(anyString(), anyString(), anyList())).thenReturn(
                new LearningPlan("A", List.of("目标"), List.of("基础"), "策略", List.of("A", "B")));
        var unsafe = new LearningMaterial("草稿", "没有依据的讲解", List.of("结论"), List.of(),
                new LearningMaterial.Quiz("问题", "答案", "A"));
        when(ai.createLearningMaterial(anyString(), anyString(), anyList())).thenReturn(unsafe);
        when(ai.repairLearningMaterial(anyString(), any(), any(), any(), anyList())).thenReturn(unsafe);
        when(ai.critiqueLearningMaterial(anyString(), any(), any(), anyList())).thenReturn(
                new LearningCritique(true, List.of(), List.of(), List.of()));
        LearningSession[] stored = new LearningSession[1];
        doAnswer(call -> { stored[0] = call.getArgument(0); stored[0].setId(9L); return 1; }).when(sessions).insert(any(LearningSession.class));
        var view = service.create(new CreateLearningSessionRequest(1L, "学习目标"), 2L);
        assertEquals("FALLBACK", view.executionMode());
        assertEquals("教材原文", view.material().explanation());
        verify(ai, times(2)).repairLearningMaterial(anyString(), any(), any(), any(), anyList());
        when(sessions.selectById(9L)).thenAnswer(call -> stored[0]);
        assertThrows(IllegalStateException.class, () -> service.next(9L, new LearningNextRequest(0L), 2L));
        stored[0].setStatus("STEP_COMPLETED");
        assertThrows(IllegalStateException.class, () -> service.answer(9L, new LearningAnswerRequest(0L, "答案", "key"), 2L));
        verify(progress, never()).upsertMastery(anyLong(), anyLong(), anyString(), anyDouble(), anyString());
        stored[0].setStatus("WAITING_FOR_ANSWER");
        when(sessions.update(any(LearningSession.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(progress.findMastery(2L, 1L, "A")).thenReturn(.6);
        when(ai.evaluateLearningAnswer(anyString(), anyString(), anyString(), anyDouble())).thenReturn(
                new LearningEvaluation(true, .9, "正确", "NONE", "COMPLETE", 1));
        var evaluated = service.answer(9L, new LearningAnswerRequest(0L, "答案", "key"), 2L);
        assertEquals("ADVANCE", evaluated.nextAction());
        assertEquals(.9, evaluated.lastEvaluation().masteryScore(), .0001);
        var advanced = service.next(9L, new LearningNextRequest(1L), 2L);
        assertEquals("B", advanced.currentKnowledgePoint());
        assertEquals("WAITING_FOR_ANSWER", advanced.status());
        var secondPoint = service.answer(9L, new LearningAnswerRequest(2L, "答案", "key2"), 2L);
        assertEquals(.3, secondPoint.lastEvaluation().masteryScore(), .0001);
        assertEquals("RETEACH", secondPoint.nextAction());
        service.next(9L, new LearningNextRequest(3L), 2L);
        when(ai.evaluateLearningAnswer(anyString(), anyString(), anyString(), anyDouble())).thenReturn(
                new LearningEvaluation(false, .2, "缺少基础", "PREREQUISITE_GAP", "ADVANCE", 1));
        assertEquals("REVIEW_PREREQUISITE", service.answer(9L,
                new LearningAnswerRequest(4L, "答案", "key3"), 2L).nextAction());
        assertEquals("基础", service.next(9L, new LearningNextRequest(5L), 2L).currentKnowledgePoint());
        when(progress.findMastery(2L, 1L, "基础")).thenReturn(.6);
        when(ai.evaluateLearningAnswer(anyString(), anyString(), anyString(), anyDouble())).thenReturn(
                new LearningEvaluation(true, .9, "正确", "NONE", "COMPLETE", 1));
        assertEquals("ADVANCE", service.answer(9L,
                new LearningAnswerRequest(6L, "答案", "key4"), 2L).nextAction());
        assertEquals("B", service.next(9L, new LearningNextRequest(7L), 2L).currentKnowledgePoint());


    }
}
