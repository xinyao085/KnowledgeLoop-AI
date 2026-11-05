package com.example.server.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.DocumentChunk;
import com.example.server.mapper.DocumentChunkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service
public class DocumentChunkPersistenceService {
    private final DocumentChunkMapper mapper;
    public DocumentChunkPersistenceService(DocumentChunkMapper mapper) { this.mapper = mapper; }
    @Transactional
    public void replace(Long id, List<DocumentChunk> chunks) {
        var existing = mapper.selectList(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, id))
                .stream().collect(java.util.stream.Collectors.toMap(DocumentChunk::getChunkKey, DocumentChunk::getId));
        for (DocumentChunk chunk : chunks) {
            Long previousId = existing.get(chunk.getChunkKey());
            if (previousId == null) mapper.insert(chunk);
            else { chunk.setId(previousId); mapper.updateById(chunk); }
        }
        if (!chunks.isEmpty()) mapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, id).notIn(DocumentChunk::getChunkKey,
                        chunks.stream().map(DocumentChunk::getChunkKey).toList()));
    }
}
