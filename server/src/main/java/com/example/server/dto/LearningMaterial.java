package com.example.server.dto;

import java.util.List;

public record LearningMaterial(String title, String explanation, List<String> keyPoints,
                               List<DocumentEvidence> evidence, Quiz quiz) {
    public LearningMaterial {
        title = title == null ? "本轮学习" : title.trim();
        explanation = explanation == null ? "" : explanation.trim();
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
    public record Quiz(String question, String referenceAnswer, String knowledgePoint) { }
}
