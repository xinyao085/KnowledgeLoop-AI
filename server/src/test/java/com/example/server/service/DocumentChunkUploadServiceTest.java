package com.example.server.service;

import com.example.server.entity.Document;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.UploadSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DocumentChunkUploadServiceTest {
    @TempDir Path root;
    final String id = "12345678-1234-1234-1234-123456789abc";
    UploadSessionMapper mapper;
    DocumentIngestService ingest;
    DocumentService documents;
    StringRedisTemplate redis;
    RLock lock;
    DocumentChunkUploadService service;
    Map<String, Object> session;

    @BeforeEach void setup() throws Exception {
        mapper = mock(UploadSessionMapper.class);
        ingest = mock(DocumentIngestService.class);
        documents = mock(DocumentService.class);
        redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        RedissonClient redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        session = new HashMap<>(Map.of("userId", 7L, "totalChunks", 2, "totalSize", 6L,
                "chunkSize", 3, "fingerprint", fingerprint("abc", "def"),
                "status", "UPLOADING", "filename", "book.txt", "uploadId", id));
        when(mapper.find(id)).thenAnswer(ignored -> new HashMap<>(session));
        service = new DocumentChunkUploadService(mapper, ingest, documents, redis, redisson, root.toString());
    }

    @Test void statusRebuildsSparseIndexesFromDiskWithoutRedisCache() throws Exception {
        service.chunk(id, 1, part("def"), 7L);
        assertEquals(List.of(1), service.status(id, 7L).get("uploadedChunks"));
        assertEquals(1, service.status(id, 7L).get("receivedChunks"));
        verify(redis.opsForSet(), atLeastOnce()).add("document:upload:" + id + ":parts", "1");
    }

    @Test void duplicateChunkIsIdempotentButDifferentContentRejected() throws Exception {
        service.chunk(id, 0, part("abc"), 7L);
        service.chunk(id, 0, part("abc"), 7L);
        assertThrows(BusinessException.class, () -> service.chunk(id, 0, part("xyz"), 7L));
        assertEquals("abc", Files.readString(root.resolve(id).resolve("00000000.part")));
        assertEquals(List.of(0), service.status(id, 7L).get("uploadedChunks"));
    }

    @Test void rejectsWrongSizeAndIncompleteMerge() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.chunk(id, 0, part("ab"), 7L));
        service.chunk(id, 1, part("def"), 7L);
        assertThrows(BusinessException.class, () -> service.complete(id, 7L));
        verifyNoInteractions(ingest);
    }

    @Test void failedIngestionPreservesPartsAndRetryCompletesExactlyOnce() throws Exception {
        service.chunk(id, 0, part("abc"), 7L);
        service.chunk(id, 1, part("def"), 7L);
        when(ingest.completeUpload(any(), eq(7L), eq(id))).thenThrow(new IllegalStateException("MinIO unavailable"));
        assertThrows(IllegalStateException.class, () -> service.complete(id, 7L));
        assertEquals(List.of(0, 1), service.status(id, 7L).get("uploadedChunks"));
        Document document = new Document(); document.setId(42L);
        doAnswer(call -> {
            assertEquals("abcdef", new String(((MultipartFile) call.getArgument(0)).getBytes(), StandardCharsets.UTF_8));
            session.put("status", "COMPLETED"); session.put("documentId", 42L);
            return document;
        }).when(ingest).completeUpload(any(), eq(7L), eq(id));
        when(documents.requireOwned(42L, 7L)).thenReturn(document);
        assertSame(document, service.complete(id, 7L));
        assertFalse(Files.exists(root.resolve(id)));
        assertSame(document, service.complete(id, 7L));
        verify(ingest, times(2)).completeUpload(any(), eq(7L), eq(id));
    }

    @Test void fingerprintMismatchRequestsRetransmissionWithoutCreatingDocument() throws Exception {
        service.chunk(id, 0, part("xyz"), 7L);
        service.chunk(id, 1, part("def"), 7L);
        assertThrows(BusinessException.class, () -> service.complete(id, 7L));
        assertEquals(List.of(), service.status(id, 7L).get("uploadedChunks"));
        verifyNoInteractions(ingest);
    }

    @Test void rejectsOtherUserAndBusySessionBeforeWriting() throws Exception {
        assertThrows(SecurityException.class, () -> service.chunk(id, 0, part("abc"), 8L));
        when(lock.tryLock()).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.chunk(id, 0, part("abc"), 7L));
        assertFalse(Files.exists(root.resolve(id)));
        verify(lock, never()).unlock();
    }

    @Test void interruptedTemporaryFilesAreNotAcknowledged() throws Exception {
        Files.createDirectories(root.resolve(id));
        Files.writeString(root.resolve(id).resolve("incoming-interrupted.tmp"), "abc");
        assertEquals(List.of(), service.status(id, 7L).get("uploadedChunks"));
    }

    @Test void truncatedPartIsMissingAndCanBeRepaired() throws Exception {
        Files.createDirectories(root.resolve(id));
        Files.writeString(root.resolve(id).resolve("00000000.part"), "ab");
        assertEquals(List.of(), service.status(id, 7L).get("uploadedChunks"));
        service.chunk(id, 0, part("abc"), 7L);
        assertEquals(List.of(0), service.status(id, 7L).get("uploadedChunks"));
    }

    MockMultipartFile part(String content) {
        return new MockMultipartFile("file", content.getBytes(StandardCharsets.UTF_8));
    }
    String fingerprint(String... parts) throws Exception {
        StringBuilder hashes = new StringBuilder();
        for (String part : parts) hashes.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(part.getBytes(StandardCharsets.UTF_8))));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(hashes.toString().getBytes(StandardCharsets.US_ASCII)));
    }
}
