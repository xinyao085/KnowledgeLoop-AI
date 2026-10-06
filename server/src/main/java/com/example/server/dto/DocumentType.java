package com.example.server.dto;

import java.util.Locale;

public enum DocumentType {
    PDF, TXT, MARKDOWN, DOCX, PPTX;

    public static DocumentType fromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return PDF;
        if (lower.endsWith(".txt")) return TXT;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return MARKDOWN;
        if (lower.endsWith(".docx")) return DOCX;
        if (lower.endsWith(".pptx")) return PPTX;
        throw new IllegalArgumentException("支持 PDF、DOCX、PPTX、TXT 和 Markdown 文档");
    }
}
