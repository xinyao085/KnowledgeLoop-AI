package com.example.server.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentEventService {
    private final Map<Long,List<SseEmitter>> emitters=new ConcurrentHashMap<>();
    public SseEmitter subscribe(Long documentId,Object initial){
        SseEmitter emitter=new SseEmitter(30L*60*1000);
        emitters.computeIfAbsent(documentId,key->new java.util.concurrent.CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(()->remove(documentId,emitter)); emitter.onTimeout(()->remove(documentId,emitter));
        Object initialData=initial==null?Map.of("documentId",documentId,"stage","QUEUED","progress",0,"detail",""):initial;
        try{emitter.send(SseEmitter.event().name("progress").data(initialData));}catch(IOException e){remove(documentId,emitter);}
        return emitter;
    }
    public void publish(Long documentId,String stage,int progress,String detail){
        Map<String,Object> data=Map.of("documentId",documentId,"stage",stage,"progress",progress,"detail",detail==null?"":detail);
        for(SseEmitter emitter:emitters.getOrDefault(documentId,List.of())) try{
            emitter.send(SseEmitter.event().name("progress").data(data));
            if(progress>=100) emitter.complete();
        }catch(Exception e){remove(documentId,emitter);}
    }
    private void remove(Long id,SseEmitter emitter){List<SseEmitter> list=emitters.get(id);if(list!=null){list.remove(emitter);if(list.isEmpty())emitters.remove(id);}}
}
