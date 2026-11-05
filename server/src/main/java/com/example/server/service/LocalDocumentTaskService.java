package com.example.server.service;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.slf4j.LoggerFactory;
@Service
public class LocalDocumentTaskService {
    private final DocumentTaskExecutionService processing;
    public LocalDocumentTaskService(DocumentTaskExecutionService processing) { this.processing = processing; }
    @Async("documentTaskExecutor")
    public void process(Long id) {
        try { processing.process(id); }
        catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).error("local_document_failed id={}", id, e); }
    }
}
