package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("document_chunks")
public class DocumentChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private String chunkKey;
    private String headingPath;
    private Integer pageNumber;
    private Integer slideNumber;
    private Integer paragraphIndex;
    private Integer lineStart;
    private Integer lineEnd;
    private String content;
    private String summary;
    private String keywordsJson;
    private String contentHash;
    private Integer embeddingVersion;
}
