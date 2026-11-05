package com.example.server.service;

import com.example.server.dto.DocumentSegment;
import com.example.server.entity.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkingServiceTest {
    private final DocumentChunkingService service = new DocumentChunkingService();

    @Test
    void keepsGeneratedImageInterpretationOutsideOriginalEvidence() {
        var segment = new com.example.server.dto.DocumentSegment("figure-1", "chapter", 1, null,
                null, null, null, "原文图注\n[AI 图片解释] 模块 A 调用模块 B", java.util.List.of("image.png"), java.util.List.of());
        var chunks = new DocumentChunkingService().chunk(1L, java.util.List.of(segment));
        org.assertj.core.api.Assertions.assertThat(chunks.getFirst().getContent()).isEqualTo("原文图注");
        org.assertj.core.api.Assertions.assertThat(chunks.getFirst().getImageDescription()).isEqualTo("模块 A 调用模块 B");
        org.assertj.core.api.Assertions.assertThat(chunks.getFirst().getImageRefsJson()).contains("image.png");
    }

    @Test
    void largeNaturalBlockIsSplitWithStableMetadataAndOverlap() {
        String text = "知识点。".repeat(900);
        DocumentSegment segment = new DocumentSegment("page-3", "第三章", 3, null,
                null, null, null, text, List.of(), List.of());

        List<DocumentChunk> chunks = service.chunk(9L, List.of(segment));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getChunkKey()).startsWith("doc-9:page-3:c");
            assertThat(chunk.getPageNumber()).isEqualTo(3);
            assertThat(chunk.getHeadingPath()).isEqualTo("第三章");
            assertThat(chunk.getContent().length()).isLessThanOrEqualTo(DocumentChunkingService.MAX_CHARS);
            assertThat(chunk.getContentHash()).hasSize(64);
        });
    }
}
