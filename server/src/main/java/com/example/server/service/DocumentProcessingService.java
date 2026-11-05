package com.example.server.service;

import com.example.server.dto.DocumentContext;
import com.example.server.dto.DocumentSegment;
import com.example.server.entity.Document;
import com.example.server.entity.DocumentChunk;
import com.example.server.mapper.DocumentMapper;
import com.example.server.mapper.DocumentCheckpointMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final DocumentChunkingService chunkingService;
    private final MinioUtils minioUtils;
    private final ObjectMapper objectMapper;
    private final DocumentRetrievalService retrievalService;
    private final DocumentCheckpointMapper checkpointMapper;
    private final DocumentEventService eventService;
    private final DocumentChunkPersistenceService chunkPersistence;
    private final StructuredDocumentService structured;
    private final java.util.concurrent.Executor branches;

    public DocumentProcessingService(DocumentMapper documentMapper,
                                     DocumentChunkingService chunkingService,
                                     MinioUtils minioUtils, ObjectMapper objectMapper,
                                     DocumentRetrievalService retrievalService, DocumentCheckpointMapper checkpointMapper,
                                     DocumentEventService eventService, DocumentChunkPersistenceService chunkPersistence,
                                     StructuredDocumentService structured,
                                     @org.springframework.beans.factory.annotation.Qualifier("documentBranchExecutor") java.util.concurrent.Executor branches) {
        this.documentMapper = documentMapper;
        this.chunkingService = chunkingService;
        this.minioUtils = minioUtils;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.checkpointMapper = checkpointMapper;
        this.eventService = eventService;
        this.chunkPersistence = chunkPersistence;
        this.structured = structured;
        this.branches = branches;
    }

    public void process(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null || "READY".equals(document.getStatus())) return;
        Path temporary = null;
        try {
            updateStatus(document, "EXTRACTING", null, 15);
            temporary = Files.createTempFile("knowledge-loop-document-", suffix(document.getFilename()));
            try (OutputStream output = Files.newOutputStream(temporary)) {
                minioUtils.copyManagedFileTo(document.getFilePath(), output);
            }
            DocumentContext context = structured.parse(documentId, document.getFilePath(),
                    document.getFilename(), temporary);
            updateStatus(document, "CHUNKING", null, 50);
            List<DocumentChunk> chunks = chunkingService.chunk(documentId, context.segments());
            chunkPersistence.replace(documentId, chunks);

            updateStatus(document, "EMBEDDING", null, 75);
            java.util.concurrent.CompletableFuture<DocumentContext> images = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return structured.enrich(context); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }, branches);
            boolean indexed;
            DocumentContext enriched;
            try {
                indexed = retrievalService.index(documentId, chunks);
                enriched = images.get(30, java.util.concurrent.TimeUnit.MINUTES);
            } finally { images.cancel(true); }
            if (!enriched.equals(context)) {
                chunks = chunkingService.chunk(documentId, enriched.segments());
                chunkPersistence.replace(documentId, chunks);
                boolean imageIndexReady = retrievalService.index(documentId,
                        chunks.stream().filter(chunk -> chunk.getImageDescription() != null).toList());
                indexed = indexed && imageIndexReady;
            }
            boolean imagesIncomplete = structured.hasPendingImages(enriched);

            document.setTitle(context.title());
            document.setPageCount(context.type().name().equals("PDF")
                    ? context.segments().stream().map(DocumentSegment::pageNumber).filter(n -> n != null).max(Integer::compareTo).orElse(0)
                    : null);
            document.setOutlineJson(objectMapper.writeValueAsString(context.segments().stream()
                    .map(DocumentSegment::headingPath).filter(value -> value != null && !value.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))));
            updateStatus(document, indexed && !imagesIncomplete ? "READY" : "READY_WITH_WARNINGS",
                    !indexed ? "向量索引暂不可用，已启用关键词检索降级" : imagesIncomplete ? "部分图片尚未完成语义解释，可重新处理重试" : null, 100);
        } catch (Exception e) {
            log.error("document_processing_failed documentId={}", documentId, e);
            updateStatus(document, "FAILED", safeMessage(e), 100);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Document processing failed: " + documentId, e);
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
