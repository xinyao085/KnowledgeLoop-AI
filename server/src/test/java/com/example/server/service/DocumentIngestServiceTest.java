package com.example.server.service;

import com.example.server.entity.Document;
import com.example.server.mapper.DocumentMapper;
import com.example.server.mapper.UploadSessionMapper;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DocumentIngestServiceTest {
    DocumentMapper documents;
    UploadSessionMapper uploads;
    MinioUtils minio;
    DocumentTaskDispatcher dispatcher;
    DocumentIngestService service;
    TestTransactionManager transaction;
    MockMultipartFile file = new MockMultipartFile("file", "book.txt", "text/plain", "text".getBytes());

    @BeforeEach void setup() throws Exception {
        documents = mock(DocumentMapper.class);
        uploads = mock(UploadSessionMapper.class);
        minio = mock(MinioUtils.class);
        dispatcher = mock(DocumentTaskDispatcher.class);
        DocumentService normalizer = mock(DocumentService.class);
        when(normalizer.normalizeFilename("book.txt")).thenReturn("book.txt");
        when(minio.uploadFile(any())).thenReturn("http://minio/bucket/object.txt");
        when(documents.insert(any(Document.class))).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            ((Document) call.getArgument(0)).setId(42L);
            return 1;
        });
        transaction = new TestTransactionManager();
        ProxyFactory factory = new ProxyFactory(new DocumentIngestService(documents, normalizer, dispatcher, minio, uploads));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transaction, new AnnotationTransactionAttributeSource()));
        service = (DocumentIngestService) factory.getProxy();
    }

    @Test void mapsSessionBeforeCommitAndDispatchesOnlyAfterCommit() throws Exception {
        doAnswer(call -> {
            assertFalse(transaction.committed);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            verifyNoInteractions(dispatcher);
            return null;
        }).when(uploads).complete("upload", 42L);
        doAnswer(call -> { assertTrue(transaction.committed); return null; }).when(dispatcher).dispatch(42L);
        assertEquals(42L, service.completeUpload(file, 7L, "upload").getId());
        verify(uploads).complete("upload", 42L);
        verify(dispatcher).dispatch(42L);
        verify(minio, never()).removeFile(anyString());
    }

    @Test void mappingFailureRollsBackAndRemovesObjectWithoutDispatching() {
        doThrow(new IllegalStateException("database failure")).when(uploads).complete("upload", 42L);
        assertThrows(IllegalStateException.class, () -> service.completeUpload(file, 7L, "upload"));
        assertTrue(transaction.rolledBack);
        assertFalse(transaction.committed);
        verifyNoInteractions(dispatcher);
        verify(minio).removeFile("http://minio/bucket/object.txt");
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed, rolledBack;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
