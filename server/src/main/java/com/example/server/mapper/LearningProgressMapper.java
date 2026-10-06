package com.example.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface LearningProgressMapper {
    @Select("SELECT mastery_score FROM knowledge_mastery WHERE user_id=#{userId} AND document_id=#{documentId} AND knowledge_key=#{knowledgeKey}")
    Double findMastery(@Param("userId") Long userId, @Param("documentId") Long documentId,
                       @Param("knowledgeKey") String knowledgeKey);

    @Insert("""
        INSERT INTO knowledge_mastery(user_id, document_id, knowledge_key, mastery_score, weakness_json, next_review_at)
        VALUES(#{userId}, #{documentId}, #{knowledgeKey}, #{score}, #{weakness},
          DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL (CASE WHEN #{score} >= .8 THEN 7 WHEN #{score} >= .5 THEN 3 ELSE 1 END) DAY))
        ON DUPLICATE KEY UPDATE mastery_score=#{score}, weakness_json=#{weakness},
          review_interval_days=CASE WHEN #{score} >= .8 THEN LEAST(review_interval_days * 2, 60)
            WHEN #{score} >= .5 THEN 3 ELSE 1 END,
          next_review_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL review_interval_days DAY),
          updated_at=CURRENT_TIMESTAMP(3)
        """)
    void upsertMastery(@Param("userId") Long userId, @Param("documentId") Long documentId,
                       @Param("knowledgeKey") String knowledgeKey, @Param("score") double score,
                       @Param("weakness") String weakness);

    @Insert("""
        INSERT INTO quiz_attempts(session_id, idempotency_key, knowledge_key, question_json,
          user_answer, evaluation_json, score, evidence_json)
        VALUES(#{sessionId}, #{key}, #{knowledgeKey}, #{question}, #{answer}, #{evaluation}, #{score}, #{evidence})
        """)
    void insertAttempt(@Param("sessionId") Long sessionId, @Param("key") String key,
                       @Param("knowledgeKey") String knowledgeKey, @Param("question") String question,
                       @Param("answer") String answer, @Param("evaluation") String evaluation,
                       @Param("score") double score, @Param("evidence") String evidence);

    @Select("SELECT evaluation_json FROM quiz_attempts WHERE session_id=#{sessionId} AND idempotency_key=#{key}")
    String findEvaluation(@Param("sessionId") Long sessionId, @Param("key") String key);

    @Select("""
        SELECT km.document_id documentId, d.title documentTitle, km.knowledge_key knowledgeKey,
          km.mastery_score masteryScore, km.weakness_json weaknessJson, km.next_review_at nextReviewAt,
          km.review_interval_days reviewIntervalDays, km.updated_at updatedAt
        FROM knowledge_mastery km JOIN documents d ON d.id=km.document_id
        WHERE km.user_id=#{userId} ORDER BY km.updated_at DESC
        """)
    List<Map<String, Object>> mastery(@Param("userId") Long userId);

    @Select("""
        SELECT km.document_id documentId, d.title documentTitle, km.knowledge_key knowledgeKey,
          km.mastery_score masteryScore, km.next_review_at nextReviewAt, km.weakness_json weaknessJson
        FROM knowledge_mastery km JOIN documents d ON d.id=km.document_id
        WHERE km.user_id=#{userId} AND km.next_review_at <= CURRENT_TIMESTAMP(3)
        ORDER BY km.next_review_at
        """)
    List<Map<String, Object>> dueReviews(@Param("userId") Long userId);

    @Select("""
        SELECT qa.id, qa.session_id sessionId, ls.document_id documentId, d.title documentTitle,
          qa.knowledge_key knowledgeKey, qa.question_json questionJson, qa.user_answer userAnswer,
          qa.evaluation_json evaluationJson, qa.score, qa.evidence_json evidenceJson, qa.created_at createdAt
        FROM quiz_attempts qa JOIN learning_sessions ls ON ls.id=qa.session_id
          JOIN documents d ON d.id=ls.document_id
        WHERE ls.user_id=#{userId} AND (qa.score IS NULL OR qa.score < .8)
        ORDER BY qa.created_at DESC
        """)
    List<Map<String, Object>> wrongAnswers(@Param("userId") Long userId);

    @Select("""
        SELECT COUNT(DISTINCT ls.document_id) documentsStudied, COUNT(qa.id) attempts,
          COALESCE(AVG(qa.score),0) averageScore,
          COALESCE(AVG(km.mastery_score),0) averageMastery,
          SUM(CASE WHEN km.next_review_at <= CURRENT_TIMESTAMP(3) THEN 1 ELSE 0 END) dueCount
        FROM learning_sessions ls LEFT JOIN quiz_attempts qa ON qa.session_id=ls.id
          LEFT JOIN knowledge_mastery km ON km.user_id=ls.user_id AND km.document_id=ls.document_id
        WHERE ls.user_id=#{userId}
        """)
    Map<String, Object> overview(@Param("userId") Long userId);
}
