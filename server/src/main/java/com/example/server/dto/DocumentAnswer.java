package com.example.server.dto;

import java.util.List;

public record DocumentAnswer(String conversationId, String answer, List<DocumentEvidence> evidence,
                             String executionMode) { }
