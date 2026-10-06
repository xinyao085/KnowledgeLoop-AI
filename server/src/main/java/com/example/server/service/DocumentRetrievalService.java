package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.DocumentChunk;
import com.example.server.mapper.DocumentChunkMapper;
import com.example.server.utils.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(DocumentRetrievalService.class);
    private final DocumentChunkMapper chunkMapper;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;

    public DocumentRetrievalService(DocumentChunkMapper chunkMapper, EmbeddingUtils embeddingUtils,
                                    QdrantVectorStore vectorStore) {
        this.chunkMapper = chunkMapper;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
    }

    public boolean index(Long documentId, List<DocumentChunk> chunks) {
        try {
            List<QdrantVectorStore.DocumentVector> vectors = chunks.stream()
                    .map(chunk -> new QdrantVectorStore.DocumentVector(chunk,
                            embeddingUtils.embed(indexText(chunk))))
                    .toList();
            vectorStore.upsertDocument(documentId, vectors);
            return true;
        } catch (RuntimeException e) {
            log.warn("document_vector_index_fallback documentId={}", documentId, e);
            return false;
        }
    }

    public List<DocumentChunk> retrieve(Long documentId, String query, int limit) {
        List<DocumentChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
        if (chunks.isEmpty()) return List.of();
        Map<String, Double> semantic = semanticScores(documentId, query, limit * 2);
        Set<String> terms = terms(query);
        return chunks.stream()
                .map(chunk -> Map.entry(chunk, score(chunk, terms, semantic)))
                .sorted(Map.Entry.<DocumentChunk, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().getId()))
                .limit(Math.max(1, limit))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, Double> semanticScores(Long documentId, String query, int limit) {
        try {
            return vectorStore.searchDocument(documentId, embeddingUtils.embed(query), limit).stream()
                    .collect(Collectors.toMap(QdrantVectorStore.DocumentVectorHit::chunkKey,
                            QdrantVectorStore.DocumentVectorHit::score, Math::max));
        } catch (RuntimeException e) {
            log.warn("document_vector_search_fallback documentId={}", documentId, e);
            return Map.of();
        }
    }

    private double score(DocumentChunk chunk, Set<String> terms, Map<String, Double> semantic) {
        String text = normalize(indexText(chunk));
        long matches = terms.stream().filter(text::contains).count();
        double keyword = terms.isEmpty() ? 0 : (double) matches / terms.size();
        return semantic.getOrDefault(chunk.getChunkKey(), 0.0) * 0.7 + keyword * 0.3;
    }

    private Set<String> terms(String query) {
        if (query == null) return Set.of();
        return Arrays.stream(query.split("[\\s，。！？、,.;:：；!?]+"))
                .map(this::normalize).filter(term -> term.length() >= 2)
                .limit(12).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String indexText(DocumentChunk chunk) {
        return String.join("\n", Objects.toString(chunk.getHeadingPath(), ""),
                Objects.toString(chunk.getSummary(), ""), Objects.toString(chunk.getContent(), ""));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
