package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.CourseRequest;
import com.example.server.entity.Course;
import com.example.server.mapper.CourseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CourseService {
    private final CourseMapper mapper;
    private final DocumentService documents;
    public CourseService(CourseMapper mapper, DocumentService documents) { this.mapper=mapper; this.documents=documents; }

    @Transactional
    public Course create(CourseRequest request, Long userId) {
        Course course=new Course(); course.setUserId(userId); course.setTitle(request.title().trim());
        course.setDescription(request.description()); course.setCreatedAt(LocalDateTime.now()); course.setUpdatedAt(LocalDateTime.now());
        mapper.insert(course);
        int order=0;
        for (Long id : request.documentIds()==null?List.<Long>of():request.documentIds()) {
            documents.requireOwned(id,userId); mapper.addDocument(course.getId(),id,order++);
        }
        return course;
    }
    public List<Course> list(Long userId) { return mapper.selectList(new LambdaQueryWrapper<Course>().eq(Course::getUserId,userId).orderByDesc(Course::getUpdatedAt)); }
    public Course requireOwned(Long id, Long userId) {
        Course course=mapper.selectById(id);
        if(course==null || !course.getUserId().equals(userId)) throw new IllegalArgumentException("课程不存在");
        return course;
    }
    public List<Long> documentIds(Long id,Long userId){ requireOwned(id,userId); return mapper.documentIds(id); }
}
