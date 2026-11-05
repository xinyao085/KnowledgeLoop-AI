package com.example.server.service;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class DocumentTaskExecutionServiceTest {
    @Test void releasesLockAndPropagatesFailureForMqRetry() {
        var redis=mock(RedissonClient.class);var lock=mock(RLock.class);
        var processing=mock(DocumentProcessingService.class);
        when(redis.getLock(anyString())).thenReturn(lock);when(lock.tryLock()).thenReturn(true);
        doThrow(new IllegalStateException("failed")).when(processing).process(1L);
        assertThatThrownBy(()->new DocumentTaskExecutionService(redis,processing).process(1L)).isInstanceOf(IllegalStateException.class);
        verify(lock).unlock();
    }
    @Test void concurrentDuplicateIsRetriedInsteadOfAcknowledged() {
        var redis=mock(RedissonClient.class);var lock=mock(RLock.class);var processing=mock(DocumentProcessingService.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        assertThatThrownBy(()->new DocumentTaskExecutionService(redis,processing).process(1L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(processing);verify(lock,never()).unlock();
    }
}
