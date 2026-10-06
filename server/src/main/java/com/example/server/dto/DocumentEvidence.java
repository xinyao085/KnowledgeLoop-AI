package com.example.server.dto;

public record DocumentEvidence(Long documentId, String chunkId, String fileName, String headingPath,
                               Integer pageNumber, Integer slideNumber, Integer paragraphIndex,
                               Integer lineStart, Integer lineEnd, String quote, String supportedClaim) { }
