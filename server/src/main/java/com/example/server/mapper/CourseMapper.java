package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.Course;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {
    @Insert("INSERT INTO course_documents(course_id,document_id,sort_order) VALUES(#{courseId},#{documentId},#{order})")
    void addDocument(@Param("courseId") Long courseId, @Param("documentId") Long documentId, @Param("order") int order);
    @Select("SELECT document_id FROM course_documents WHERE course_id=#{courseId} ORDER BY sort_order")
    List<Long> documentIds(@Param("courseId") Long courseId);
    @Delete("DELETE FROM course_documents WHERE course_id=#{courseId}")
    void deleteDocuments(@Param("courseId") Long courseId);
}
