package com.example.server.dto;

import java.util.List;

public record LearningCritique(boolean passed, List<String> feedback,
                               List<String> unsupportedClaims, List<String> missingObjectives) {
    public LearningCritique {
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
        unsupportedClaims = unsupportedClaims == null ? List.of() : List.copyOf(unsupportedClaims);
        missingObjectives = missingObjectives == null ? List.of() : List.copyOf(missingObjectives);
    }
}
