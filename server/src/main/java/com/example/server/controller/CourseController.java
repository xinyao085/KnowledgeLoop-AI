package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.*;
import com.example.server.entity.Course;
import com.example.server.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/courses")
public class CourseController {
    private final CourseService courses; private final DocumentQuestionService questions;
    public CourseController(CourseService courses,DocumentQuestionService questions){this.courses=courses;this.questions=questions;}
    @PostMapping public Result<Course> create(@Valid @RequestBody CourseRequest request,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId){return Result.ok(courses.create(request,userId));}
    @GetMapping public Result<List<Course>> list(@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId){return Result.ok(courses.list(userId));}
    @GetMapping("/{id}/documents") public Result<List<Long>> documents(@PathVariable Long id,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId){return Result.ok(courses.documentIds(id,userId));}
    @PostMapping("/{id}/questions") public Result<DocumentAnswer> ask(@PathVariable Long id,@Valid @RequestBody DocumentQuestionRequest request,@RequestAttribute(AuthService.REQUEST_USER_ID) Long userId){return Result.ok(questions.askCourse(id,request,userId));}
}
