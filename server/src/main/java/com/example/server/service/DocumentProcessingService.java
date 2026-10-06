package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.DocumentContext;
import com.example.server.dto.DocumentSegment;
import com.example.server.entity.Document;
import com.example.server.entity.DocumentChunk;
import com.example.server.mapper.DocumentChunkMapper;
import com.example.server.mapper.DocumentMapper;
import com.example.server.mapper.DocumentCheckpointMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class DocumentProcessingService {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentParsingService parsingService;
    private final DocumentChunkingService chunkingService;
    private final MinioUtils minioUtils;
    private final ObjectMapper objectMapper;
    private final DocumentRetrievalService retrievalService;
    private final DocumentCheckpointMapper checkpointMapper;
    private final DocumentEventService eventService;

    public DocumentProcessingService(DocumentMapper documentMapper, DocumentChunkMapper chunkMapper,
                                     DocumentParsingService parsingService, DocumentChunkingService chunkingService,
                                     MinioUtils minioUtils, ObjectMapper objectMapper,
                                     DocumentRetrievalService retrievalService, DocumentCheckpointMapper checkpointMapper,
                                     DocumentEventService eventService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.parsingService = parsingService;
        this.chunkingService = chunkingService;
        this.minioUtils = minioUtils;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.checkpointMapper = checkpointMapper;
        this.eventService = eventService;
    }

    @Async("documentTaskExecutor")
    public void process(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) return;
        Path temporary = null;
        try {
            List<DocumentChunk> existingChunks = chunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                    .eq(DocumentChunk::getDocumentId, documentId));
            if (!existingChunks.isEmpty()) {
                updateStatus(document, "EMBEDDING", "已从分块 Checkpoint 恢复，跳过重复解析", 75);
                boolean indexed = retrievalService.index(documentId, existingChunks);
                updateStatus(document, indexed ? "READY" : "READY_WITH_WARNINGS",
                        indexed ? null : "向量索引暂不可用，已启用关键词检索降级", 100);
                return;
            }
            updateStatus(document, "EXTRACTING", null, 15);
            temporary = Files.createTempFile("knowledge-loop-document-", suffix(document.getFilename()));
            try (OutputStream output = Files.newOutputStream(temporary)) {
                minioUtils.copyManagedFileTo(document.getFilePath(), output);
            }
            DocumentContext context = parsingService.parse(documentId, document.getFilePath(),
                    document.getFilename(), temporary);
            updateStatus(document, "CHUNKING", null, 50);
            List<DocumentChunk> chunks = chunkingService.chunk(documentId, context.segments());
            chunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, documentId));
            chunks.forEach(chunkMapper::insert);

            updateStatus(document, "EMBEDDING", null, 75);
            boolean indexed = retrievalService.index(documentId, chunks);

            document.setTitle(context.title());
            document.setPageCount(context.type().name().equals("PDF")
                    ? context.segments().stream().map(DocumentSegment::pageNumber).filter(n -> n != null).max(Integer::compareTo).orElse(0)
                    : null);
            document.setOutlineJson(objectMapper.writeValueAsString(context.segments().stream()
                    .map(DocumentSegment::headingPath).filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))));
            updateStatus(document, indexed ? "READY" : "READY_WITH_WARNINGS",
                    indexed ? null : "向量索引暂不可用，已启用关键词检索降级", 100);
        } catch (Exception e) {
            log.error("document_processing_failed documentId={}", documentId, e);
            updateStatus(document, "FAILED", safeMessage(e), 100);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    private void updateStatus(Document document, String status, String warning, int progress) {
        document.setStatus(status);
        document.setWarningJson(warning);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
        checkpointMapper.save(document.getId(), status, progress, warning);
        eventService.publish(document.getId(), status, progress, warning);
    }

    private String suffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? ".bin" : filename.substring(dot);
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
