package com.example.server.dto;

import java.util.List;

public record DocumentContext(
        Long documentId,
        String source,
        String title,
        DocumentType type,
        List<DocumentSegment> segments
) { }
