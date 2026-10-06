package com.example.server.service;

import com.example.server.dto.DocumentSegment;
import com.example.server.entity.DocumentChunk;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class DocumentChunkingService {
    static final int TARGET_CHARS = 2_400;
    static final int MAX_CHARS = 3_600;
    static final int OVERLAP_CHARS = 240;

    public List<DocumentChunk> chunk(Long documentId, List<DocumentSegment> segments) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 0;
        for (DocumentSegment segment : segments) {
            String remaining = segment.text().strip();
            while (!remaining.isBlank()) {
                int cut = chooseCut(remaining);
                String content = remaining.substring(0, cut).strip();
                DocumentChunk chunk = new DocumentChunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkKey("doc-" + documentId + ":" + segment.segmentId() + ":c" + index++);
                chunk.setHeadingPath(segment.headingPath());
                chunk.setPageNumber(segment.pageNumber());
                chunk.setSlideNumber(segment.slideNumber());
                chunk.setParagraphIndex(segment.paragraphIndex());
                chunk.setLineStart(segment.lineStart());
                chunk.setLineEnd(segment.lineEnd());
                chunk.setContent(content);
                chunk.setSummary(content.length() > 180 ? content.substring(0, 180) + "…" : content);
                chunk.setKeywordsJson("[]");
                chunk.setContentHash(sha256(content));
                chunk.setEmbeddingVersion(1);
                chunks.add(chunk);
                if (cut >= remaining.length()) break;
                remaining = remaining.substring(Math.max(0, cut - OVERLAP_CHARS));
            }
        }
        return chunks;
    }

    private int chooseCut(String text) {
        if (text.length() <= TARGET_CHARS) return text.length();
        int preferred = Math.min(TARGET_CHARS, text.length());
        int lowerBound = Math.max(1, preferred * 3 / 5);
        for (int i = preferred; i >= lowerBound; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.') return i;
        }
        int upperBound = Math.min(MAX_CHARS, text.length());
        for (int i = preferred + 1; i <= upperBound; i++) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.') return i;
        }
        return upperBound;
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
