package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("documents")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String filename;
    private String documentType;
    private String mimeType;
    private String status;
    private String filePath;
    private String contentHash;
    private Integer parserVersion;
    private String title;
    private String outlineJson;
    private Integer pageCount;
    private String warningJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
