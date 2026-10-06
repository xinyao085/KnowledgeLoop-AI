package com.example.server.service;

import com.example.server.dto.DocumentSegment;
import com.example.server.entity.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkingServiceTest {
    private final DocumentChunkingService service = new DocumentChunkingService();

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
