package com.example.server.consumer;

import com.example.server.dto.DocumentTaskMessage;
import com.example.server.service.DocumentTaskExecutionService;
import org.apache.rocketmq.spring.annotation.*;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic="document-processing-topic",consumerGroup="document-processing-consumer", maxReconsumeTimes=2, consumeTimeout=60L, consumeThreadNumber=4)
public class DocumentTaskConsumer implements RocketMQListener<DocumentTaskMessage> {
    private final DocumentTaskExecutionService processing;
    public DocumentTaskConsumer(DocumentTaskExecutionService processing){this.processing=processing;}
    @Override public void onMessage(DocumentTaskMessage message){processing.process(message.documentId());}
}
