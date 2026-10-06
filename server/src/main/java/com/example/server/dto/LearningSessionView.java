package com.example.server.dto;

import java.time.LocalDateTime;
import java.util.List;

public record LearningSessionView(Long id, Long documentId, String goal, String status,
                                  String currentKnowledgePoint, String nextAction, long version,
                                  LearningMaterial material, LearningEvaluation lastEvaluation,
                                  String executionMode, String decisionReason,
                                  List<AgentTraceStep> agentTrace,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) { }
