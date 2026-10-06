package com.example.server.service;

import com.example.server.dto.DocumentTaskMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.springframework.stereotype.Service;

@Service
public class DocumentTaskDispatcher {
    public static final String TOPIC="document-processing-topic";
    private final RocketMQTemplate rocketMQ; private final DocumentProcessingService fallback;
    public DocumentTaskDispatcher(RocketMQTemplate rocketMQ,DocumentProcessingService fallback){this.rocketMQ=rocketMQ;this.fallback=fallback;}
    public void dispatch(Long documentId){
        try{rocketMQ.asyncSend(TOPIC,new DocumentTaskMessage(documentId),new SendCallback(){
            public void onSuccess(SendResult result) { }
            public void onException(Throwable error) { fallback.process(documentId); }
        },5000);}
        catch(Exception error){fallback.process(documentId);}
    }
}
