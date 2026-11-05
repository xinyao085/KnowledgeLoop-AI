package com.example.server.service;
import com.example.server.entity.DocumentChunk;
import com.example.server.mapper.DocumentChunkMapper;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentRetrievalConcurrencyTest {
    @Test void embedsInBoundedParallelBatches() throws Exception {
        var embeddings=mock(EmbeddingUtils.class);
        var vectors=mock(QdrantVectorStore.class);
        var active=new AtomicInteger();var peak=new AtomicInteger();
        var entered=new CountDownLatch(2);
        when(embeddings.embed(anyString())).thenAnswer(invocation->{
            int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);
            try { entered.countDown(); assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();return List.of(1.0); }
            finally { active.decrementAndGet(); }
        });
        try(var pool=Executors.newFixedThreadPool(2)) {
            var service=new DocumentRetrievalService(mock(DocumentChunkMapper.class),embeddings,vectors,pool,2,5);
            assertThat(service.index(1L,chunks(5))).isTrue();
            assertThat(peak.get()).isEqualTo(2);
            verify(embeddings,times(5)).embed(anyString());
            verify(vectors).upsertDocument(eq(1L),argThat(items->items.size()==5));
        }
    }
    @Test void rejectionAndFailureFallBackWithoutPublishingPartialIndex() {
        var embeddings=mock(EmbeddingUtils.class);var vectors=mock(QdrantVectorStore.class);
        Executor rejected=task->{throw new RejectedExecutionException();};
        assertThat(new DocumentRetrievalService(mock(DocumentChunkMapper.class),embeddings,vectors,rejected,2,1)
                .index(1L,chunks(3))).isFalse();
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertThat(new DocumentRetrievalService(mock(DocumentChunkMapper.class),embeddings,vectors,Runnable::run,2,1)
                .index(1L,chunks(3))).isFalse();
        verifyNoInteractions(vectors);
    }
    private List<DocumentChunk> chunks(int count) {
        List<DocumentChunk> chunks=new ArrayList<>();
        for(int i=0;i<count;i++){var chunk=new DocumentChunk();chunk.setContent("chunk "+i);chunks.add(chunk);}return chunks;
    }
}
