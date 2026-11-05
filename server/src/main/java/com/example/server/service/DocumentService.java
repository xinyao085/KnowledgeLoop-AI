package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.DocumentSummary;
import com.example.server.dto.DocumentType;
import com.example.server.entity.Document;
import com.example.server.entity.DocumentChunk;
import com.example.server.mapper.DocumentChunkMapper;
import com.example.server.mapper.DocumentMapper;
import com.example.server.utils.MinioUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
public class DocumentService {
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final MinioUtils minioUtils;
    private final QdrantVectorStore vectorStore;
    private final StructuredDocumentService structured;
    private final org.redisson.api.RedissonClient redis;

    public DocumentService(DocumentMapper documentMapper, DocumentChunkMapper chunkMapper, MinioUtils minioUtils,
                           QdrantVectorStore vectorStore, StructuredDocumentService structured, org.redisson.api.RedissonClient redis) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.minioUtils = minioUtils;
        this.vectorStore = vectorStore;
        this.structured = structured;
        this.redis = redis;
    }

    public String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) throw new IllegalArgumentException("文档文件名不能为空");
        String value = filename.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).trim();
        if (value.isBlank() || value.length() > 255) throw new IllegalArgumentException("文档文件名无效或过长");
        DocumentType.fromFilename(value);
        return value;
    }

    public List<DocumentSummary> list(Long userId) {
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId).orderByDesc(Document::getId))
                .stream().map(DocumentSummary::from).toList();
    }

    public Document requireOwned(Long id, Long userId) {
        Document document = documentMapper.selectById(id);
        if (document == null) throw new NoSuchElementException("文档不存在");
        if (!Objects.equals(document.getUserId(), userId)) throw new SecurityException("无权访问该文档");
        return document;
    }

    public List<DocumentChunk> chunks(Long id, Long userId) {
        requireOwned(id, userId);
        return chunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id).orderByAsc(DocumentChunk::getId));
    }

    public Document queueForProcessing(Long id, Long userId) {
        Document document = requireOwned(id, userId);
        var lock = redis.getLock("document:processing:" + id);
        if (!lock.tryLock()) throw new IllegalStateException("文档正在处理中");
        try {
            document.setStatus("QUEUED");
            document.setUpdatedAt(java.time.LocalDateTime.now());
            documentMapper.updateById(document);
            return document;
        } finally { lock.unlock(); }
    }

    public byte[] image(Long id, Long userId, String source) throws Exception {
        requireOwned(id, userId);
        return structured.image(id, source);
    }

    public void delete(Long id, Long userId) {
        requireOwned(id, userId);
        var lock = redis.getLock("document:processing:" + id);
        if (!lock.tryLock()) throw new IllegalStateException("文档正在处理中，请稍后删除");
        try { deleteLocked(id, userId); } finally { lock.unlock(); }
    }

    private void deleteLocked(Long id, Long userId) {
        Document document = requireOwned(id, userId);
        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, id));
        structured.delete(id);
        documentMapper.deleteById(id);
        vectorStore.deleteDocument(id);
        long references = documentMapper.selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getFilePath, document.getFilePath()));
        if (references == 0) minioUtils.removeFile(document.getFilePath());
    }
}
