package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.DocumentType;
import com.example.server.entity.Document;
import com.example.server.mapper.DocumentMapper;
import com.example.server.mapper.UploadSessionMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.server.utils.MinioUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class DocumentIngestService {
    private static final int PARSER_VERSION = 1;
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final DocumentTaskDispatcher taskDispatcher;
    private final MinioUtils minioUtils;
    private final UploadSessionMapper uploadSessions;

    public DocumentIngestService(DocumentMapper documentMapper, DocumentService documentService,
                                 DocumentTaskDispatcher taskDispatcher, MinioUtils minioUtils, UploadSessionMapper uploadSessions) {
        this.documentMapper = documentMapper;
        this.documentService = documentService;
        this.taskDispatcher = taskDispatcher;
        this.minioUtils = minioUtils;
        this.uploadSessions = uploadSessions;
    }

    @Transactional(rollbackFor = Exception.class)
    public Document completeUpload(MultipartFile file, Long userId, String uploadId) throws Exception {
        Document document = ingest(file, userId);
        uploadSessions.complete(uploadId, document.getId());
        return document;
    }

    @Transactional(rollbackFor = Exception.class)
    public Document ingest(MultipartFile file, Long userId) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文档不能为空");
        String filename = documentService.normalizeFilename(file.getOriginalFilename());
        DocumentType type = DocumentType.fromFilename(filename);
        String hash;
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            hash = HexFormat.of().formatHex(digest.digest());
        }
        Document existing = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId).eq(Document::getContentHash, hash)
                .eq(Document::getParserVersion, PARSER_VERSION).in(Document::getStatus, "READY", "READY_WITH_WARNINGS")
                .last("LIMIT 1"));
        if (existing != null) return existing;

        String filePath = minioUtils.uploadFile(file);
        Document document = new Document();
        document.setUserId(userId);
        document.setFilename(filename);
        document.setDocumentType(type.name());
        document.setMimeType(file.getContentType());
        document.setStatus("QUEUED");
        document.setFilePath(filePath);
        document.setContentHash(hash);
        document.setParserVersion(PARSER_VERSION);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        try {
            documentMapper.insert(document);
        } catch (RuntimeException e) {
            minioUtils.removeFile(filePath);
            throw e;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { taskDispatcher.dispatch(document.getId()); }
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) minioUtils.removeFile(filePath);
                }
            });
        } else {
            taskDispatcher.dispatch(document.getId());
        }
        return document;
    }
}
