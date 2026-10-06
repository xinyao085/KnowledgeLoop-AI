package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CourseRequest(@NotBlank @Size(max=255) String title,
                            @Size(max=1000) String description,
                            List<Long> documentIds) { }
