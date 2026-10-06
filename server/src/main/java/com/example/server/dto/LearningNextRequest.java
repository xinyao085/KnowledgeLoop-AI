package com.example.server.dto;

import jakarta.validation.constraints.NotNull;

public record LearningNextRequest(@NotNull Long sessionVersion) { }
