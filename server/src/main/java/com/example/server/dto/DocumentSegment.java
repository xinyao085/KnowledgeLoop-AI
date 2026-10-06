package com.example.server.dto;

import java.util.List;

public record DocumentSegment(
        String segmentId,
        String headingPath,
        Integer pageNumber,
        Integer slideNumber,
        Integer paragraphIndex,
        Integer lineStart,
        Integer lineEnd,
        String text,
        List<String> imageRefs,
        List<String> tableRefs
) { }
