package com.example.server.mapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface DocumentParseStateMapper {
    @Delete("DELETE FROM document_parse_states WHERE document_id=#{id}")
    void delete(Long id);
    @Select("SELECT context_json FROM document_parse_states WHERE document_id=#{id}")
    String load(Long id);
    @Insert("INSERT INTO document_parse_states(document_id,context_json) VALUES(#{id},#{json}) ON DUPLICATE KEY UPDATE context_json=#{json}")
    void save(@Param("id") Long id, @Param("json") String json);
}
