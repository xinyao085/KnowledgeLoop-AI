package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.DocumentSummary;
import com.example.server.entity.Document;
import com.example.server.entity.DocumentChunk;
import com.example.server.service.AuthService;
import com.example.server.service.DocumentIngestService;
import com.example.server.service.DocumentProcessingService;
import com.example.server.service.DocumentService;
import com.example.server.service.DocumentQuestionService;
import com.example.server.service.DocumentEventService;
import com.example.server.mapper.DocumentCheckpointMapper;
import com.example.server.service.DocumentTaskDispatcher;
import com.example.server.service.DocumentChunkUploadService;
import com.example.server.dto.InitUploadRequest;
import com.example.server.dto.DocumentAnswer;
import com.example.server.dto.DocumentQuestionRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/documents")
public class DocumentController {
    private final DocumentIngestService ingestService;
    private final DocumentService documentService;
    private final DocumentProcessingService processingService;
    private final DocumentQuestionService questionService;
    private final DocumentEventService eventService;
    private final DocumentCheckpointMapper checkpointMapper;
    private final DocumentTaskDispatcher taskDispatcher;
    private final DocumentChunkUploadService chunkUploads;

    public DocumentController(DocumentIngestService ingestService, DocumentService documentService,
                              DocumentProcessingService processingService, DocumentQuestionService questionService,
                              DocumentEventService eventService, DocumentCheckpointMapper checkpointMapper,
                              DocumentTaskDispatcher taskDispatcher, DocumentChunkUploadService chunkUploads) {
        this.ingestService = ingestService;
        this.documentService = documentService;
        this.processingService = processingService;
        this.questionService = questionService;
        this.eventService = eventService;
        this.checkpointMapper = checkpointMapper;
        this.taskDispatcher = taskDispatcher;
        this.chunkUploads = chunkUploads;
    }

    @PostMapping("/init-upload")
    public Result<java.util.Map<String,Object>> initUpload(@Valid @RequestBody InitUploadRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return Result.ok(chunkUploads.init(request,userId));
    }
    @GetMapping("/upload-status")
    public Result<java.util.Map<String,Object>> uploadStatus(@RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws java.io.IOException {return Result.ok(chunkUploads.status(uploadId,userId));}
    @PostMapping("/upload-chunk")
    public Result<java.util.Map<String,Object>> uploadChunk(@RequestParam String uploadId,@RequestParam int index,
            @RequestParam("file") MultipartFile file,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId)throws Exception{
        return Result.ok(chunkUploads.chunk(uploadId,index,file,userId));
    }
    @PostMapping("/complete-upload")
    public ResponseEntity<Result<DocumentSummary>> completeUpload(@RequestParam String uploadId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId)throws Exception{
        return ResponseEntity.accepted().body(Result.ok(DocumentSummary.from(chunkUploads.complete(uploadId,userId))));
    }

    @GetMapping("/{id}/process-status")
    public Result<java.util.Map<String,Object>> processStatus(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        documentService.requireOwned(id,userId);
        return Result.ok(checkpointMapper.find(id));
    }

    @GetMapping(value="/{id}/process-events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processEvents(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        documentService.requireOwned(id,userId);
        return eventService.subscribe(id,checkpointMapper.find(id));
    }

    @PostMapping("/{id}/questions")
    public Result<DocumentAnswer> question(@PathVariable Long id, @Valid @RequestBody DocumentQuestionRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(questionService.askDocument(id, request, userId));
    }

    @GetMapping("/questions/{conversationId}")
    public Result<java.util.List<java.util.Map<String,Object>>> questionHistory(@PathVariable String conversationId,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(questionService.history(conversationId, userId));
    }

    @PostMapping("/upload")
    public ResponseEntity<Result<DocumentSummary>> upload(@RequestParam("file") MultipartFile file,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) throws Exception {
        return ResponseEntity.accepted().body(Result.ok(DocumentSummary.from(ingestService.ingest(file, userId))));
    }

    @GetMapping
    public Result<List<DocumentSummary>> list(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(documentService.list(userId));
    }

    @GetMapping("/{id}")
    public Result<DocumentSummary> get(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(DocumentSummary.from(documentService.requireOwned(id, userId)));
    }

    @GetMapping("/{id}/outline")
    public Result<String> outline(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(documentService.requireOwned(id, userId).getOutlineJson());
    }

    @GetMapping("/{id}/content")
    public Result<List<DocumentChunk>> content(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(documentService.chunks(id, userId));
    }

    @PostMapping("/{id}/process")
    public ResponseEntity<Result<DocumentSummary>> process(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        Document document = documentService.requireOwned(id, userId);
        taskDispatcher.dispatch(id);
        return ResponseEntity.accepted().body(Result.ok(DocumentSummary.from(document)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        documentService.delete(id, userId);
        return Result.ok();
    }
}
