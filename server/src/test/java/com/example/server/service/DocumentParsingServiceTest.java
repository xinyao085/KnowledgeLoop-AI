package com.example.server.service;

import com.example.server.dto.DocumentContext;
import com.example.server.dto.DocumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParsingServiceTest {
    @TempDir Path directory;
    private final DocumentParsingService service = new DocumentParsingService();

    @Test
    void markdownPreservesHeadingPathAndLineLocator() throws Exception {
        Path file = directory.resolve("lesson.md");
        Files.writeString(file, "# 树结构\n\n导言。\n\n## 二叉树\n\n前序遍历从根开始。\n");

        DocumentContext context = service.parse(18L, "source", "lesson.md", file);

        assertThat(context.type()).isEqualTo(DocumentType.MARKDOWN);
        assertThat(context.title()).isEqualTo("树结构");
        assertThat(context.segments()).hasSize(2);
        assertThat(context.segments().get(1).headingPath()).isEqualTo("树结构 > 二叉树");
        assertThat(context.segments().get(1).lineStart()).isEqualTo(7);
    }

    @Test
    void textPreservesLineRanges() throws Exception {
        Path file = directory.resolve("lesson.txt");
        Files.writeString(file, "第一段\n仍是第一段\n\n第二段\n");

        DocumentContext context = service.parse(2L, "source", "lesson.txt", file);

        assertThat(context.segments()).hasSize(2);
        assertThat(context.segments().get(0).lineStart()).isEqualTo(1);
        assertThat(context.segments().get(0).lineEnd()).isEqualTo(2);
        assertThat(context.segments().get(1).lineStart()).isEqualTo(4);
    }
}
