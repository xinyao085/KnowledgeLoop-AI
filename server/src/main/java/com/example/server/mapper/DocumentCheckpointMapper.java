package com.example.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface DocumentCheckpointMapper {
    @Insert("""
      INSERT INTO document_processing_checkpoints(document_id,stage,progress,detail)
      VALUES(#{id},#{stage},#{progress},#{detail})
      ON DUPLICATE KEY UPDATE stage=#{stage},progress=#{progress},detail=#{detail},updated_at=CURRENT_TIMESTAMP(3)
      """)
    void save(@Param("id")Long id,@Param("stage")String stage,@Param("progress")int progress,@Param("detail")String detail);
    @Select("SELECT stage,progress,detail,updated_at updatedAt FROM document_processing_checkpoints WHERE document_id=#{id}")
    Map<String,Object> find(@Param("id")Long id);
    @Delete("DELETE FROM document_processing_checkpoints WHERE document_id=#{id}") void delete(@Param("id")Long id);
}
