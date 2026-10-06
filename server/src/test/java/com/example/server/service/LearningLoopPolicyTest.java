package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.entity.DocumentChunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LearningLoopPolicyTest {
    private LearningEvaluation grade(boolean correct, double score, String error) {
        return new LearningEvaluation(correct, score, "诊断", error, "COMPLETE", 1);
    }

    @Test void ignoresModelMasteryAndRequiresRepeatedEvidence() {
        var first = LearningLoopPolicy.evaluate(grade(true, .9, "NONE"), 0, true);
        assertEquals(.3, first.masteryScore(), .0001);
        assertEquals("RETEACH", first.nextAction());
        assertEquals("ADVANCE", LearningLoopPolicy.evaluate(grade(true, .9, "NONE"), .6, true).nextAction());
        assertEquals("COMPLETE", LearningLoopPolicy.evaluate(grade(true, .9, "NONE"), .6, false).nextAction());
    }

    @Test void diagnosesPrerequisitesAndDoesNotAdvanceOnWrongAnswer() {
        assertEquals("REVIEW_PREREQUISITE", LearningLoopPolicy.evaluate(grade(false, .2, "PREREQUISITE_GAP"), .4, true).nextAction());
        var wrong = LearningLoopPolicy.evaluate(grade(false, .1, "CONCEPTUAL"), 1, false);
        assertEquals(.8, wrong.masteryScore(), .0001);
        assertEquals("RETEACH", wrong.nextAction());
        assertEquals("RETEACH", LearningLoopPolicy.evaluate(grade(true, .1, "NONE"), .9, false).nextAction());
    }

    @Test void rejectsNonFiniteGrades() {
        assertThrows(IllegalArgumentException.class, () -> LearningLoopPolicy.evaluate(grade(true, Double.NaN, "NONE"), 0, false));
    }

    @Test void rejectsFabricatedQuotesAndContradictoryCriticApproval() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentId(1L); chunk.setChunkKey("c1"); chunk.setContent("光合作用需要光。");
        var critic = new LearningCritique(true, List.of(), List.of(), List.of());
        assertTrue(LearningLoopPolicy.verify(material("光合作用需要光。"), critic, List.of(chunk)).passed());
        assertFalse(LearningLoopPolicy.verify(material("光合作用不需要光。"), critic, List.of(chunk)).passed());
        assertFalse(LearningLoopPolicy.verify(material("光合作用需要光。"),
                new LearningCritique(true, List.of(), List.of("无依据结论"), List.of()), List.of(chunk)).passed());
        chunk.setDocumentId(2L);
        assertFalse(LearningLoopPolicy.verify(material("光合作用需要光。"), critic, List.of(chunk)).passed());
    }

    private LearningMaterial material(String quote) {
        return new LearningMaterial("光合作用", "解释", List.of("需要光"),
                List.of(new DocumentEvidence(1L, "c1", "教材", null, null, null, null, null, null, quote, "需要光")),
                new LearningMaterial.Quiz("需要什么？", "光", "光合作用"));
    }
}
