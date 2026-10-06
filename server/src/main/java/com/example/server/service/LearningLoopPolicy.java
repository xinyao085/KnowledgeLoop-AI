package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.entity.DocumentChunk;
import java.util.*;

/** Deterministic boundaries around probabilistic agent decisions. */
public final class LearningLoopPolicy {
    private LearningLoopPolicy() { }

    public static LearningEvaluation evaluate(LearningEvaluation raw, double current, boolean canAdvance) {
        if (raw == null || !Double.isFinite(raw.score())) throw new IllegalArgumentException("无效测验评分");
        double score = Math.max(0, Math.min(1, raw.score()));
        boolean correct = raw.correct() && score >= .6;
        String error = correct ? "NONE" : Objects.toString(raw.errorType(), "INCOMPLETE");
        double delta = correct ? (score >= .8 ? .3 : .15) : ("CONCEPTUAL".equals(error) ? -.2 : -.1);
        double mastery = Math.max(0, Math.min(1, current + delta));
        String action = correct && mastery >= .8 ? (canAdvance ? "ADVANCE" : "COMPLETE")
                : !correct && "PREREQUISITE_GAP".equals(error) ? "REVIEW_PREREQUISITE" : "RETEACH";
        return new LearningEvaluation(correct, score, raw.feedback(), error, action, mastery);
    }

    public static LearningCritique verify(LearningMaterial material, LearningCritique critic,
                                          List<DocumentChunk> chunks) {
        List<String> errors = new ArrayList<>();
        if (critic != null) {
            errors.addAll(critic.feedback());
            errors.addAll(critic.unsupportedClaims());
            errors.addAll(critic.missingObjectives());
        }
        if (critic == null || !critic.passed()) errors.add("语义校验未通过");
        if (material == null || material.explanation().isBlank() || material.keyPoints().isEmpty()
                || material.quiz() == null || blank(material.quiz().question()) || blank(material.quiz().referenceAnswer())) {
            errors.add("讲解或测验结构不完整");
        }
        if (material == null || material.evidence().isEmpty()) errors.add("缺少原文引用");
        else for (DocumentEvidence citation : material.evidence()) {
            boolean valid = !blank(citation.quote()) && !blank(citation.supportedClaim()) && chunks.stream().anyMatch(c ->
                    Objects.equals(c.getChunkKey(), citation.chunkId())
                    && Objects.equals(c.getDocumentId(), citation.documentId())
                    && c.getContent() != null && c.getContent().contains(citation.quote()));
            if (!valid) errors.add("引用无法定位到原文：" + citation.chunkId());
        }
        return new LearningCritique(errors.isEmpty(), errors, List.of(), List.of());
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
}
