package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.entity.Document;
import com.example.server.entity.DocumentChunk;
import com.example.server.mapper.DocumentQuestionMapper;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class DocumentQuestionService {
    private final DocumentService documents; private final DocumentRetrievalService retrieval;
    private final CourseService courses; private final DocumentQuestionMapper mapper; private final ObjectMapper json;
    private final DeepSeekUtils deepSeekUtils;
    public DocumentQuestionService(DocumentService documents,DocumentRetrievalService retrieval,CourseService courses,
                                   DocumentQuestionMapper mapper,ObjectMapper json,DeepSeekUtils deepSeekUtils){this.documents=documents;this.retrieval=retrieval;this.courses=courses;this.mapper=mapper;this.json=json;this.deepSeekUtils=deepSeekUtils;}

    public DocumentAnswer askDocument(Long documentId,DocumentQuestionRequest request,Long userId){
        Document document=documents.requireOwned(documentId,userId);
        return answer(documentId,null,List.of(document),request,userId);
    }
    public DocumentAnswer askCourse(Long courseId,DocumentQuestionRequest request,Long userId){
        List<Document> docs=courses.documentIds(courseId,userId).stream().map(id->documents.requireOwned(id,userId)).toList();
        return answer(null,courseId,docs,request,userId);
    }
    private DocumentAnswer answer(Long documentId,Long courseId,List<Document> docs,DocumentQuestionRequest request,Long userId){
        if(docs.isEmpty()) throw new IllegalStateException("课程中没有可检索文档");
        String conversation=request.conversationId()==null||request.conversationId().isBlank()?UUID.randomUUID().toString():request.conversationId();
        List<Map<String,Object>> history=mapper.history(userId,conversation);
        List<Map<String,Object>> recent=history.stream().skip(Math.max(0,history.size()-4L)).toList();
        String contextualQuery=recent.isEmpty()?request.question():recent.get(recent.size()-1).get("question")+" "+request.question();
        List<DocumentChunk> hits=new ArrayList<>();
        for(Document doc:docs) hits.addAll(retrieval.retrieve(doc.getId(),contextualQuery,3));
        hits=hits.stream().limit(6).toList();
        if(hits.isEmpty()) throw new IllegalStateException("没有找到相关原文");
        Map<Long,Document> byId=new HashMap<>(); docs.forEach(d->byId.put(d.getId(),d));
        List<DocumentEvidence> evidence=hits.stream().map(c->new DocumentEvidence(c.getDocumentId(),c.getChunkKey(),
                byId.get(c.getDocumentId()).getFilename(),c.getHeadingPath(),c.getPageNumber(),c.getSlideNumber(),
                c.getParagraphIndex(),c.getLineStart(),c.getLineEnd(),excerpt(c.getContent()),"回答依据")).toList();
        String answer; String executionMode;
        try {
            answer=deepSeekUtils.answerDocumentQuestion(request.question(),recent,hits);
            executionMode="AI";
        } catch(RuntimeException error) {
            answer="模型当前不可用，以下是检索到的相关原文：\n\n"+hits.stream().map(c->"- "+excerpt(c.getContent())).reduce((a,b)->a+"\n- "+b).orElse("");
            executionMode="FALLBACK";
        }
        try{mapper.insert(userId,documentId,courseId,conversation,request.question(),answer,json.writeValueAsString(evidence));}
        catch(Exception e){throw new IllegalStateException("保存问答记录失败",e);}
        return new DocumentAnswer(conversation,answer,evidence,executionMode);
    }
    private String excerpt(String text){return text.length()>500?text.substring(0,500)+"…":text;}
    public List<Map<String,Object>> history(String conversationId,Long userId){return mapper.history(userId,conversationId);}
}
