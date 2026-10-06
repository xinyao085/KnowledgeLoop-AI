package com.example.server.dto;

import java.util.List;

public record LearningPlan(String knowledgePoint, List<String> objectives,
                           List<String> prerequisites, String teachingStrategy, List<String> knowledgePoints) {
    public LearningPlan(String knowledgePoint, List<String> objectives, List<String> prerequisites, String teachingStrategy) {
        this(knowledgePoint, objectives, prerequisites, teachingStrategy, List.of(knowledgePoint));
    }
    public LearningPlan {
        knowledgePoints = knowledgePoints == null ? List.of() : knowledgePoints.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).distinct().limit(20).toList();
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
    }
}
