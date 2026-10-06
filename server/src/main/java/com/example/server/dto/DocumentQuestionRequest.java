package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentQuestionRequest(@NotBlank @Size(max=4000) String question,
                                      @Size(max=64) String conversationId) { }
