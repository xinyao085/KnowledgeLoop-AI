package com.example.server.service;

import com.example.server.mapper.LearningProgressMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LearningProgressService {
    private final LearningProgressMapper mapper;

    public LearningProgressService(LearningProgressMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> dashboard(Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overview", mapper.overview(userId));
        result.put("knowledgeMap", mapper.mastery(userId));
        result.put("dueReviews", mapper.dueReviews(userId));
        result.put("wrongAnswers", mapper.wrongAnswers(userId));
        return result;
    }

    public List<Map<String, Object>> knowledgeMap(Long userId) { return mapper.mastery(userId); }
    public List<Map<String, Object>> dueReviews(Long userId) { return mapper.dueReviews(userId); }
    public List<Map<String, Object>> wrongAnswers(Long userId) { return mapper.wrongAnswers(userId); }
}
