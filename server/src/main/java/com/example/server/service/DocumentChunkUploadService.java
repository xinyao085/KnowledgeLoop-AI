package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.InitUploadRequest;
import com.example.server.entity.Document;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.UploadSessionMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Service
public class DocumentChunkUploadService {
    private static final Logger log = LoggerFactory.getLogger(DocumentChunkUploadService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private final UploadSessionMapper mapper;
    private final DocumentIngestService ingest;
    private final DocumentService documents;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final Path root;

    public DocumentChunkUploadService(UploadSessionMapper mapper, DocumentIngestService ingest,
            DocumentService documents, StringRedisTemplate redis, RedissonClient redisson,
            @Value("${document.upload.directory:./data/document-uploads}") String directory) {
        this.mapper = mapper;
        this.ingest = ingest;
        this.documents = documents;
        this.redis = redis;
        this.redisson = redisson;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    public Map<String, Object> init(InitUploadRequest request, Long userId) throws IOException {
        String filename = documents.normalizeFilename(request.filename());
        if (request.totalChunks() != (request.totalSize() - 1) / request.chunkSize() + 1) {
            throw new IllegalArgumentException("文件大小与分片数量不一致");
        }
        String id = UUID.randomUUID().toString();
        Files.createDirectories(root.resolve(id));
        mapper.create(id, userId, filename, request.mimeType(), request.totalChunks(),
                request.totalSize(), request.chunkSize(), request.fingerprint());
        return status(id, userId);
    }

    public Map<String, Object> status(String id, Long userId) throws IOException {
        Map<String, Object> session = owned(id, userId);
        RLock lock = lock(id);
        try {
            session = owned(id, userId);
            if ("COMPLETED".equals(session.get("status"))) return session;
            return snapshot(id, session);
        } finally { lock.unlock(); }
    }

    public Map<String, Object> chunk(String id, int index, MultipartFile chunk, Long userId) throws IOException {
        owned(id, userId);
        RLock lock = lock(id);
        Path temporary = null;
        try {
            Map<String, Object> session = owned(id, userId);
            if ("COMPLETED".equals(session.get("status"))) return session;
            int total = number(session, "totalChunks").intValue();
            if (index < 0 || index >= total || chunk == null || chunk.isEmpty()
                    || chunk.getSize() != expectedSize(session, index)) {
                throw new IllegalArgumentException("分片索引或大小无效");
            }
            Path dir = root.resolve(id);
            Files.createDirectories(dir);
            temporary = Files.createTempFile(dir, "incoming-", ".tmp");
            try (InputStream input = chunk.getInputStream(); OutputStream output = Files.newOutputStream(temporary)) {
                input.transferTo(output);
            }
            if (Files.size(temporary) != expectedSize(session, index)) throw new IOException("分片写入不完整");
            Path target = part(id, index);
            if (Files.exists(target) && Files.size(target) == expectedSize(session, index)) {
                if (Files.mismatch(target, temporary) != -1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "同一分片内容不一致，请重新选择原文件");
                }
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            // Only atomically published .part files count; Redis is a rebuildable index.
            return snapshot(id, session);
        } finally {
            try { if (temporary != null) Files.deleteIfExists(temporary); }
            finally { lock.unlock(); }
        }
    }

    public Document complete(String id, Long userId) throws Exception {
        owned(id, userId);
        RLock lock = lock(id);
        try {
            Map<String, Object> session = owned(id, userId);
            if ("COMPLETED".equals(session.get("status"))) {
                Document document = documents.requireOwned(number(session, "documentId").longValue(), userId);
                cleanup(id);
                return document;
            }
            int total = number(session, "totalChunks").intValue();
            for (int i = 0; i < total; i++) {
                if (!Files.isRegularFile(part(id, i)) || Files.size(part(id, i)) != expectedSize(session, i)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "分片尚未上传完整，请继续上传");
                }
            }
            Path merged = root.resolve(id).resolve("merged.upload");
            MessageDigest fingerprint = digest();
            try (OutputStream output = Files.newOutputStream(merged)) {
                byte[] buffer = new byte[8192];
                for (int i = 0; i < total; i++) {
                    MessageDigest chunkHash = digest();
                    try (InputStream input = Files.newInputStream(part(id, i))) {
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                            chunkHash.update(buffer, 0, read);
                        }
                    }
                    fingerprint.update(HexFormat.of().formatHex(chunkHash.digest()).getBytes(StandardCharsets.US_ASCII));
                }
            }
            if (Files.size(merged) != number(session, "totalSize").longValue()
                    || !HexFormat.of().formatHex(fingerprint.digest()).equals(session.get("fingerprint"))) {
                // Corrupt parts cannot be trusted. The next status query requests all parts again.
                for (int i = 0; i < total; i++) Files.deleteIfExists(part(id, i));
                Files.deleteIfExists(merged);
                snapshot(id, session);
                throw new BusinessException(ErrorCode.CONFLICT, "文件完整性校验失败，请重新选择同一文件修复上传");
            }
            MultipartFile file = new PathMultipartFile(merged, Objects.toString(session.get("filename")),
                    Objects.toString(session.get("mimeType"), "application/octet-stream"));
            // Document insert and uploadId -> documentId mapping commit together before cleanup.
            Document document = ingest.completeUpload(file, userId, id);
            cleanup(id);
            return document;
        } finally { lock.unlock(); }
    }

    private Map<String, Object> snapshot(String id, Map<String, Object> session) throws IOException {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < number(session, "totalChunks").intValue(); index++) {
            Path path = part(id, index);
            if (Files.isRegularFile(path) && Files.size(path) == expectedSize(session, index)) indexes.add(index);
        }
        // Serialize with chunk writes so reconciliation cannot erase a newly acknowledged part.
        redis.delete(partsKey(id));
        if (!indexes.isEmpty()) {
            redis.opsForSet().add(partsKey(id), indexes.stream().map(String::valueOf).toArray(String[]::new));
            redis.expire(partsKey(id), CACHE_TTL);
        }
        redis.opsForHash().putAll(key(id), Map.of("userId", session.get("userId").toString(),
                "totalChunks", session.get("totalChunks").toString(), "status", "UPLOADING"));
        redis.expire(key(id), CACHE_TTL);
        mapper.progress(id, indexes.size());
        Map<String, Object> result = new HashMap<>(session);
        result.put("uploadedChunks", indexes);
        result.put("receivedChunks", indexes.size());
        return result;
    }

    private RLock lock(String id) {
        RLock lock = redisson.getLock("lock:document:upload:" + id);
        // No fixed lease: Redisson watchdog renews while a large merge is running.
        if (!lock.tryLock()) throw new BusinessException(ErrorCode.CONFLICT, "上传正在处理，请稍后重试");
        return lock;
    }

    private Map<String, Object> owned(String id, Long userId) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new NoSuchElementException("上传会话不存在");
        }
        Map<String, Object> session = mapper.find(id);
        if (session == null) throw new NoSuchElementException("上传会话不存在");
        if (!userId.equals(number(session, "userId").longValue())) throw new SecurityException("无权访问上传会话");
        if (session.get("fingerprint") == null) throw new NoSuchElementException("旧上传会话已失效，请重新上传");
        return session;
    }

    private void cleanup(String id) {
        try {
            redis.delete(List.of(key(id), partsKey(id)));
            Path dir = root.resolve(id);
            if (Files.exists(dir)) try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        } catch (Exception error) {
            // Committed success must not be reported as failure because of cache/disk cleanup.
            log.warn("document_upload_cleanup_failed uploadId={}", id, error);
        }
    }

    private long expectedSize(Map<String, Object> session, int index) {
        return Math.min(number(session, "chunkSize").longValue(),
                number(session, "totalSize").longValue() - index * number(session, "chunkSize").longValue());
    }
    private Number number(Map<String, Object> map, String key) { return (Number) map.get(key); }
    private Path part(String id, int index) { return root.resolve(id).resolve(String.format("%08d.part", index)); }
    private String key(String id) { return "document:upload:" + id; }
    private String partsKey(String id) { return key(id) + ":parts"; }
    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private record PathMultipartFile(Path path, String originalFilename, String contentType) implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return originalFilename; }
        public String getContentType() { return contentType; }
        public boolean isEmpty() { return getSize() == 0; }
        public long getSize() { try { return Files.size(path); } catch (IOException error) { throw new UncheckedIOException(error); } }
        public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        public void transferTo(File dest) throws IOException { Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
}
