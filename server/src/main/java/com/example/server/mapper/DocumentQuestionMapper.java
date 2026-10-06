package com.example.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface DocumentQuestionMapper {
    @Insert("""
      INSERT INTO document_questions(user_id,document_id,course_id,conversation_id,question,answer,evidence_json)
      VALUES(#{userId},#{documentId},#{courseId},#{conversationId},#{question},#{answer},#{evidence})
      """)
    void insert(@Param("userId") Long userId, @Param("documentId") Long documentId,
                @Param("courseId") Long courseId, @Param("conversationId") String conversationId,
                @Param("question") String question, @Param("answer") String answer,
                @Param("evidence") String evidence);
    @Select("""
      SELECT question,answer,evidence_json evidenceJson,created_at createdAt FROM document_questions
      WHERE user_id=#{userId} AND conversation_id=#{conversationId} ORDER BY created_at
      """)
    List<Map<String,Object>> history(@Param("userId") Long userId, @Param("conversationId") String conversationId);
}
