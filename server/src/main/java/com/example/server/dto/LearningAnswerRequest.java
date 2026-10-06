package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LearningAnswerRequest(@NotNull Long sessionVersion,
                                    @NotBlank @Size(max = 4000) String answer,
                                    @NotBlank @Size(max = 128) String idempotencyKey) { }
