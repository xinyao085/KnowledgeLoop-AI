package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.example.server.service.LearningProgressService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/learning")
public class LearningProgressController {
    private final LearningProgressService service;
    public LearningProgressController(LearningProgressService service) { this.service = service; }

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.dashboard(userId));
    }
    @GetMapping("/knowledge-map")
    public Result<List<Map<String, Object>>> knowledgeMap(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.knowledgeMap(userId));
    }
    @GetMapping("/review-items")
    public Result<List<Map<String, Object>>> reviews(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.dueReviews(userId));
    }
    @GetMapping("/wrong-answers")
    public Result<List<Map<String, Object>>> wrong(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(service.wrongAnswers(userId));
    }
}
