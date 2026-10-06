package com.example.server.consumer;

import com.example.server.dto.DocumentTaskMessage;
import com.example.server.service.DocumentProcessingService;
import org.apache.rocketmq.spring.annotation.*;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic="document-processing-topic",consumerGroup="document-processing-consumer")
public class DocumentTaskConsumer implements RocketMQListener<DocumentTaskMessage> {
    private final DocumentProcessingService processing;
    public DocumentTaskConsumer(DocumentProcessingService processing){this.processing=processing;}
    @Override public void onMessage(DocumentTaskMessage message){processing.process(message.documentId());}
}
