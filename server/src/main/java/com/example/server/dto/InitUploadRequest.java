package com.example.server.dto;
import jakarta.validation.constraints.*;
public record InitUploadRequest(@NotBlank @Size(max=255) String filename,
                                @Size(max=128) String mimeType,
                                @NotNull @Min(1) @Max(10000) Integer totalChunks,
                                @NotNull @Min(1) @Max(2147483648L) Long totalSize,
                                @NotNull @Min(1) @Max(5242880) Integer chunkSize,
                                @NotBlank @Pattern(regexp="[a-f0-9]{64}") String fingerprint) { }
