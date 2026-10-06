package com.example.server.dto;

import com.example.server.entity.Document;
import java.time.LocalDateTime;

public record DocumentSummary(Long id, String filename, String documentType, String status,
                              String title, Integer pageCount, String warning,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static DocumentSummary from(Document document) {
        return new DocumentSummary(document.getId(), document.getFilename(), document.getDocumentType(),
                document.getStatus(), document.getTitle(), document.getPageCount(),
                document.getWarningJson(), document.getCreatedAt(), document.getUpdatedAt());
    }
}
