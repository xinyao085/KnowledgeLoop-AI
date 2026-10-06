package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.*;
import com.example.server.service.AuthService;
import com.example.server.service.LearningSessionService;
import com.example.server.service.TaskEventService;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/learning/sessions")
public class LearningController {
    private final LearningSessionService service;
    private final TaskEventService events;

    public LearningController(LearningSessionService service,TaskEventService events) { this.service = service;this.events=events; }

    @PostMapping
    public ResponseEntity<Result<LearningSessionView>> create(@Valid @RequestBody CreateLearningSessionRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return ResponseEntity.accepted().body(Result.ok(service.create(request, userId)));
    }

    @GetMapping("/{id}")
    public Result<LearningSessionView> get(@PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.get(id, userId));
    }

    @PostMapping("/{id}/answers")
    public Result<LearningSessionView> answer(@PathVariable Long id, @Valid @RequestBody LearningAnswerRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.answer(id, request, userId));
    }

    @PostMapping("/{id}/next")
    public Result<LearningSessionView> next(@PathVariable Long id, @Valid @RequestBody LearningNextRequest request,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.next(id, request, userId));
    }

    @GetMapping(value="/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long id,@RequestAttribute(AuthService.REQUEST_USER_ID)Long userId){
        LearningSessionView session=service.get(id,userId);
        TaskStatus.State state="COMPLETED".equals(session.status())?TaskStatus.State.COMPLETED:TaskStatus.State.PROCESSING;
        TaskStage stage="WAITING_FOR_ANSWER".equals(session.status())?TaskStage.WAITING_FOR_ANSWER:TaskStage.REPLANNING;
        return events.subscribeLearning(id,TaskStatus.of(state,session.decisionReason()),stage);
    }
}
