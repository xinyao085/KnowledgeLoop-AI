package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("learning_sessions")
public class LearningSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long documentId;
    private String goal;
    private String status;
    private String currentKnowledgePoint;
    private String nextAction;
    private String stateJson;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
