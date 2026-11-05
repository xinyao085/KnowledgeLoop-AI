package com.example.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class ThreadPoolConfig {

    @Bean("aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        return executor("AI-Thread-", 4, 8, 100);
    }

    @Bean("asrExecutor")
    public ThreadPoolTaskExecutor asrExecutor() {
        return executor("ASR-Thread-", 4, 8, 50);
    }

    @Bean("ocrExecutor")
    public ThreadPoolTaskExecutor ocrExecutor() {
        int cores = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        return executor("OCR-Thread-", cores, cores, 20);
    }

    @Bean("modelCallExecutor")
    public ThreadPoolTaskExecutor modelCallExecutor() {
        return executor("LLM-Thread-", 4, 8, 20);
    }

    @Bean("documentBranchExecutor")
    public ThreadPoolTaskExecutor documentBranchExecutor() {
        return executor("Document-Branch-", 2, 4, 8);
    }

    @Bean("documentImageExecutor")
    public ThreadPoolTaskExecutor documentImageExecutor(
            @Value("${ai.vision.concurrency:2}") int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("Invalid vision concurrency");
        return executor("Document-Image-", concurrency, concurrency, 8);
    }

    @Bean("embeddingExecutor")
    public ThreadPoolTaskExecutor embeddingExecutor(
            @Value("${ai.embedding.index.concurrency:3}") int concurrency,
            @Value("${ai.embedding.index.queue-capacity:12}") int queueCapacity) {
        if (concurrency < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("Invalid embedding executor limits");
        }
        return executor("Embedding-Thread-", concurrency, concurrency, queueCapacity);
    }

    @Bean("documentTaskExecutor")
    public ThreadPoolTaskExecutor documentTaskExecutor() {
        return executor("Document-Thread-", 2, 4, 40);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
