package com.example.server.service;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
@Service
public class DocumentTaskExecutionService {
    private final RedissonClient redis;
    private final DocumentProcessingService processing;
    public DocumentTaskExecutionService(RedissonClient redis, DocumentProcessingService processing) {
        this.redis = redis; this.processing = processing;
    }
    public void process(Long id) {
        if (id == null) throw new IllegalArgumentException("Missing document id");
        var lock = redis.getLock("document:processing:" + id);
        if (!lock.tryLock()) throw new IllegalStateException("Document processing already active");
        try { processing.process(id); } finally { lock.unlock(); }
    }
}
