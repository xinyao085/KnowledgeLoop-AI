package com.example.server.dto;

public record LearningEvaluation(boolean correct, double score, String feedback, String errorType,
                                 String nextAction, double masteryScore) { }
